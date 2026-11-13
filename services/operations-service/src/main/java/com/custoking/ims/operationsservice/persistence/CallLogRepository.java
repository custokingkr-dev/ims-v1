package com.custoking.ims.operationsservice.persistence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * SQL for an order's call log (design D35, workflow V20). Entries are append-only: there is no update or delete here, and
 * the runtime role holds only SELECT and INSERT on the table. The table is tenant data under row-level security, so every
 * method must run inside the transaction that applied the caller's scope and cleared the order.
 */
@Repository
public class CallLogRepository {
    /** The outcome that asks for a callback (an open callback until the entity is called again). */
    public static final String CALLBACK_REQUESTED = "CALLBACK_REQUESTED";

    private final JdbcClient jdbc;
    private final String calls;
    private final String instanceSteps;
    private final String designers;

    public CallLogRepository(JdbcClient jdbc, @Value("${workflow.db.schema:workflow}") String schema) {
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid schema name");
        this.jdbc = jdbc;
        this.calls = schema + ".workflow_call_logs";
        this.instanceSteps = schema + ".workflow_instance_steps";
        this.designers = schema + ".workflow_designers";
    }

    /** A stored call. */
    public record CallRow(long id, Integer stepOrder, String contacted, String outcome, String contactLabel, Long vendorId,
                          Long designerId, String notes, OffsetDateTime nextFollowUpAt, long createdBy, OffsetDateTime createdAt) { }

    /** A call to add; validated by the service. */
    public record NewCallRow(long instanceId, long schoolId, Integer stepOrder, String contacted, String outcome,
                             String contactLabel, Long vendorId, Long designerId, String notes, OffsetDateTime nextFollowUpAt,
                             long createdBy) { }

    public long insert(NewCallRow call) {
        return jdbc.sql("INSERT INTO " + calls + " (instance_id, school_id, step_order, contacted, outcome, contact_label, vendor_id, "
                        + "designer_id, notes, next_follow_up_at, created_by) VALUES (:instance, :school, :step, :contacted, :outcome, "
                        + ":label, :vendor, :designer, :notes, :followUp, :user) RETURNING id")
                .param("instance", call.instanceId()).param("school", call.schoolId()).param("step", call.stepOrder())
                .param("contacted", call.contacted()).param("outcome", call.outcome()).param("label", call.contactLabel())
                .param("vendor", call.vendorId()).param("designer", call.designerId()).param("notes", call.notes())
                .param("followUp", call.nextFollowUpAt()).param("user", call.createdBy())
                .query(Long.class).single();
    }

    public Optional<CallRow> call(long id) {
        return jdbc.sql(SELECT + " FROM " + calls + " WHERE id = :id").param("id", id).query((rs, n) -> row(rs)).optional();
    }

    /** The order's calls, newest first. */
    public List<CallRow> calls(long instanceId, int limit) {
        return jdbc.sql(SELECT + " FROM " + calls + " WHERE instance_id = :id ORDER BY created_at DESC, id DESC LIMIT :limit")
                .param("id", instanceId).param("limit", limit).query((rs, n) -> row(rs)).list();
    }

    /**
     * The open callbacks (design D39): per contacted entity, its latest call, when that call asked for a callback. A later
     * call to the same entity, whatever its outcome, closes the callback. Earliest follow-up first.
     */
    public List<CallRow> openCallbacks(long instanceId) {
        return jdbc.sql("SELECT * FROM (SELECT DISTINCT ON (contacted) " + COLUMNS + " FROM " + calls
                        + " WHERE instance_id = :id ORDER BY contacted, created_at DESC, id DESC) latest "
                        + "WHERE outcome = '" + CALLBACK_REQUESTED + "' ORDER BY next_follow_up_at, id")
                .param("id", instanceId).query((rs, n) -> row(rs)).list();
    }

    /** Whether the order has this stage (a wholesale notebook order has no Steps 2-4). */
    public boolean hasStep(long instanceId, int stepOrder) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM " + instanceSteps + " WHERE instance_id = :id AND step_order = :step)")
                .param("id", instanceId).param("step", stepOrder).query(Boolean.class).single();
    }

    /** A designer's name by id, active or not (the directory is global). */
    public Optional<String> designerName(long designerId) {
        return jdbc.sql("SELECT name FROM " + designers + " WHERE id = :id").param("id", designerId).query(String.class).optional();
    }

    private static final String COLUMNS = "id, step_order, contacted, outcome, contact_label, vendor_id, designer_id, notes, "
            + "next_follow_up_at, created_by, created_at";
    private static final String SELECT = "SELECT " + COLUMNS;

    private static CallRow row(ResultSet rs) throws SQLException {
        return new CallRow(rs.getLong("id"), rs.getObject("step_order", Integer.class), rs.getString("contacted"),
                rs.getString("outcome"), rs.getString("contact_label"), rs.getObject("vendor_id", Long.class),
                rs.getObject("designer_id", Long.class), rs.getString("notes"),
                rs.getObject("next_follow_up_at", OffsetDateTime.class), rs.getLong("created_by"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
