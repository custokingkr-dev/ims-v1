package com.custoking.ims.operationsservice.persistence;

import com.custoking.ims.operationsservice.application.tracker.Sla;
import com.custoking.ims.operationsservice.security.TenantContext;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SQL for the notebook order tracker. Every method must run inside a transaction opened by the service, because the
 * row-level-security scope for operators is set transaction-locally ({@link #applyOperatorScope()}).
 */
@Repository
public class NotebookTrackerRepository {
    public static final String DEFINITION = "NOTEBOOK_ORDER_TRACKER";
    /** The catalogue item the tracker serves; item-specific operators are scoped by this code. */
    public static final String CATALOG_CATEGORY = "NOTEBOOKS";

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final String instances;
    private final String steps;
    private final String actions;
    private final String trackers;
    private final String instanceSteps;
    private final String schoolSla;
    private final String notes;
    private final String followUps;
    private final String designers;

    public NotebookTrackerRepository(JdbcClient jdbc, ObjectMapper json, @Value("${workflow.db.schema:workflow}") String schema) {
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid schema name");
        this.jdbc = jdbc;
        this.json = json;
        this.instances = schema + ".workflow_instances";
        this.steps = schema + ".workflow_steps";
        this.actions = schema + ".workflow_actions";
        this.trackers = schema + ".workflow_notebook_trackers";
        this.instanceSteps = schema + ".workflow_instance_steps";
        this.schoolSla = schema + ".workflow_school_step_sla";
        this.notes = schema + ".workflow_internal_notes";
        this.followUps = schema + ".workflow_follow_ups";
        this.designers = schema + ".workflow_designers";
    }

    // ---- rows ---------------------------------------------------------------------------------------------------

    public record TrackerRow(long instanceId, long schoolId, String orderId, boolean customised, int orderedCount,
                             OffsetDateTime placedAt, String timeZone, String schoolName, String vendorName, String vendorPhone,
                             Long designerId, String designState, String designStateNote, OffsetDateTime designStateAt,
                             Long designStateBy, Long approvedDraftRef, long version, String instanceStatus) { }

    public record StepRow(int stepOrder, String status, Long assigneeUserId, OffsetDateTime assignedAt,
                          OffsetDateTime startedAt, OffsetDateTime dueAt, OffsetDateTime completedAt, Long completedBy,
                          Map<String, Object> data, OffsetDateTime dataSavedAt, String stageNote, long version) { }

    public record DefinitionStep(int stepOrder, String name, boolean customisedOnly, Sla defaultSla) { }

    public record SchoolSla(int stepOrder, Sla sla) { }

    public record NoteRow(long id, long authorUserId, String body, OffsetDateTime createdAt) { }

    public record FollowUpRow(long id, int percentComplete, LocalDate nextFollowUpOn, String contactPhone,
                              long createdBy, OffsetDateTime createdAt) { }

    public record DesignerRow(long id, String name, String phone, boolean active) { }

    public record ActionRow(long id, int stepOrder, String action, Long actorId, String notes, OffsetDateTime actedAt) { }

    // ---- request scope ------------------------------------------------------------------------------------------

    /** Operators have no home school: admit exactly their assigned schools for this transaction (empty = none). */
    public void applyOperatorScope() {
        TenantContext ctx = TenantContext.get();
        if (!ctx.isOperations()) return;
        String csv = ctx.operatorSchoolsFor(CATALOG_CATEGORY).stream().map(String::valueOf).collect(Collectors.joining(","));
        jdbc.sql("SELECT set_config('app.operator_schools', :csv, true)").param("csv", csv).query(String.class).single();
    }

    /** For a machine read of one order's tracker by its id; there is no user, so the read is explicitly privileged. */
    public void applyBypassScope() {
        jdbc.sql("SELECT set_config('app.bypass_rls', 'on', true)").query(String.class).single();
    }

    /** For the machine call that creates a tracker at placement: scope this transaction to the order's school. */
    public void applySchoolScope(long schoolId) {
        jdbc.sql("SELECT set_config('app.current_school_id', :school, true)").param("school", String.valueOf(schoolId))
                .query(String.class).single();
    }

    // ---- definition and time slots ------------------------------------------------------------------------------

    public List<DefinitionStep> definitionSteps() {
        return jdbc.sql("SELECT step_order, step_name, customised_only, default_sla_value, default_sla_unit FROM " + steps
                        + " WHERE definition_id = :definition ORDER BY step_order")
                .param("definition", DEFINITION)
                .query((rs, n) -> new DefinitionStep(rs.getInt("step_order"), rs.getString("step_name"),
                        rs.getBoolean("customised_only"), sla(rs.getObject("default_sla_value", Integer.class), rs.getString("default_sla_unit"))))
                .list();
    }

    /** Per-school overrides; a present row with no slot means "no timer for this school and step". */
    public List<SchoolSla> schoolSla(long schoolId) {
        return jdbc.sql("SELECT step_order, sla_value, sla_unit FROM " + schoolSla + " WHERE school_id = :school AND definition_id = :definition")
                .param("school", schoolId).param("definition", DEFINITION)
                .query((rs, n) -> new SchoolSla(rs.getInt("step_order"), sla(rs.getObject("sla_value", Integer.class), rs.getString("sla_unit"))))
                .list();
    }

    public void upsertSchoolSla(long schoolId, int stepOrder, Sla sla, long userId) {
        jdbc.sql("INSERT INTO " + schoolSla + " (school_id, definition_id, step_order, sla_value, sla_unit, updated_by, updated_at) "
                        + "VALUES (:school, :definition, :step, :value, :unit, :user, now()) "
                        + "ON CONFLICT (school_id, definition_id, step_order) DO UPDATE SET sla_value = EXCLUDED.sla_value, "
                        + "sla_unit = EXCLUDED.sla_unit, updated_by = EXCLUDED.updated_by, updated_at = now()")
                .param("school", schoolId).param("definition", DEFINITION).param("step", stepOrder)
                .param("value", sla == null ? null : (int) sla.value()).param("unit", sla == null ? null : sla.unit().name())
                .param("user", userId).update();
    }

    public int deleteSchoolSla(long schoolId, int stepOrder) {
        return jdbc.sql("DELETE FROM " + schoolSla + " WHERE school_id = :school AND definition_id = :definition AND step_order = :step")
                .param("school", schoolId).param("definition", DEFINITION).param("step", stepOrder).update();
    }

    // ---- tracker --------------------------------------------------------------------------------------------------

    public Optional<TrackerRow> findByOrder(String orderId, boolean lock) {
        return jdbc.sql(trackerSelect() + " WHERE t.order_id = :order" + (lock ? " FOR UPDATE OF t" : ""))
                .param("order", orderId).query((rs, n) -> trackerRow(rs)).optional();
    }

    private String trackerSelect() {
        return "SELECT t.instance_id, t.school_id, t.order_id, t.customised, t.ordered_count, t.placed_at, t.time_zone, t.school_name, "
                + "t.vendor_name, t.vendor_phone, t.designer_id, t.design_state, t.design_state_note, t.design_state_at, "
                + "t.design_state_by, t.approved_draft_ref, t.version, i.status AS instance_status "
                + "FROM " + trackers + " t JOIN " + instances + " i ON i.id = t.instance_id AND i.school_id = t.school_id";
    }

    private TrackerRow trackerRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TrackerRow(rs.getLong("instance_id"), rs.getLong("school_id"), rs.getString("order_id"),
                rs.getBoolean("customised"), rs.getInt("ordered_count"), rs.getObject("placed_at", OffsetDateTime.class),
                rs.getString("time_zone"), rs.getString("school_name"), rs.getString("vendor_name"), rs.getString("vendor_phone"),
                rs.getObject("designer_id", Long.class), rs.getString("design_state"), rs.getString("design_state_note"),
                rs.getObject("design_state_at", OffsetDateTime.class), rs.getObject("design_state_by", Long.class),
                rs.getObject("approved_draft_ref", Long.class), rs.getLong("version"), rs.getString("instance_status"));
    }

    public long insertInstance(long schoolId, String orderId, int currentStep, Long initiatedBy) {
        return jdbc.sql("INSERT INTO " + instances + " (definition_id, entity_type, entity_id, school_id, current_step, status, initiated_by) "
                        + "VALUES (:definition, :entityType, :order, :school, :current, 'IN_PROGRESS', :by) RETURNING id")
                .param("definition", DEFINITION).param("entityType", WorkflowReadRepository.NOTEBOOK_TRACKER_ENTITY)
                .param("order", orderId).param("school", schoolId).param("current", currentStep).param("by", initiatedBy)
                .query(Long.class).single();
    }

    public void insertTracker(long instanceId, long schoolId, String orderId, boolean customised, int orderedCount,
                              OffsetDateTime placedAt, String timeZone, String schoolName) {
        jdbc.sql("INSERT INTO " + trackers + " (instance_id, school_id, order_id, customised, ordered_count, placed_at, time_zone, school_name) "
                        + "VALUES (:instance, :school, :order, :customised, :count, :placed, :zone, :name)")
                .param("instance", instanceId).param("school", schoolId).param("order", orderId)
                .param("customised", customised).param("count", orderedCount).param("placed", placedAt).param("zone", timeZone)
                .param("name", schoolName).update();
    }

    public void insertStep(long instanceId, long schoolId, int stepOrder, String status, Long assignee,
                           OffsetDateTime startedAt, OffsetDateTime dueAt) {
        jdbc.sql("INSERT INTO " + instanceSteps + " (instance_id, school_id, step_order, status, assignee_user_id, assigned_at, started_at, due_at) "
                        + "VALUES (:instance, :school, :step, :status, :assignee, CASE WHEN :assignee::bigint IS NULL THEN NULL ELSE now() END, :started, :due)")
                .param("instance", instanceId).param("school", schoolId).param("step", stepOrder).param("status", status)
                .param("assignee", assignee).param("started", startedAt).param("due", dueAt).update();
    }

    public void setInstanceProgress(long instanceId, int currentStep, String status, boolean finished) {
        jdbc.sql("UPDATE " + instances + " SET current_step = :current, status = :status, "
                        + "completed_at = CASE WHEN :finished THEN now() ELSE NULL END, version = version + 1 WHERE id = :id")
                .param("id", instanceId).param("current", currentStep).param("status", status).param("finished", finished).update();
    }

    /** The instance's version: every step change increments it, so it orders the order's status deliveries. */
    public long instanceVersion(long instanceId) {
        return jdbc.sql("SELECT version FROM " + instances + " WHERE id = :id").param("id", instanceId).query(Long.class).single();
    }

    public void setVendor(long instanceId, String name, String phone) {
        jdbc.sql("UPDATE " + trackers + " SET vendor_name = :name, vendor_phone = :phone, version = version + 1 WHERE instance_id = :id")
                .param("id", instanceId).param("name", name).param("phone", phone).update();
    }

    public void setDesigner(long instanceId, Long designerId) {
        jdbc.sql("UPDATE " + trackers + " SET designer_id = :designer, version = version + 1 WHERE instance_id = :id")
                .param("id", instanceId).param("designer", designerId).update();
    }

    public void setDesignState(long instanceId, String state, String note, long userId, Long approvedDraftRef) {
        jdbc.sql("UPDATE " + trackers + " SET design_state = :state, design_state_note = :note, design_state_at = now(), "
                        + "design_state_by = :user, approved_draft_ref = :ref, version = version + 1 WHERE instance_id = :id")
                .param("id", instanceId).param("state", state).param("note", note).param("user", userId).param("ref", approvedDraftRef).update();
    }

    /** One row of the worklist: an order's progress and its active step. */
    public record ListRow(String orderId, long schoolId, String schoolName, boolean customised, int orderedCount,
                          OffsetDateTime placedAt, String instanceStatus, Integer activeStep, Long assigneeUserId,
                          OffsetDateTime dueAt, int completed, int total) { }

    /**
     * Orders in the caller's row-level scope, those with the soonest active-step deadline first. {@code schools}, when not
     * null, further limits the list to those schools; {@code assignee} to orders whose active step is assigned to that user.
     */
    public List<ListRow> list(Long schoolId, java.util.Collection<Long> schools, Long assignee, boolean includeFinished, int limit) {
        if (schools != null && schools.isEmpty()) return List.of();
        StringBuilder sql = new StringBuilder("SELECT t.order_id, t.school_id, t.school_name, t.customised, t.ordered_count, t.placed_at, "
                + "i.status AS instance_status, s.step_order AS active_step, s.assignee_user_id, s.due_at, "
                + "(SELECT count(*) FROM " + instanceSteps + " c WHERE c.instance_id = t.instance_id AND c.status = 'COMPLETED') AS completed, "
                + "(SELECT count(*) FROM " + instanceSteps + " c WHERE c.instance_id = t.instance_id) AS total "
                + "FROM " + trackers + " t JOIN " + instances + " i ON i.id = t.instance_id AND i.school_id = t.school_id "
                + "LEFT JOIN " + instanceSteps + " s ON s.instance_id = t.instance_id AND s.status = 'ACTIVE' WHERE 1 = 1");
        if (!includeFinished) sql.append(" AND i.status <> 'COMPLETED'");
        if (schoolId != null) sql.append(" AND t.school_id = :school");
        if (schools != null) sql.append(" AND t.school_id IN (:schools)");
        if (assignee != null) sql.append(" AND s.assignee_user_id = :assignee");
        sql.append(" ORDER BY s.due_at NULLS LAST, t.placed_at DESC, t.order_id LIMIT :limit");
        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 500)));
        if (schoolId != null) spec = spec.param("school", schoolId);
        if (schools != null) spec = spec.param("schools", List.copyOf(schools));
        if (assignee != null) spec = spec.param("assignee", assignee);
        return spec.query((rs, n) -> new ListRow(rs.getString("order_id"), rs.getLong("school_id"), rs.getString("school_name"),
                rs.getBoolean("customised"), rs.getInt("ordered_count"), rs.getObject("placed_at", OffsetDateTime.class),
                rs.getString("instance_status"), rs.getObject("active_step", Integer.class), rs.getObject("assignee_user_id", Long.class),
                rs.getObject("due_at", OffsetDateTime.class), rs.getInt("completed"), rs.getInt("total"))).list();
    }

    // ---- steps ------------------------------------------------------------------------------------------------------

    public List<StepRow> steps(long instanceId) {
        return jdbc.sql(stepSelect() + " WHERE instance_id = :id ORDER BY step_order").param("id", instanceId)
                .query((rs, n) -> stepRow(rs)).list();
    }

    public Optional<StepRow> step(long instanceId, int stepOrder) {
        return jdbc.sql(stepSelect() + " WHERE instance_id = :id AND step_order = :step")
                .param("id", instanceId).param("step", stepOrder).query((rs, n) -> stepRow(rs)).optional();
    }

    private String stepSelect() {
        return "SELECT step_order, status, assignee_user_id, assigned_at, started_at, due_at, completed_at, completed_by, "
                + "data::text AS data, data_saved_at, stage_note, version FROM " + instanceSteps;
    }

    private StepRow stepRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StepRow(rs.getInt("step_order"), rs.getString("status"), rs.getObject("assignee_user_id", Long.class),
                rs.getObject("assigned_at", OffsetDateTime.class), rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("due_at", OffsetDateTime.class), rs.getObject("completed_at", OffsetDateTime.class),
                rs.getObject("completed_by", Long.class), readJson(rs.getString("data")),
                rs.getObject("data_saved_at", OffsetDateTime.class), rs.getString("stage_note"), rs.getLong("version"));
    }

    /** Replaces the saved work-area data; returns 0 when the step changed since the caller read it. */
    public int saveStepData(long instanceId, int stepOrder, Map<String, Object> data, long userId, long expectedVersion) {
        return jdbc.sql("UPDATE " + instanceSteps + " SET data = CAST(:data AS jsonb), data_saved_at = now(), data_saved_by = :user, version = version + 1 "
                        + "WHERE instance_id = :id AND step_order = :step AND version = :version")
                .param("id", instanceId).param("step", stepOrder).param("data", writeJson(data)).param("user", userId)
                .param("version", expectedVersion).update();
    }

    /** Server-side change to a step's saved data (sign-off); it does not require the client's version. */
    public void mergeStepData(long instanceId, int stepOrder, Map<String, Object> additions, long userId) {
        jdbc.sql("UPDATE " + instanceSteps + " SET data = data || CAST(:data AS jsonb), data_saved_at = now(), data_saved_by = :user, version = version + 1 "
                        + "WHERE instance_id = :id AND step_order = :step")
                .param("id", instanceId).param("step", stepOrder).param("data", writeJson(additions)).param("user", userId).update();
    }

    public int saveStageNote(long instanceId, int stepOrder, String note, long userId, long expectedVersion) {
        return jdbc.sql("UPDATE " + instanceSteps + " SET stage_note = :note, stage_note_saved_at = now(), stage_note_saved_by = :user, version = version + 1 "
                        + "WHERE instance_id = :id AND step_order = :step AND version = :version")
                .param("id", instanceId).param("step", stepOrder).param("note", note).param("user", userId)
                .param("version", expectedVersion).update();
    }

    public int completeStep(long instanceId, int stepOrder, long userId, long expectedVersion) {
        return jdbc.sql("UPDATE " + instanceSteps + " SET status = 'COMPLETED', completed_at = now(), completed_by = :user, version = version + 1 "
                        + "WHERE instance_id = :id AND step_order = :step AND status = 'ACTIVE' AND version = :version")
                .param("id", instanceId).param("step", stepOrder).param("user", userId).param("version", expectedVersion).update();
    }

    /** Makes a step active and (re)starts its clock; used for the next step and for a step superadmin reopens. */
    public void activateStep(long instanceId, int stepOrder, OffsetDateTime startedAt, OffsetDateTime dueAt) {
        jdbc.sql("UPDATE " + instanceSteps + " SET status = 'ACTIVE', started_at = :started, due_at = :due, completed_at = NULL, completed_by = NULL, version = version + 1 "
                        + "WHERE instance_id = :id AND step_order = :step")
                .param("id", instanceId).param("step", stepOrder).param("started", startedAt).param("due", dueAt).update();
    }

    /** Later steps revert to not-completed when a step is reopened; their saved data and notes are kept. */
    public void lockStepsAfter(long instanceId, int stepOrder) {
        jdbc.sql("UPDATE " + instanceSteps + " SET status = 'LOCKED', started_at = NULL, due_at = NULL, completed_at = NULL, completed_by = NULL, version = version + 1 "
                        + "WHERE instance_id = :id AND step_order > :step AND status <> 'LOCKED'")
                .param("id", instanceId).param("step", stepOrder).update();
    }

    public void assignStep(long instanceId, int stepOrder, Long userId, long assignedBy) {
        jdbc.sql("UPDATE " + instanceSteps + " SET assignee_user_id = :user, assigned_by = :by, assigned_at = now(), version = version + 1 "
                        + "WHERE instance_id = :id AND step_order = :step")
                .param("id", instanceId).param("step", stepOrder).param("user", userId).param("by", assignedBy).update();
    }

    // ---- notes, follow-ups, designers, history --------------------------------------------------------------------

    public List<NoteRow> internalNotes(long instanceId) {
        return jdbc.sql("SELECT id, author_user_id, body, created_at FROM " + notes + " WHERE instance_id = :id ORDER BY created_at, id")
                .param("id", instanceId)
                .query((rs, n) -> new NoteRow(rs.getLong("id"), rs.getLong("author_user_id"), rs.getString("body"),
                        rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    public long addInternalNote(long instanceId, long schoolId, long authorUserId, String body) {
        return jdbc.sql("INSERT INTO " + notes + " (instance_id, school_id, author_user_id, body) VALUES (:id, :school, :author, :body) RETURNING id")
                .param("id", instanceId).param("school", schoolId).param("author", authorUserId).param("body", body)
                .query(Long.class).single();
    }

    public List<FollowUpRow> followUps(long instanceId) {
        return jdbc.sql("SELECT id, percent_complete, next_follow_up_on, contact_phone, created_by, created_at FROM " + followUps
                        + " WHERE instance_id = :id ORDER BY created_at, id")
                .param("id", instanceId)
                .query((rs, n) -> new FollowUpRow(rs.getLong("id"), rs.getInt("percent_complete"),
                        rs.getObject("next_follow_up_on", LocalDate.class), rs.getString("contact_phone"),
                        rs.getLong("created_by"), rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    public long addFollowUp(long instanceId, long schoolId, int percent, LocalDate nextOn, String phone, long userId) {
        return jdbc.sql("INSERT INTO " + followUps + " (instance_id, school_id, percent_complete, next_follow_up_on, contact_phone, created_by) "
                        + "VALUES (:id, :school, :percent, :next, :phone, :user) RETURNING id")
                .param("id", instanceId).param("school", schoolId).param("percent", percent).param("next", nextOn)
                .param("phone", phone).param("user", userId).query(Long.class).single();
    }

    public List<DesignerRow> designers(boolean activeOnly) {
        return jdbc.sql("SELECT id, name, phone, active FROM " + designers + (activeOnly ? " WHERE active" : "") + " ORDER BY lower(name), id")
                .query((rs, n) -> new DesignerRow(rs.getLong("id"), rs.getString("name"), rs.getString("phone"), rs.getBoolean("active")))
                .list();
    }

    public Optional<DesignerRow> designer(long id) {
        return jdbc.sql("SELECT id, name, phone, active FROM " + designers + " WHERE id = :id").param("id", id)
                .query((rs, n) -> new DesignerRow(rs.getLong("id"), rs.getString("name"), rs.getString("phone"), rs.getBoolean("active")))
                .optional();
    }

    public long insertDesigner(String name, String phone, long userId) {
        return jdbc.sql("INSERT INTO " + designers + " (name, phone, created_by, updated_by) VALUES (:name, :phone, :user, :user) RETURNING id")
                .param("name", name).param("phone", phone).param("user", userId).query(Long.class).single();
    }

    public int updateDesigner(long id, String name, String phone, boolean active, long userId) {
        return jdbc.sql("UPDATE " + designers + " SET name = :name, phone = :phone, active = :active, updated_by = :user, updated_at = now() WHERE id = :id")
                .param("id", id).param("name", name).param("phone", phone).param("active", active).param("user", userId).update();
    }

    public List<ActionRow> history(long instanceId) {
        return jdbc.sql("SELECT id, step_order, action, actor_id, notes, acted_at FROM " + actions + " WHERE instance_id = :id ORDER BY acted_at, id")
                .param("id", instanceId)
                .query((rs, n) -> new ActionRow(rs.getLong("id"), rs.getInt("step_order"), rs.getString("action"),
                        rs.getObject("actor_id", Long.class), rs.getString("notes"), rs.getObject("acted_at", OffsetDateTime.class)))
                .list();
    }

    public void recordAction(long instanceId, long schoolId, int stepOrder, String action, String notes) {
        TenantContext actor = TenantContext.get();
        jdbc.sql("INSERT INTO " + actions + " (instance_id, step_order, action, actor_id, actor_email, notes, school_id, request_correlation_id, authority) "
                        + "VALUES (:id, :step, :action, :actorId, :email, :notes, :school, :request, 'SERVER')")
                .param("id", instanceId).param("step", stepOrder).param("action", action).param("actorId", actor.userId())
                .param("email", actor.email()).param("notes", notes).param("school", schoolId).param("request", correlationId())
                .update();
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static Sla sla(Integer value, String unit) {
        return value == null || unit == null ? null : new Sla(value, Sla.Unit.valueOf(unit));
    }

    private static String correlationId() {
        String value = MDC.get("requestId");
        return value != null && value.matches("[A-Za-z0-9._:-]{1,128}") ? value : UUID.randomUUID().toString();
    }

    private String writeJson(Map<String, Object> value) { return json.writeValueAsString(value == null ? Map.of() : value); }

    private Map<String, Object> readJson(String text) {
        if (text == null || text.isBlank()) return new LinkedHashMap<>();
        return json.readValue(text, MAP);
    }
}
