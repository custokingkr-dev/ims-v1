package com.custoking.ims.operationsservice.persistence;

import com.custoking.ims.operationsservice.infrastructure.CatalogOrderGateway;
import com.custoking.ims.operationsservice.outbox.QueueBacklog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Delivers the tracker's status to the catalogue (school-core), which shows it as the order's status (review fix, spec
 * §3.3). A step change {@link #record records} the new status with the tracker's instance version in the transaction
 * that changed the step; once that commits the order is {@link #attempt attempted} once, and the relay trigger
 * {@link #drain drains} whatever is left with a doubling backoff capped at an hour.
 *
 * <p><b>Ordering.</b> One row per order: a newer version replaces the queued status and an older one never does. A
 * delivered row is deleted only while it still holds the version that was sent, so a newer status queued during the push
 * survives for its own delivery. School-core ignores a version no newer than the one it has applied, so a late or
 * repeated push never moves an order backwards.
 *
 * <p><b>Giving up.</b> After {@link #MAX_ATTEMPTS} failed attempts the row is dead-lettered: drains skip it, an ERROR log
 * says so, and the outbox health report counts it ({@link #backlog}). A newer status for the order revives it with fresh
 * retries. A status school-core refuses because the order is closed (its 409, for a cancelled order) can never be
 * shown, so that row is dropped at once instead of being retried.
 *
 * <p><b>Tenant safety.</b> Delivery has no user, so a claim enables the RLS bypass transaction-locally only to pick one
 * due row across schools, turns it off again and scopes the rest of that transaction to the row's school (the pattern of
 * school-core's {@code NotebookTrackerCreationQueue}). Every later write for that row is scoped to its school too. The
 * peer call runs with no transaction open and no row locked.
 */
@Repository
public class TrackerStatusOutbox implements QueueBacklog {
    private static final Logger LOG = LoggerFactory.getLogger(TrackerStatusOutbox.class);
    /** About two days of retries at the backoff (2, 4, 8, 16, 32 minutes, then hourly). */
    public static final int MAX_ATTEMPTS = 48;
    static final String DEAD_LETTERED_EVENT = "tracker_status_dead_lettered";

    private final JdbcClient jdbc;
    private final CatalogOrderGateway catalog;
    private final String table;
    /** Suspends any surrounding transaction, so the peer call never runs inside one. */
    private final TransactionTemplate outsideTransaction;
    private final TransactionTemplate step;

    public TrackerStatusOutbox(JdbcClient jdbc, CatalogOrderGateway catalog, PlatformTransactionManager manager,
                               @Value("${workflow.db.schema:workflow}") String schema) {
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid schema name");
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.table = schema + ".workflow_tracker_status_outbox";
        this.outsideTransaction = new TransactionTemplate(manager);
        this.outsideTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        this.step = new TransactionTemplate(manager);
        this.step.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * In the step change's own transaction, under the acting user's scope: the order now shows {@code status}. Unless a
     * newer version is already queued, the row takes this status and version and is due at once with fresh retries, even
     * one that had been given up on.
     */
    public void record(String orderId, long schoolId, String status, long trackerVersion) {
        jdbc.sql("INSERT INTO " + table + " AS queued (order_id, school_id, status, tracker_version) "
                        + "VALUES (:order, :school, :status, :version) "
                        + "ON CONFLICT (order_id) DO UPDATE SET status = EXCLUDED.status, tracker_version = EXCLUDED.tracker_version, "
                        + "attempts = 0, next_attempt_at = now(), last_error = NULL, dead_lettered_at = NULL, updated_at = now() "
                        + "WHERE EXCLUDED.tracker_version > queued.tracker_version")
                .param("order", orderId).param("school", schoolId).param("status", status).param("version", trackerVersion).update();
    }

    /**
     * One delivery attempt for this order, once its recording has committed. Never throws: whatever fails stays queued for
     * the relay trigger. Returns true when a status was delivered.
     */
    public boolean attempt(String orderId) {
        try {
            return Boolean.TRUE.equals(outsideTransaction.execute(status -> claim(orderId, new ArrayList<>()).map(this::deliver).orElse(false)));
        } catch (RuntimeException failure) {
            LOG.warn("Status of order {} stays queued for the relay: {}", orderId, failure.toString());
            return false;
        }
    }

    /**
     * Works up to {@code max} due orders; returns how many statuses were delivered. One order's failure never ends the
     * batch: that order waits for its backoff and the drain moves on to the next due order.
     */
    public int drain(int max) {
        Integer delivered = outsideTransaction.execute(status -> {
            int count = 0;
            List<String> claimed = new ArrayList<>();
            for (int i = 0; i < max; i++) {
                int before = claimed.size();
                try {
                    Optional<Claim> claim = claim(null, claimed);
                    if (claim.isEmpty()) break;
                    if (deliver(claim.get())) count++;
                } catch (RuntimeException failure) {
                    // Nothing was picked, so the outbox itself is failing (the database, say): the trigger fails too.
                    if (claimed.size() == before) throw failure;
                    LOG.warn("Status of order {} failed and waits for the next drain: {}", claimed.getLast(), failure.toString());
                }
            }
            return count;
        });
        return delivered == null ? 0 : delivered;
    }

    /**
     * Rows still being retried, rows given up on, and how long the oldest row still being retried has waited since its
     * order's status first went undelivered, across every school. Added into the outbox health report so the outbox
     * alerts cover this outbox.
     */
    @Override
    public Backlog backlog() {
        return step.execute(status -> {
            // Counts only, for the health log; the bypass ends with this transaction.
            jdbc.sql("SELECT set_config('app.bypass_rls', 'on', true)").query(String.class).single();
            return jdbc.sql("SELECT count(*) FILTER (WHERE dead_lettered_at IS NULL) AS pending, "
                            + "count(*) FILTER (WHERE dead_lettered_at IS NOT NULL) AS dead_letter, "
                            + "COALESCE(EXTRACT(EPOCH FROM now() - min(created_at) FILTER (WHERE dead_lettered_at IS NULL))::bigint, 0) AS oldest_age "
                            + "FROM " + table)
                    .query((rs, n) -> new Backlog(rs.getLong("pending"), rs.getLong("dead_letter"), rs.getLong("oldest_age"))).single();
        });
    }

    @Override
    public String queueName() {
        return "workflow_tracker_status_outbox";
    }

    /**
     * Claims one due row (this order's, or any) that has not been given up on, in a short transaction of its own. Moving
     * the next attempt out by the backoff at claim time doubles as the lease: a concurrent drain skips the row while its
     * push is in flight, and an attempt that dies midway is retried after the backoff. {@code claimed} excludes orders
     * already tried in this drain and receives the picked order id before anything else can fail, so the caller can skip
     * it.
     */
    private Optional<Claim> claim(String orderId, List<String> claimed) {
        Optional<Claim> result = step.execute(status -> {
            jdbc.sql("SELECT set_config('app.bypass_rls', 'on', true)").query(String.class).single();
            Optional<Claim> due;
            try {
                var select = jdbc.sql("SELECT order_id, school_id, status, tracker_version FROM " + table
                        + " WHERE next_attempt_at <= now() AND dead_lettered_at IS NULL"
                        + (orderId == null ? "" : " AND order_id = :order") + (claimed.isEmpty() ? "" : " AND order_id NOT IN (:claimed)")
                        + " ORDER BY next_attempt_at, order_id LIMIT 1 FOR UPDATE SKIP LOCKED");
                if (orderId != null) select = select.param("order", orderId);
                if (!claimed.isEmpty()) select = select.param("claimed", List.copyOf(claimed));
                due = select.query((rs, n) -> new Claim(rs.getString("order_id"), rs.getLong("school_id"), rs.getString("status"),
                        rs.getLong("tracker_version"), 0)).optional();
            } finally {
                jdbc.sql("SELECT set_config('app.bypass_rls', 'off', true)").query(String.class).single();
            }
            if (due.isEmpty()) return Optional.<Claim>empty();
            Claim picked = due.get();
            claimed.add(picked.orderId());
            scopeTo(picked.schoolId());
            // The exponent is capped too: 2^1024 overflows float8, and a row failing that long must still be claimable.
            int attempts = jdbc.sql("UPDATE " + table + " SET attempts = attempts + 1, "
                            + "next_attempt_at = now() + make_interval(mins => LEAST(power(2, LEAST(attempts + 1, 6)), 60)::int) "
                            + "WHERE order_id = :order RETURNING attempts")
                    .param("order", picked.orderId()).query(Integer.class).single();
            return Optional.of(new Claim(picked.orderId(), picked.schoolId(), picked.status(), picked.trackerVersion(), attempts));
        });
        return result == null ? Optional.empty() : result;
    }

    /** The peer call for one claimed row, outside any transaction; then the row goes, unless a newer status replaced it. */
    private boolean deliver(Claim claim) {
        try {
            catalog.pushStatus(claim.orderId(), claim.status(), claim.trackerVersion());
        } catch (CatalogOrderGateway.OrderClosedException closed) {
            return dropped(claim, closed.getMessage());
        } catch (RuntimeException failure) {
            // The client's failures carry fixed messages; anything else is recorded by type only.
            return failed(claim, failure instanceof IllegalStateException ? failure.getMessage() : failure.getClass().getSimpleName());
        }
        deleteSent(claim);
        return true;
    }

    /**
     * The catalogue will never show a status for this order (it is cancelled, say): the row is done rather than retried
     * for two days into a dead letter. A newer status queued meanwhile is kept; it meets the same answer on its own turn.
     */
    private boolean dropped(Claim claim, String reason) {
        LOG.warn("Status {} of order {} dropped: the catalogue refuses it because the order is closed ({})",
                claim.status(), claim.orderId(), reason);
        deleteSent(claim);
        return false;
    }

    private void deleteSent(Claim claim) {
        step.executeWithoutResult(status -> {
            scopeTo(claim.schoolId());
            jdbc.sql("DELETE FROM " + table + " WHERE order_id = :order AND tracker_version = :sent")
                    .param("order", claim.orderId()).param("sent", claim.trackerVersion()).update();
        });
    }

    /**
     * The row keeps the backoff its claim set and records the reason; at {@link #MAX_ATTEMPTS} it is given up on. Only the
     * row as this claim left it is touched: a newer status queued meanwhile starts its retries afresh.
     */
    private boolean failed(Claim claim, String reason) {
        boolean giveUp = claim.attempts() >= MAX_ATTEMPTS;
        Integer changed = step.execute(status -> {
            scopeTo(claim.schoolId());
            return jdbc.sql("UPDATE " + table + " SET last_error = left(:error, 500), updated_at = now(), "
                            + "dead_lettered_at = CASE WHEN :giveUp THEN now() ELSE dead_lettered_at END "
                            + "WHERE order_id = :order AND tracker_version = :sent AND attempts = :attempts")
                    .param("error", String.valueOf(reason)).param("giveUp", giveUp).param("order", claim.orderId())
                    .param("sent", claim.trackerVersion()).param("attempts", claim.attempts()).update();
        });
        if (giveUp && changed != null && changed > 0) {
            LOG.error("Tracker status delivery given up: {} {} {} {} {}", kv("event", DEAD_LETTERED_EVENT), kv("orderId", claim.orderId()),
                    kv("trackerVersion", claim.trackerVersion()), kv("attempts", claim.attempts()), kv("lastError", String.valueOf(reason)));
        } else {
            LOG.warn("Status {} of order {} not delivered on attempt {}: {}", claim.status(), claim.orderId(), claim.attempts(), reason);
        }
        return false;
    }

    private void scopeTo(long schoolId) {
        jdbc.sql("SELECT set_config('app.bypass_rls', 'off', true), set_config('app.operator_schools', '', true), "
                        + "set_config('app.current_school_id', :school, true)")
                .param("school", String.valueOf(schoolId)).query().singleRow();
    }

    private record Claim(String orderId, long schoolId, String status, long trackerVersion, int attempts) { }
}
