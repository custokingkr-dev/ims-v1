package com.custoking.ims.operationsservice.persistence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * SQL for an order's activity history (design D40): its workflow actions, stage and internal notes, call log and legacy
 * follow-ups, merged into one newest-first stream. Every source is tenant data under row-level security, so each read
 * must run inside the transaction that applied the caller's scope and cleared the order.
 *
 * <p>Rows are read in batches that always end on a whole timestamp: every row sharing the batch's oldest time is in it,
 * so a page boundary drawn at a timestamp never splits rows that one transaction wrote at the same instant.
 */
@Repository
public class ActivityRepository {
    /** The four sources, also the order rows of one timestamp are sorted in. */
    public static final String ACTION = "ACTION", NOTE = "NOTE", CALL = "CALL", FOLLOW_UP = "FOLLOW_UP";

    private final JdbcClient jdbc;
    private final String actions;
    private final String notes;
    private final String calls;
    private final String followUps;

    public ActivityRepository(JdbcClient jdbc, @Value("${workflow.db.schema:workflow}") String schema) {
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid schema name");
        this.jdbc = jdbc;
        this.actions = schema + ".workflow_actions";
        this.notes = schema + ".workflow_internal_notes";
        this.calls = schema + ".workflow_call_logs";
        this.followUps = schema + ".workflow_follow_ups";
    }

    /**
     * One row of any source. Fields a source does not have are null: {@code action} is set for actions only; {@code text}
     * holds an action's notes, a note's body or a call's notes; the call and follow-up fields are their sources' own.
     */
    public record RawEntry(String kind, long id, Integer stepOrder, Long actorId, OffsetDateTime at, String action, String text,
                           String contacted, String outcome, String contactLabel, Long vendorId, Long designerId,
                           OffsetDateTime nextFollowUpAt, Integer percentComplete, LocalDate nextFollowUpOn, String contactPhone) { }

    /**
     * The newest rows strictly older than {@code before} (all rows when null): at least {@code batchSize} when that many
     * exist, plus every further row at the oldest returned time.
     *
     * @param step           only this stage's rows (internal notes, which have no stage, are then left out); null for all
     * @param followUpStep   the stage legacy follow-ups belong to (they carry none), or null when the order has no such stage
     */
    public List<RawEntry> batch(long instanceId, OffsetDateTime before, Integer step, Integer followUpStep, int batchSize) {
        List<RawEntry> first = query(instanceId, before, null, step, followUpStep, batchSize);
        if (first.size() < batchSize) return first;
        // Complete the oldest timestamp: rows sharing it beyond the limit would otherwise fall between two pages.
        return query(instanceId, before, first.get(first.size() - 1).at(), step, followUpStep, null);
    }

    private List<RawEntry> query(long instanceId, OffsetDateTime before, OffsetDateTime notBefore, Integer step, Integer followUpStep,
                                 Integer limit) {
        String followUpStepSql = followUpStep == null ? "NULL::integer" : "CAST(:followUpStep AS integer)";
        String union = "SELECT '" + ACTION + "' AS kind, a.id, a.step_order, a.actor_id AS actor_id, a.acted_at AS at, a.action, "
                + "a.notes AS text, NULL::varchar AS contacted, NULL::varchar AS outcome, NULL::varchar AS contact_label, "
                + "NULL::bigint AS vendor_id, NULL::bigint AS designer_id, NULL::timestamptz AS next_follow_up_at, "
                + "NULL::integer AS percent_complete, NULL::date AS next_follow_up_on, NULL::varchar AS contact_phone "
                + "FROM " + actions + " a WHERE a.instance_id = :id "
                + "UNION ALL SELECT '" + NOTE + "', n.id, n.step_order, n.author_user_id, n.created_at, NULL, n.body, NULL, NULL, NULL, "
                + "NULL, NULL, NULL, NULL, NULL, NULL FROM " + notes + " n WHERE n.instance_id = :id "
                + "UNION ALL SELECT '" + CALL + "', c.id, c.step_order, c.created_by, c.created_at, NULL, c.notes, c.contacted, "
                + "c.outcome, c.contact_label, c.vendor_id, c.designer_id, c.next_follow_up_at, NULL, NULL, NULL FROM " + calls
                + " c WHERE c.instance_id = :id "
                + "UNION ALL SELECT '" + FOLLOW_UP + "', f.id, " + followUpStepSql + ", f.created_by, f.created_at, NULL, NULL, NULL, "
                + "NULL, NULL, NULL, NULL, NULL, f.percent_complete, f.next_follow_up_on, f.contact_phone FROM " + followUps
                + " f WHERE f.instance_id = :id";
        StringBuilder sql = new StringBuilder("SELECT * FROM (").append(union).append(") e WHERE true");
        if (before != null) sql.append(" AND e.at < :before");
        if (notBefore != null) sql.append(" AND e.at >= :notBefore");
        if (step != null) sql.append(" AND e.step_order = :step");
        sql.append(" ORDER BY e.at DESC, e.kind, e.id DESC");
        if (limit != null) sql.append(" LIMIT :limit");
        JdbcClient.StatementSpec statement = jdbc.sql(sql.toString()).param("id", instanceId);
        if (followUpStep != null) statement = statement.param("followUpStep", followUpStep);
        if (before != null) statement = statement.param("before", before);
        if (notBefore != null) statement = statement.param("notBefore", notBefore);
        if (step != null) statement = statement.param("step", step);
        if (limit != null) statement = statement.param("limit", limit);
        return statement.query((rs, n) -> row(rs)).list();
    }

    private static RawEntry row(ResultSet rs) throws SQLException {
        return new RawEntry(rs.getString("kind"), rs.getLong("id"), rs.getObject("step_order", Integer.class),
                rs.getObject("actor_id", Long.class), rs.getObject("at", OffsetDateTime.class), rs.getString("action"),
                rs.getString("text"), rs.getString("contacted"), rs.getString("outcome"), rs.getString("contact_label"),
                rs.getObject("vendor_id", Long.class), rs.getObject("designer_id", Long.class),
                rs.getObject("next_follow_up_at", OffsetDateTime.class), rs.getObject("percent_complete", Integer.class),
                rs.getObject("next_follow_up_on", LocalDate.class), rs.getString("contact_phone"));
    }
}
