package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.infrastructure.OperatorResolver;
import com.custoking.ims.schoolcoreservice.infrastructure.TrackerGateway;
import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import com.custoking.ims.schoolcoreservice.outbox.QueueBacklog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Creates the order workflow of a placed order outside the placing transaction (review fix, spec §3.3), for every category
 * whose template the order was placed under (item order workflows design §3.6; the class keeps its notebook name, D6, but
 * the table does not, see below). Placement {@link #enqueue enqueues} the order in its own transaction; once that transaction has committed and
 * released its connection the order is {@link #attempt attempted} once, and the relay trigger {@link #drain drains}
 * whatever is left. Creation is idempotent on the order id, so a repeated or concurrent attempt is safe, and a create for a
 * workflow that already exists fills in its missing order number (catalog V38 queues those).
 *
 * <p>The claim reads the template, item code and noun, the order number, the summed line quantities and, for an order
 * moved off the retired product workflow, its V36 progress. The operator is resolved for the order's own category.
 *
 * <p><b>No connection is held during a peer call.</b> The pool has five connections. An attempt is refused while the
 * caller still has a transaction (or an after-commit hook, whose connection is not released yet); otherwise each database
 * step is a short transaction of its own that returns its connection before identity's operator directory, then
 * operations' tracker, is asked (the shape of operations' {@code TrackerStatusOutbox}).
 *
 * <p><b>Retries.</b> A failed attempt keeps the doubling backoff, capped at an hour, that its claim set. After
 * {@link #MAX_ATTEMPTS} the row is dead-lettered: drains skip it, an ERROR log says so, and the outbox health report counts
 * it ({@link #backlog}). Starting the tracker by hand ({@link #enqueue}) revives it. A row whose order closes is dropped,
 * by the tracker's own status push ({@link #done}) or by the next claim, and is never counted.
 *
 * <p><b>Tenant safety.</b> Draining has no user, so a claim enables the RLS bypass transaction-locally only to pick one
 * due row across schools, turns it off again and scopes the rest of that transaction to the row's school (the pattern of
 * {@link AbsenteeNotificationDeliveryRepository}). Every later write is scoped to that school too.
 *
 * <p><b>The table.</b> Since catalog V38 the queue is {@value #TABLE}, which no earlier school-core reads: during a release
 * the previous revision still serves (in prod beside this one, through the canary), and its drain would give any queued
 * order a notebook workflow. V38 forwards whatever that revision queues into this table, and queues for {@code repair} an
 * order it places without a number. A claim repairs such an order first: it mints the number, moves an open structured
 * order of a template category onto its workflow as V38 did, and tells reporting. Only a tracker-managed order is created;
 * any other row is dropped.
 */
@Repository
public class NotebookTrackerCreationQueue implements QueueBacklog {
    private static final Logger LOG = LoggerFactory.getLogger(NotebookTrackerCreationQueue.class);
    /** About two days of retries at the backoff (2, 4, 8, 16, 32 minutes, then hourly). */
    public static final int MAX_ATTEMPTS = 48;
    /** The queue's table (catalog V38). */
    static final String TABLE = "catalog.order_workflow_creation_queue";
    /** Statuses that are not open (catalog V38): such an order is not moved onto a workflow by a repair. */
    static final List<String> NOT_OPEN_STATUSES = List.of("DRAFT", "CANCELLED", "DELIVERED", "FULFILLED", "REJECTED", "RETURNED", "COMPLETED");
    /** The engine refuses a longer noun (operations V18 stores it in VARCHAR(40)); a category label may be 80 characters. */
    static final int ITEM_NOUN_MAX = 40;
    static final String DEAD_LETTERED_EVENT = "tracker_creation_dead_lettered";
    /** Identity's definitive answers that leave the order without an operator; superadmin assigns it in the tracker. */
    private static final Set<String> NO_SINGLE_OPERATOR = Set.of("NONE", "AMBIGUOUS");
    private static final Set<String> ONE_OPERATOR = Set.of("ITEM_SPECIFIC", "SCHOOL_WIDE");
    /** An order in one of these statuses gets no tracker: its row, pending or given up on, has nothing left to do. */
    static final Set<String> CLOSED_ORDER_STATUSES = Set.of("CANCELLED", "DELIVERED", "COMPLETED");

    private final JdbcClient jdbc;
    private final TrackerGateway tracker;
    private final OperatorResolver operators;
    private final TransactionTemplate step;
    /** Tells reporting about a repaired order; null only where a test builds the queue without one. */
    private final OutboxWriter outbox;

    public NotebookTrackerCreationQueue(JdbcClient jdbc, TrackerGateway tracker, OperatorResolver operators,
                                        PlatformTransactionManager manager) {
        this(jdbc, tracker, operators, manager, null);
    }

    @Autowired
    public NotebookTrackerCreationQueue(JdbcClient jdbc, TrackerGateway tracker, OperatorResolver operators,
                                        PlatformTransactionManager manager, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.tracker = tracker;
        this.operators = operators;
        this.outbox = outbox;
        this.step = new TransactionTemplate(manager);
        this.step.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * In the caller's transaction: the order's tracker is due now. A row already queued, even one given up on, is due again
     * now with fresh retries: someone asked for the tracker.
     */
    public void enqueue(String orderId, long schoolId) {
        jdbc.sql("""
                INSERT INTO %s (order_id, school_id) VALUES (:order, :school)
                ON CONFLICT (order_id) DO UPDATE SET next_attempt_at = now(), attempts = 0, dead_lettered_at = NULL
                """.formatted(TABLE)).param("order", orderId).param("school", schoolId).update();
    }

    /**
     * One attempt for this order, once its queueing has committed and the caller's connection is back in the pool. Never
     * throws: whatever fails stays queued for the relay trigger. Returns true when the tracker was created.
     */
    public boolean attempt(String orderId) {
        if (holdsAConnection()) {
            LOG.warn("Tracker for order {} is left for the relay: an attempt must not run inside a transaction", loggable(orderId));
            return false;
        }
        try {
            return claim(orderId, new ArrayList<>()).map(this::create).orElse(false);
        } catch (RuntimeException failure) {
            LOG.warn("Tracker for order {} stays queued for the relay: {}", loggable(orderId), loggable(failure.toString()));
            return false;
        }
    }

    /**
     * Works up to {@code max} due orders; returns how many trackers were created. One order's failure never ends the
     * batch: that order is skipped until the next drain and the drain moves on to the next due order.
     */
    public int drain(int max) {
        if (holdsAConnection()) throw new IllegalStateException("The tracker creation queue is drained outside any transaction");
        int count = 0;
        List<String> claimed = new ArrayList<>();
        for (int i = 0; i < max; i++) {
            int before = claimed.size();
            try {
                Optional<Claim> claim = claim(null, claimed);
                if (claim.isEmpty()) break;
                if (create(claim.get())) count++;
            } catch (RuntimeException failure) {
                // Nothing was picked, so the queue itself is failing (the database, say): the trigger fails too.
                if (claimed.size() == before) throw failure;
                LOG.warn("Tracker for order {} failed and waits for the next drain: {}", claimed.getLast(), failure.toString());
            }
        }
        return count;
    }

    /**
     * In the caller's transaction, which has the order in scope: the order's tracker exists (it has just pushed a status),
     * so its creation row, pending or given up on, is done. Returns whether there was one. The status push does not call
     * this for an open, numbered order: its row also carries the number to the workflow (design D13), so it stays until a
     * create succeeds.
     */
    public boolean done(String orderId) {
        return jdbc.sql("DELETE FROM " + TABLE + " WHERE order_id = :order").param("order", orderId).update() > 0;
    }

    /**
     * Rows still being retried, rows given up on, and the age of the oldest row still being retried, across every school.
     * Added into the outbox health report so the outbox alerts cover this queue. A row whose order has closed is not
     * counted: nothing can be done for it, and ensure refuses a closed order, so it would keep the alert firing forever.
     */
    @Override
    public Backlog backlog() {
        return step.execute(status -> {
            // Counts only, for the health log; the bypass ends with this transaction.
            jdbc.sql("SELECT set_config('app.bypass_rls', 'on', true)").query(String.class).single();
            return jdbc.sql("""
                    SELECT count(*) FILTER (WHERE q.dead_lettered_at IS NULL) AS pending,
                           count(*) FILTER (WHERE q.dead_lettered_at IS NOT NULL) AS dead_letter,
                           COALESCE(EXTRACT(EPOCH FROM now() - min(q.created_at) FILTER (WHERE q.dead_lettered_at IS NULL))::bigint, 0) AS oldest_age
                    FROM %s q
                    JOIN catalog.catalog_orders co ON co.id = q.order_id AND co.school_id = q.school_id
                    WHERE co.status NOT IN (:closed)
                    """.formatted(TABLE)).param("closed", List.copyOf(CLOSED_ORDER_STATUSES))
                    .query((rs, n) -> new Backlog(rs.getLong("pending"), rs.getLong("dead_letter"), rs.getLong("oldest_age"))).single();
        });
    }

    @Override
    public String queueName() {
        return "order_workflow_creation_queue";
    }

    /** A transaction (or an after-commit hook of one) still holds a pooled connection on this thread. */
    private static boolean holdsAConnection() {
        return TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isSynchronizationActive();
    }

    /**
     * Claims one due row (this order's, or any) that has not been given up on, and reads what the tracker needs, in short
     * transactions of its own. Moving the next attempt out by the backoff at claim time doubles as the lease: a concurrent
     * drain skips the row while this attempt's calls are in flight, and an attempt that dies midway is retried after the
     * backoff. {@code claimed} excludes orders already tried in this drain and receives the picked order id before anything
     * else can fail, so the caller can skip it. A row queued for repair is repaired in a transaction of its own after the
     * lease has committed, so a repair that fails keeps the backoff and records why.
     */
    private Optional<Claim> claim(String orderId, List<String> claimed) {
        Optional<Claim> result = step.execute(status -> {
            jdbc.sql("SELECT set_config('app.bypass_rls', 'on', true)").query(String.class).single();
            Optional<Due> due;
            try {
                var select = jdbc.sql("SELECT order_id, school_id, repair FROM " + TABLE
                        + " WHERE next_attempt_at <= now() AND dead_lettered_at IS NULL"
                        + (orderId == null ? "" : " AND order_id = :order") + (claimed.isEmpty() ? "" : " AND order_id NOT IN (:claimed)")
                        + " ORDER BY next_attempt_at, order_id LIMIT 1 FOR UPDATE SKIP LOCKED");
                if (orderId != null) select = select.param("order", orderId);
                if (!claimed.isEmpty()) select = select.param("claimed", List.copyOf(claimed));
                due = select.query((rs, n) -> new Due(rs.getString("order_id"), rs.getLong("school_id"), rs.getBoolean("repair"))).optional();
            } finally {
                jdbc.sql("SELECT set_config('app.bypass_rls', 'off', true)").query(String.class).single();
            }
            if (due.isEmpty()) return Optional.<Claim>empty();
            String order = due.get().orderId();
            long school = due.get().schoolId();
            claimed.add(order);
            scopeTo(school);
            if (!due.get().repair()) {
                String orderStatus = jdbc.sql("SELECT status FROM catalog.catalog_orders WHERE id = :order AND school_id = :school")
                        .param("order", order).param("school", school).query(String.class).optional().orElse(null);
                // A closed order gets no tracker: its row is done, not retried.
                if (orderStatus != null && CLOSED_ORDER_STATUSES.contains(orderStatus)) return Optional.of(drop(order, school, 0, Drop.CLOSED));
            }
            // The exponent is capped too: 2^1024 overflows float8, and a row failing that long must still be claimable.
            int attempts = jdbc.sql("""
                    UPDATE %s SET attempts = attempts + 1,
                        next_attempt_at = now() + make_interval(mins => LEAST(power(2, LEAST(attempts + 1, 6)), 60)::int)
                    WHERE order_id = :order AND school_id = :school RETURNING attempts
                    """.formatted(TABLE)).param("order", order).param("school", school).query(Integer.class).single();
            if (due.get().repair()) return Optional.of(new Claim(order, school, attempts, null, Drop.REPAIR_PENDING));
            return Optional.of(facts(order, school, attempts));
        });
        if (result == null || result.isEmpty() || result.get().drop() != Drop.REPAIR_PENDING) return result == null ? Optional.empty() : result;
        // Queued for repair: repaired in a transaction of its own, then read afresh.
        Claim leased = result.get();
        try {
            step.executeWithoutResult(status -> {
                scopeTo(leased.schoolId());
                repair(leased.orderId(), leased.schoolId());
            });
        } catch (RuntimeException failure) {
            failed(leased, "the order could not be repaired: " + reason(failure));
            return Optional.of(new Claim(leased.orderId(), leased.schoolId(), leased.attempts(), null, Drop.REPAIR_FAILED));
        }
        return Optional.ofNullable(step.execute(status -> {
            scopeTo(leased.schoolId());
            return facts(leased.orderId(), leased.schoolId(), leased.attempts());
        }));
    }

    /**
     * In a transaction scoped to the order's school: what the tracker needs, or the row dropped when nothing is left to
     * create. A closed order gets no workflow, and neither does an order that is not tracker-managed: only a placement under
     * a template, catalog V38 or a repair makes an order tracker-managed, so such a row was queued for a repair that found
     * the order runs on no workflow (a number was all it lacked).
     */
    private Claim facts(String order, long school, int attempts) {
        Map<String, Object> state = jdbc.sql("""
                SELECT status, attributes @> '{"trackerManaged": true}'::jsonb AS managed FROM catalog.catalog_orders
                WHERE id = :order AND school_id = :school
                """).param("order", order).param("school", school).query().listOfRows().stream().findFirst().orElse(null);
        if (state != null && CLOSED_ORDER_STATUSES.contains(String.valueOf(state.get("status")))) return drop(order, school, attempts, Drop.CLOSED);
        if (state != null && !Boolean.TRUE.equals(state.get("managed"))) return drop(order, school, attempts, Drop.NO_WORKFLOW);
        // The template is the one the category ran when the order was placed (or moved onto the engine by V38); a notebook
        // order placed before templates were recorded runs the notebook one. A wholesale notebook skips the design
        // steps; every other item takes them, so customised is true for it. A noun taken from a long label is cut to fit.
        TrackerGateway.NewTracker facts = jdbc.sql("""
                SELECT co.placed_at, co.placed_by, co.category, co.order_number, s.time_zone, s.name,
                    COALESCE(co.attributes ->> 'workflowDefinition', pc.workflow_definition,
                        CASE WHEN co.category = 'NOTEBOOKS' THEN 'NOTEBOOK_ORDER_TRACKER' END) AS definition_id,
                    pc.order_code AS item_code,
                    COALESCE(NULLIF(btrim(pc.item_noun), ''), rtrim(left(lower(btrim(pc.label)), :nounMax))) AS item_noun,
                    co.category <> 'NOTEBOOKS' OR COALESCE(COALESCE(co.order_selections -> 'CUSTOMIZATION' ->> 'code',
                        co.order_selections ->> 'CUSTOMIZATION') = 'CUSTOMIZED', false) AS customised,
                    (SELECT COALESCE(sum(l.book_count), 0) FROM catalog.catalog_order_lines l WHERE l.order_id = co.id) AS ordered
                FROM catalog.catalog_orders co JOIN tenant_school.schools s ON s.id = co.school_id
                LEFT JOIN catalog.product_categories pc ON pc.code = co.category
                WHERE co.id = :order AND co.school_id = :school
                """).param("order", order).param("school", school).param("nounMax", ITEM_NOUN_MAX)
                .query((rs, n) -> new TrackerGateway.NewTracker(order, school,
                rs.getBoolean("customised"), rs.getInt("ordered"), rs.getObject("placed_at", OffsetDateTime.class),
                rs.getString("time_zone"), rs.getObject("placed_by", Long.class), null, rs.getString("name"),
                rs.getString("definition_id"), rs.getString("category"), rs.getString("item_code"), rs.getString("item_noun"),
                rs.getString("order_number"), List.of())).optional().orElse(null);
        if (facts != null) facts = withImportedProgress(facts);
        return new Claim(order, school, attempts, facts, null);
    }

    /** Deletes the row, in the caller's transaction scoped to the order's school: nothing is left for it to create. */
    private Claim drop(String order, long school, int attempts, Drop why) {
        jdbc.sql("DELETE FROM " + TABLE + " WHERE order_id = :order AND school_id = :school")
                .param("order", order).param("school", school).update();
        return new Claim(order, school, attempts, null, why);
    }

    /**
     * In a transaction scoped to the order's school, for an order that a school-core which did not number orders placed
     * (catalog V38's commit-time trigger queued it): the placed order gets its number (design D13, dated by its placement),
     * an open structured order of a template category moves onto its workflow exactly as catalog V38 moved the orders open at
     * the release (D4), and reporting is told when either changed. Idempotent: a number is minted once, and a moved order is
     * tracker-managed. The order's row lock is held, as a placement holds it, so neither can race an edit of the order.
     */
    private void repair(String order, long school) {
        Map<String, Object> row = jdbc.sql("""
                SELECT status, order_number FROM catalog.catalog_orders WHERE id = :order AND school_id = :school FOR UPDATE
                """).param("order", order).param("school", school).query().listOfRows().stream().findFirst().orElse(null);
        if (row != null) {
            boolean changed = false;
            if (!"DRAFT".equals(row.get("status")) && row.get("order_number") == null) changed = OrderNumberAllocator.mint(jdbc, order) != null;
            changed |= jdbc.sql("""
                    UPDATE catalog.catalog_orders co
                    SET attributes = co.attributes || jsonb_build_object('trackerManaged', true, 'trackerPending', true,
                            'workflowDefinition', pc.workflow_definition)
                    FROM catalog.product_categories pc
                    WHERE co.id = :order AND co.school_id = :school AND pc.code = co.category
                      AND pc.workflow_definition IS NOT NULL AND co.category <> 'NOTEBOOKS' AND co.form_version = 2
                      AND co.status NOT IN (:notOpen) AND NOT co.attributes @> '{"trackerManaged": true}'::jsonb
                    """).param("order", order).param("school", school).param("notOpen", NOT_OPEN_STATUSES).update() > 0;
            if (changed) {
                Map<String, Object> repaired = jdbc.sql("SELECT * FROM catalog.catalog_orders WHERE id = :order AND school_id = :school")
                        .param("order", order).param("school", school).query().listOfRows().getFirst();
                if (outbox != null) {
                    outbox.append("catalog-order.upserted.v1", "CatalogOrderUpserted:" + order, "CatalogOrder", order, school,
                            CatalogOrderFormService.upsertedPayload(jdbc, repaired));
                }
                LOG.info("Order {} was placed without an order number and is repaired: number {}", order, repaired.get("order_number"));
            }
        }
        jdbc.sql("UPDATE " + TABLE + " SET repair = false WHERE order_id = :order AND school_id = :school")
                .param("order", order).param("school", school).update();
    }

    /**
     * The order's retired PR #380 product workflow (catalog V36), if it has one: its completed stages, and the stage in
     * progress when it holds a production update or a note, mapped onto the engine's step codes (design D4). Read in the
     * claim's transaction, scoped to the order's school; the V36 tables stay readable.
     */
    private TrackerGateway.NewTracker withImportedProgress(TrackerGateway.NewTracker facts) {
        if ("NOTEBOOKS".equals(facts.categoryCode())) return facts;
        List<TrackerGateway.ImportedStep> steps = jdbc.sql("""
                SELECT stage_code, stage_order, status, completed_at, updated_by, data ->> 'details' AS details, stage_note
                FROM catalog.product_order_workflow_steps
                WHERE order_id = :order AND school_id = :school
                  AND (status = 'COMPLETED' OR (status = 'ACTIVE' AND (COALESCE(btrim(data ->> 'details'), '') <> ''
                       OR COALESCE(btrim(stage_note), '') <> '')))
                ORDER BY stage_order
                """).param("order", facts.orderId()).param("school", facts.schoolId()).query((rs, n) -> new TrackerGateway.ImportedStep(
                        importedStepCode(rs.getString("stage_code")), rs.getString("stage_code"), rs.getInt("stage_order"),
                        rs.getString("status"), rs.getObject("completed_at", OffsetDateTime.class), rs.getObject("updated_by", Long.class),
                        blankToNull(rs.getString("details")), blankToNull(rs.getString("stage_note")))).list();
        if (steps.isEmpty()) return facts;
        return new TrackerGateway.NewTracker(facts.orderId(), facts.schoolId(), facts.customised(), facts.orderedCount(),
                facts.placedAt(), facts.timeZone(), facts.placedBy(), facts.assigneeUserId(), facts.schoolName(), facts.definitionId(),
                facts.categoryCode(), facts.itemCode(), facts.itemNoun(), facts.orderNumber(), steps);
    }

    /** V36 stage codes map one to one onto the nine-step templates, except these two (design D4). */
    static String importedStepCode(String v36StageCode) {
        return switch (v36StageCode) {
            case "DESIGN_ACTIVITY" -> "DESIGNING_ACTIVITY";
            case "FINAL_VERIFICATION" -> "FINAL_APPROVAL";
            default -> v36StageCode;
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * The peer calls for one claimed order, with no transaction open. Identity must answer first: without its answer the
     * order's operator is unknown, not absent, so the order waits for a later attempt. A definitive answer of no operator,
     * or of several, creates the tracker unassigned and superadmin assigns it.
     */
    private boolean create(Claim claim) {
        if (claim.drop() == Drop.CLOSED) {
            LOG.info("Order {} closed before its tracker was created; its creation row is dropped", claim.orderId());
            return false;
        }
        if (claim.drop() == Drop.NO_WORKFLOW) {
            LOG.info("Order {} runs on no order workflow; its creation row is dropped", claim.orderId());
            return false;
        }
        // Recorded already by the repair's own failure.
        if (claim.drop() == Drop.REPAIR_FAILED) return false;
        TrackerGateway.NewTracker facts = claim.tracker();
        if (facts == null || facts.placedAt() == null) return failed(claim, "The order's placement facts could not be read");
        // Superadmin can switch a category's template off, or clear its item code, while an order waits; it then waits
        // until one is set again or the row is given up on.
        if (facts.definitionId() == null) return failed(claim, "the order's category has no workflow template");
        if (facts.itemCode() == null) return failed(claim, "the order's category has no item code");
        OperatorResolver.Resolution picked;
        try {
            picked = operators.resolve(claim.schoolId(), facts.categoryCode());
        } catch (RuntimeException unavailable) {
            return failed(claim, reason(unavailable));
        }
        // Set.of rejects contains(null), so a missing outcome is checked first: it is no answer, and the order stays queued.
        String outcome = picked == null ? null : picked.outcome();
        Long assignee;
        if (outcome != null && ONE_OPERATOR.contains(outcome) && picked.userId() != null) assignee = picked.userId();
        else if (outcome != null && NO_SINGLE_OPERATOR.contains(outcome)) assignee = null;
        else return failed(claim, "operator directory gave no usable answer");
        try {
            tracker.create(facts.assignedTo(assignee));
        } catch (RuntimeException failure) {
            return failed(claim, reason(failure));
        }
        step.executeWithoutResult(status -> {
            scopeTo(claim.schoolId());
            jdbc.sql("DELETE FROM " + TABLE + " WHERE order_id = :order AND school_id = :school")
                    .param("order", claim.orderId()).param("school", claim.schoolId()).update();
            jdbc.sql("""
                    UPDATE catalog.catalog_orders SET attributes = attributes || jsonb_build_object('trackerPending', false)
                    WHERE id = :order AND school_id = :school
                    """).param("order", claim.orderId()).param("school", claim.schoolId()).update();
        });
        return true;
    }

    /** The clients' failures carry fixed messages; anything else is recorded by type only. */
    private static String reason(RuntimeException failure) {
        return failure instanceof IllegalStateException ? failure.getMessage() : failure.getClass().getSimpleName();
    }

    /**
     * The row keeps the backoff its claim set and records the reason; at {@link #MAX_ATTEMPTS} it is given up on. Only the
     * row as this claim left it is touched: a row revived meanwhile ({@link #enqueue}) starts its retries afresh.
     */
    private boolean failed(Claim claim, String reason) {
        boolean giveUp = claim.attempts() >= MAX_ATTEMPTS;
        Integer changed = step.execute(status -> {
            scopeTo(claim.schoolId());
            return jdbc.sql("""
                    UPDATE %s SET last_error = left(:error, 500),
                        dead_lettered_at = CASE WHEN :giveUp THEN now() ELSE dead_lettered_at END
                    WHERE order_id = :order AND school_id = :school AND attempts = :attempts
                    """.formatted(TABLE)).param("error", String.valueOf(reason)).param("giveUp", giveUp).param("order", claim.orderId())
                    .param("school", claim.schoolId()).param("attempts", claim.attempts()).update();
        });
        if (giveUp && changed != null && changed > 0) {
            LOG.error("Notebook tracker creation given up: {} {} {} {}", kv("event", DEAD_LETTERED_EVENT), kv("orderId", claim.orderId()),
                    kv("attempts", claim.attempts()), kv("lastError", String.valueOf(reason)));
        } else {
            LOG.warn("Tracker for order {} not created on attempt {}: {}", claim.orderId(), claim.attempts(), reason);
        }
        return false;
    }

    private void scopeTo(long schoolId) {
        jdbc.sql("SELECT set_config('app.bypass_rls', 'off', true), set_config('app.current_school_id', :school, true)")
                .param("school", String.valueOf(schoolId)).query().singleRow();
    }

    private record Due(String orderId, long schoolId, boolean repair) { }

    /** Why a claim creates nothing. */
    private enum Drop {
        /** The order had closed; the claim dropped its row. */
        CLOSED,
        /** The order is on no workflow (a repair found a number was all it lacked); the claim dropped its row. */
        NO_WORKFLOW,
        /** Leased, and to be repaired before its facts are read (inside {@link #claim} only). */
        REPAIR_PENDING,
        /** The repair failed; the row keeps its backoff and records why. */
        REPAIR_FAILED
    }

    /**
     * {@code tracker} holds the order's facts without an assignee; null when they could not be read or nothing is to be
     * created. {@code drop}: why nothing is created, or null.
     */
    private record Claim(String orderId, long schoolId, int attempts, TrackerGateway.NewTracker tracker, Drop drop) { }

    /** A value for a log line, with line breaks replaced so a caller-supplied id cannot forge log entries. */
    private static String loggable(String value) {
        return value == null ? null : value.replace('\r', '_').replace('\n', '_');
    }
}
