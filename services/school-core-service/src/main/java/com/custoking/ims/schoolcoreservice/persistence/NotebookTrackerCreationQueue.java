package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.infrastructure.OperatorResolver;
import com.custoking.ims.schoolcoreservice.infrastructure.TrackerGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Creates the notebook order tracker for a placed order outside the placing transaction (review fix, spec §3.3).
 * Placement {@link #enqueue enqueues} the order in its own transaction; after commit the order is {@link #attempt
 * attempted} once, and the relay trigger {@link #drain drains} whatever is left. Every peer call (identity's operator
 * directory, then operations' tracker) runs with no database transaction open and no row locked. Tracker creation is
 * idempotent on the order id, so a repeated or concurrent attempt is safe.
 *
 * <p><b>Tenant safety.</b> Draining has no user, so a claim enables the RLS bypass transaction-locally only to pick one
 * due row across schools, turns it off again and scopes the rest of that transaction to the row's school (the pattern of
 * {@link AbsenteeNotificationDeliveryRepository}). Every later write is scoped to that school too.
 */
@Repository
public class NotebookTrackerCreationQueue {
    private static final Logger LOG = LoggerFactory.getLogger(NotebookTrackerCreationQueue.class);
    private static final String NOTEBOOKS = "NOTEBOOKS";

    private final JdbcClient jdbc;
    private final TrackerGateway tracker;
    private final OperatorResolver operators;
    /** Suspends any surrounding transaction, including the placing one whose after-commit hook calls {@link #attempt}. */
    private final TransactionTemplate outsideTransaction;
    private final TransactionTemplate step;

    public NotebookTrackerCreationQueue(JdbcClient jdbc, TrackerGateway tracker, OperatorResolver operators,
                                        PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.tracker = tracker;
        this.operators = operators;
        this.outsideTransaction = new TransactionTemplate(manager);
        this.outsideTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        this.step = new TransactionTemplate(manager);
        this.step.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** In the caller's transaction: the order's tracker is due now. A row already queued becomes due now. */
    public void enqueue(String orderId, long schoolId) {
        jdbc.sql("""
                INSERT INTO catalog.notebook_tracker_creation_queue (order_id, school_id) VALUES (:order, :school)
                ON CONFLICT (order_id) DO UPDATE SET next_attempt_at = now()
                """).param("order", orderId).param("school", schoolId).update();
    }

    /**
     * One attempt for this order, once its queueing has committed. Never throws: whatever fails stays queued for the
     * relay trigger. Returns true when the tracker was created.
     */
    public boolean attempt(String orderId) {
        try {
            return Boolean.TRUE.equals(outsideTransaction.execute(status -> claim(orderId, new ArrayList<>()).map(this::create).orElse(false)));
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
        Integer created = outsideTransaction.execute(status -> {
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
        });
        return created == null ? 0 : created;
    }

    /**
     * Claims one due row (this order's, or any) and reads what the tracker needs, in a short transaction of its own.
     * Moving the next attempt out by the backoff at claim time doubles as the lease: a concurrent drain skips the row
     * while this attempt's calls are in flight, and an attempt that dies midway is retried after the backoff.
     * {@code claimed} excludes orders already tried in this drain and receives the picked order id before anything else
     * can fail, so the caller can skip it.
     */
    private Optional<Claim> claim(String orderId, List<String> claimed) {
        Optional<Claim> result = step.execute(status -> {
            jdbc.sql("SELECT set_config('app.bypass_rls', 'on', true)").query(String.class).single();
            Optional<Due> due;
            try {
                var select = jdbc.sql("SELECT order_id, school_id FROM catalog.notebook_tracker_creation_queue WHERE next_attempt_at <= now()"
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
            return Optional.of(new Claim(order, school, attempts, facts));
        });
        return result == null ? Optional.empty() : result;
    }

    /**
     * The peer calls for one claimed order, outside any transaction. When no operator can be chosen (or identity cannot
     * be asked) the tracker is created unassigned and superadmin assigns it.
     */
    private boolean create(Claim claim) {
        TrackerGateway.NewTracker facts = claim.tracker();
        if (facts == null || facts.placedAt() == null) return failed(claim, "The order's placement facts could not be read");
        Long assignee = null;
        try {
            OperatorResolver.Resolution picked = operators.resolve(claim.schoolId(), NOTEBOOKS);
            if (picked != null && picked.userId() != null
                    && ("ITEM_SPECIFIC".equals(picked.outcome()) || "SCHOOL_WIDE".equals(picked.outcome()))) assignee = picked.userId();
        } catch (IllegalStateException unavailable) {
            LOG.warn("Order {} gets its tracker without an automatic operator: {}", claim.orderId(), unavailable.getMessage());
        }
        try {
            tracker.create(new TrackerGateway.NewTracker(facts.orderId(), facts.schoolId(), facts.customised(), facts.orderedCount(),
                    facts.placedAt(), facts.timeZone(), facts.placedBy(), assignee, facts.schoolName()));
        } catch (RuntimeException failure) {
            // The clients' failures carry fixed messages; anything else is recorded by type only.
            return failed(claim, failure instanceof IllegalStateException ? failure.getMessage() : failure.getClass().getSimpleName());
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

    /** The row keeps the backoff its claim set; only the reason is recorded. */
    private boolean failed(Claim claim, String reason) {
        LOG.warn("Tracker for order {} not created on attempt {}: {}", claim.orderId(), claim.attempts(), reason);
        step.executeWithoutResult(status -> {
            scopeTo(claim.schoolId());
            jdbc.sql("UPDATE catalog.notebook_tracker_creation_queue SET last_error = left(:error, 500) WHERE order_id = :order AND school_id = :school")
                    .param("error", String.valueOf(reason)).param("order", claim.orderId()).param("school", claim.schoolId()).update();
        });
        return false;
    }

    private void scopeTo(long schoolId) {
        jdbc.sql("SELECT set_config('app.bypass_rls', 'off', true), set_config('app.current_school_id', :school, true)")
                .param("school", String.valueOf(schoolId)).query().singleRow();
    }

    private record Due(String orderId, long schoolId) { }

    /** {@code tracker} holds the order's facts without an assignee; null when they could not be read. */
    private record Claim(String orderId, long schoolId, int attempts, TrackerGateway.NewTracker tracker) { }
}
