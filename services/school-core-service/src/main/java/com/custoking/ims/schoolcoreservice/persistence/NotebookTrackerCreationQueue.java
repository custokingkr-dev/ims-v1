package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.infrastructure.OperatorResolver;
import com.custoking.ims.schoolcoreservice.infrastructure.TrackerGateway;
import com.custoking.ims.schoolcoreservice.outbox.QueueBacklog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Creates the notebook order tracker for a placed order outside the placing transaction (review fix, spec §3.3).
 * Placement {@link #enqueue enqueues} the order in its own transaction; once that transaction has committed and released
 * its connection the order is {@link #attempt attempted} once, and the relay trigger {@link #drain drains} whatever is
 * left. Tracker creation is idempotent on the order id, so a repeated or concurrent attempt is safe.
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
 */
@Repository
public class NotebookTrackerCreationQueue implements QueueBacklog {
    private static final Logger LOG = LoggerFactory.getLogger(NotebookTrackerCreationQueue.class);
    private static final String NOTEBOOKS = "NOTEBOOKS";
    /** About two days of retries at the backoff (2, 4, 8, 16, 32 minutes, then hourly). */
    public static final int MAX_ATTEMPTS = 48;
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

    public NotebookTrackerCreationQueue(JdbcClient jdbc, TrackerGateway tracker, OperatorResolver operators,
                                        PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.tracker = tracker;
        this.operators = operators;
        this.step = new TransactionTemplate(manager);
        this.step.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * In the caller's transaction: the order's tracker is due now. A row already queued, even one given up on, is due again
     * now with fresh retries: someone asked for the tracker.
     */
    public void enqueue(String orderId, long schoolId) {
        jdbc.sql("""
                INSERT INTO catalog.notebook_tracker_creation_queue (order_id, school_id) VALUES (:order, :school)
                ON CONFLICT (order_id) DO UPDATE SET next_attempt_at = now(), attempts = 0, dead_lettered_at = NULL
                """).param("order", orderId).param("school", schoolId).update();
    }

    /**
     * One attempt for this order, once its queueing has committed and the caller's connection is back in the pool. Never
     * throws: whatever fails stays queued for the relay trigger. Returns true when the tracker was created.
     */
    public boolean attempt(String orderId) {
        if (holdsAConnection()) {
            LOG.warn("Tracker for order {} is left for the relay: an attempt must not run inside a transaction", orderId);
            return false;
        }
        try {
            return claim(orderId, new ArrayList<>()).map(this::create).orElse(false);
        } catch (RuntimeException failure) {
            LOG.warn("Tracker for order {} stays queued for the relay: {}", orderId, failure.toString());
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
     * so its creation row, pending or given up on, is done. Returns whether there was one.
     */
    public boolean done(String orderId) {
        return jdbc.sql("DELETE FROM catalog.notebook_tracker_creation_queue WHERE order_id = :order").param("order", orderId).update() > 0;
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
                    FROM catalog.notebook_tracker_creation_queue q
                    JOIN catalog.catalog_orders co ON co.id = q.order_id AND co.school_id = q.school_id
                    WHERE co.status NOT IN (:closed)
                    """).param("closed", List.copyOf(CLOSED_ORDER_STATUSES))
                    .query((rs, n) -> new Backlog(rs.getLong("pending"), rs.getLong("dead_letter"), rs.getLong("oldest_age"))).single();
        });
    }

    @Override
    public String queueName() {
        return "notebook_tracker_creation_queue";
    }

    /** A transaction (or an after-commit hook of one) still holds a pooled connection on this thread. */
    private static boolean holdsAConnection() {
        return TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isSynchronizationActive();
    }

    /**
     * Claims one due row (this order's, or any) that has not been given up on, and reads what the tracker needs, in a short
     * transaction of its own. Moving the next attempt out by the backoff at claim time doubles as the lease: a concurrent
     * drain skips the row while this attempt's calls are in flight, and an attempt that dies midway is retried after the
     * backoff. {@code claimed} excludes orders already tried in this drain and receives the picked order id before anything
     * else can fail, so the caller can skip it.
     */
    private Optional<Claim> claim(String orderId, List<String> claimed) {
        Optional<Claim> result = step.execute(status -> {
            jdbc.sql("SELECT set_config('app.bypass_rls', 'on', true)").query(String.class).single();
            Optional<Due> due;
            try {
                var select = jdbc.sql("SELECT order_id, school_id FROM catalog.notebook_tracker_creation_queue"
                        + " WHERE next_attempt_at <= now() AND dead_lettered_at IS NULL"
                        + (orderId == null ? "" : " AND order_id = :order") + (claimed.isEmpty() ? "" : " AND order_id NOT IN (:claimed)")
                        + " ORDER BY next_attempt_at, order_id LIMIT 1 FOR UPDATE SKIP LOCKED");
                if (orderId != null) select = select.param("order", orderId);
                if (!claimed.isEmpty()) select = select.param("claimed", List.copyOf(claimed));
                due = select.query((rs, n) -> new Due(rs.getString("order_id"), rs.getLong("school_id"))).optional();
            } finally {
                jdbc.sql("SELECT set_config('app.bypass_rls', 'off', true)").query(String.class).single();
            }
            if (due.isEmpty()) return Optional.<Claim>empty();
            String order = due.get().orderId();
            long school = due.get().schoolId();
            claimed.add(order);
            scopeTo(school);
            String orderStatus = jdbc.sql("SELECT status FROM catalog.catalog_orders WHERE id = :order AND school_id = :school")
                    .param("order", order).param("school", school).query(String.class).optional().orElse(null);
            if (orderStatus != null && CLOSED_ORDER_STATUSES.contains(orderStatus)) {
                // A closed order gets no tracker: its row is done, not retried.
                jdbc.sql("DELETE FROM catalog.notebook_tracker_creation_queue WHERE order_id = :order AND school_id = :school")
                        .param("order", order).param("school", school).update();
                return Optional.of(new Claim(order, school, 0, null, true));
            }
            // The exponent is capped too: 2^1024 overflows float8, and a row failing that long must still be claimable.
            int attempts = jdbc.sql("""
                    UPDATE catalog.notebook_tracker_creation_queue SET attempts = attempts + 1,
                        next_attempt_at = now() + make_interval(mins => LEAST(power(2, LEAST(attempts + 1, 6)), 60)::int)
                    WHERE order_id = :order AND school_id = :school RETURNING attempts
                    """).param("order", order).param("school", school).query(Integer.class).single();
            TrackerGateway.NewTracker facts = jdbc.sql("""
                    SELECT co.placed_at, co.placed_by, s.time_zone, s.name,
                        COALESCE(COALESCE(co.order_selections -> 'CUSTOMIZATION' ->> 'code',
                            co.order_selections ->> 'CUSTOMIZATION') = 'CUSTOMIZED', false) AS customised,
                        (SELECT COALESCE(sum(l.book_count), 0) FROM catalog.catalog_order_lines l WHERE l.order_id = co.id) AS ordered
                    FROM catalog.catalog_orders co JOIN tenant_school.schools s ON s.id = co.school_id
                    WHERE co.id = :order AND co.school_id = :school
                    """).param("order", order).param("school", school).query((rs, n) -> new TrackerGateway.NewTracker(order, school,
                    rs.getBoolean("customised"), rs.getInt("ordered"), rs.getObject("placed_at", OffsetDateTime.class),
                    rs.getString("time_zone"), rs.getObject("placed_by", Long.class), null, rs.getString("name"))).optional().orElse(null);
            return Optional.of(new Claim(order, school, attempts, facts, false));
        });
        return result == null ? Optional.empty() : result;
    }

    /**
     * The peer calls for one claimed order, with no transaction open. Identity must answer first: without its answer the
     * order's operator is unknown, not absent, so the order waits for a later attempt. A definitive answer of no operator,
     * or of several, creates the tracker unassigned and superadmin assigns it.
     */
    private boolean create(Claim claim) {
        if (claim.closed()) {
            LOG.info("Order {} closed before its tracker was created; its creation row is dropped", claim.orderId());
            return false;
        }
        TrackerGateway.NewTracker facts = claim.tracker();
        if (facts == null || facts.placedAt() == null) return failed(claim, "The order's placement facts could not be read");
        OperatorResolver.Resolution picked;
        try {
            picked = operators.resolve(claim.schoolId(), NOTEBOOKS);
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
            tracker.create(new TrackerGateway.NewTracker(facts.orderId(), facts.schoolId(), facts.customised(), facts.orderedCount(),
                    facts.placedAt(), facts.timeZone(), facts.placedBy(), assignee, facts.schoolName()));
        } catch (RuntimeException failure) {
            return failed(claim, reason(failure));
        }
        step.executeWithoutResult(status -> {
            scopeTo(claim.schoolId());
            jdbc.sql("DELETE FROM catalog.notebook_tracker_creation_queue WHERE order_id = :order AND school_id = :school")
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
                    UPDATE catalog.notebook_tracker_creation_queue SET last_error = left(:error, 500),
                        dead_lettered_at = CASE WHEN :giveUp THEN now() ELSE dead_lettered_at END
                    WHERE order_id = :order AND school_id = :school AND attempts = :attempts
                    """).param("error", String.valueOf(reason)).param("giveUp", giveUp).param("order", claim.orderId())
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

    private record Due(String orderId, long schoolId) { }

    /**
     * {@code tracker} holds the order's facts without an assignee; null when they could not be read. {@code closed}: the
     * order had closed, and the claim dropped its row instead.
     */
    private record Claim(String orderId, long schoolId, int attempts, TrackerGateway.NewTracker tracker, boolean closed) { }
}
