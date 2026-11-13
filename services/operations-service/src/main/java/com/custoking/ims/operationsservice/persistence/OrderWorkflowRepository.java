package com.custoking.ims.operationsservice.persistence;

import com.custoking.ims.operationsservice.application.orderworkflow.rules.Sla;
import com.custoking.ims.operationsservice.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SQL for the item order workflows (design §3.1): one {@code workflow_notebook_trackers} row per order (the table keeps
 * its name, D6), its {@code workflow_instance_steps}, notes, designers and per-school time slots. Every method must run
 * inside a transaction opened by the service, because the row-level-security scope is set transaction-locally
 * ({@link #applyOperatorScope()}).
 */
@Repository
public class OrderWorkflowRepository {
    private static final Logger LOG = LoggerFactory.getLogger(OrderWorkflowRepository.class);
    /** Entity type of every workflow created by this engine (D6); notebook trackers created before it keep NOTEBOOK_ORDER. */
    public static final String ENTITY_TYPE = WorkflowReadRepository.ITEM_ORDER_ENTITY;

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
    private final String designers;
    private final String groupLinks;

    public OrderWorkflowRepository(JdbcClient jdbc, ObjectMapper json, @Value("${workflow.db.schema:workflow}") String schema) {
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
        this.designers = schema + ".workflow_designers";
        this.groupLinks = schema + ".workflow_school_whatsapp_group_links";
    }

    // ---- rows ---------------------------------------------------------------------------------------------------

    /** One workflow order: the tracker row joined with its instance. */
    public record TrackerRow(long instanceId, long schoolId, String orderId, boolean customised, int orderedCount,
                             OffsetDateTime placedAt, String timeZone, String schoolName, String vendorName, String vendorPhone,
                             Map<String, List<Long>> vendorSelections, Long designerId, String designState, String designStateNote,
                             OffsetDateTime designStateAt, Long designStateBy, Long approvedDraftRef, long version,
                             String instanceStatus, String definitionId, String categoryCode, String itemCode, String itemNoun,
                             String orderNumber, String schoolContactName, String schoolContactPhone, String approvedCdrLink,
                             String designComment, String importedFrom) { }

    public record StepRow(int stepOrder, String status, Long assigneeUserId, OffsetDateTime assignedAt,
                          OffsetDateTime startedAt, OffsetDateTime dueAt, OffsetDateTime completedAt, Long completedBy,
                          Map<String, Object> data, OffsetDateTime dataSavedAt, Long dataSavedBy, long version) { }

    /** A template step as {@code workflow_steps} stores it; the default time slot is the one superadmin last set. */
    public record DefinitionStep(int stepOrder, String code, String name, Optional<Sla> defaultSla) { }

    /** A per-school override; an empty slot means "no timer for this school and step". */
    public record SchoolSla(int stepOrder, Optional<Sla> sla) { }

    /** A stage note ({@code stepOrder} set) or an order-level internal note. */
    public record NoteRow(long id, Integer stepOrder, long authorUserId, String body, OffsetDateTime createdAt) { }

    /** A designer of the global directory. */
    public record DesignerRow(long id, String name, String phone, boolean active) { }

    public record WhatsAppGroupLink(String targetKey, String inviteUrl) { }

    /** What a new workflow order stores (design §3.5 machine create). */
    public record NewTracker(long instanceId, long schoolId, String orderId, boolean customised, int orderedCount,
                             OffsetDateTime placedAt, String timeZone, String schoolName, String definitionId, String categoryCode,
                             String itemCode, String itemNoun, String orderNumber, String importedFrom) { }

    // ---- request scope ------------------------------------------------------------------------------------------

    /**
     * Operators have no home school: admit, for this transaction, every school where they may work some item (D42); reads
     * then also filter each row on its (school, category) pair. Empty admits none. Nothing changes for anyone else.
     */
    public void applyOperatorScope() {
        TenantContext ctx = TenantContext.get();
        if (!ctx.isOperations()) return;
        String csv = ctx.operatorSchoolsForAnyItem().stream().map(String::valueOf).collect(Collectors.joining(","));
        jdbc.sql("SELECT set_config('app.operator_schools', :csv, true)").param("csv", csv).query(String.class).single();
    }

    /** For a machine read of one order by its id; there is no user, so the read is explicitly privileged. */
    public void applyBypassScope() {
        jdbc.sql("SELECT set_config('app.bypass_rls', 'on', true)").query(String.class).single();
    }

    /** For the machine call that creates a workflow at placement: scope this transaction to the order's school. */
    public void applySchoolScope(long schoolId) {
        jdbc.sql("SELECT set_config('app.current_school_id', :school, true)").param("school", String.valueOf(schoolId))
                .query(String.class).single();
    }

    // ---- definitions and time slots -----------------------------------------------------------------------------------

    public List<DefinitionStep> definitionSteps(String definitionId) {
        return jdbc.sql("SELECT step_order, step_code, step_name, default_sla_value, default_sla_unit FROM " + steps
                        + " WHERE definition_id = :definition ORDER BY step_order")
                .param("definition", definitionId)
                .query((rs, n) -> new DefinitionStep(rs.getInt("step_order"), rs.getString("step_code"), rs.getString("step_name"),
                        Sla.ofNullable(rs.getObject("default_sla_value", Integer.class), rs.getString("default_sla_unit"))))
                .list();
    }

    /**
     * Superadmin's new default for one template step (D23); it applies to stages that start afterwards. A null slot means
     * "no timer" by default.
     */
    public int updateDefaultSla(String definitionId, int stepOrder, Sla sla, long userId) {
        return jdbc.sql("UPDATE " + steps + " SET default_sla_value = CAST(:value AS integer), default_sla_unit = CAST(:unit AS varchar), "
                        + "updated_by = :user, updated_at = now() WHERE definition_id = :definition AND step_order = :step")
                .param("value", sla == null ? null : (int) sla.value()).param("unit", sla == null ? null : sla.unit().name())
                .param("user", userId).param("definition", definitionId).param("step", stepOrder).update();
    }

    public List<SchoolSla> schoolSla(long schoolId, String definitionId) {
        return jdbc.sql("SELECT step_order, sla_value, sla_unit FROM " + schoolSla + " WHERE school_id = :school AND definition_id = :definition")
                .param("school", schoolId).param("definition", definitionId)
                .query((rs, n) -> new SchoolSla(rs.getInt("step_order"),
                        Sla.ofNullable(rs.getObject("sla_value", Integer.class), rs.getString("sla_unit"))))
                .list();
    }

    public void upsertSchoolSla(long schoolId, String definitionId, int stepOrder, Sla sla, long userId) {
        jdbc.sql("INSERT INTO " + schoolSla + " (school_id, definition_id, step_order, sla_value, sla_unit, updated_by, updated_at) "
                        + "VALUES (:school, :definition, :step, :value, :unit, :user, now()) "
                        + "ON CONFLICT (school_id, definition_id, step_order) DO UPDATE SET sla_value = EXCLUDED.sla_value, "
                        + "sla_unit = EXCLUDED.sla_unit, updated_by = EXCLUDED.updated_by, updated_at = now()")
                .param("school", schoolId).param("definition", definitionId).param("step", stepOrder)
                .param("value", sla == null ? null : (int) sla.value()).param("unit", sla == null ? null : sla.unit().name())
                .param("user", userId).update();
    }

    public int deleteSchoolSla(long schoolId, String definitionId, int stepOrder) {
        return jdbc.sql("DELETE FROM " + schoolSla + " WHERE school_id = :school AND definition_id = :definition AND step_order = :step")
                .param("school", schoolId).param("definition", definitionId).param("step", stepOrder).update();
    }

    // ---- a school's own WhatsApp groups (V15) ---------------------------------------------------------------------------

    public Map<String, String> whatsappGroupLinks(long schoolId) {
        return jdbc.sql("SELECT target_key, invite_url FROM " + groupLinks + " WHERE school_id = :school ORDER BY target_key")
                .param("school", schoolId)
                .query((rs, n) -> new WhatsAppGroupLink(rs.getString("target_key"), rs.getString("invite_url")))
                .list().stream().collect(Collectors.toMap(WhatsAppGroupLink::targetKey, WhatsAppGroupLink::inviteUrl,
                        (first, ignored) -> first, LinkedHashMap::new));
    }

    public void upsertWhatsAppGroupLink(long schoolId, String targetKey, String inviteUrl, long userId) {
        jdbc.sql("INSERT INTO " + groupLinks + " (school_id, target_key, invite_url, updated_by, updated_at) "
                        + "VALUES (:school, :target, :url, :user, now()) "
                        + "ON CONFLICT (school_id, target_key) DO UPDATE SET invite_url = EXCLUDED.invite_url, "
                        + "updated_by = EXCLUDED.updated_by, updated_at = now()")
                .param("school", schoolId).param("target", targetKey).param("url", inviteUrl).param("user", userId).update();
    }

    public int deleteWhatsAppGroupLink(long schoolId, String targetKey) {
        return jdbc.sql("DELETE FROM " + groupLinks + " WHERE school_id = :school AND target_key = :target")
                .param("school", schoolId).param("target", targetKey).update();
    }

    // ---- the order's tracker row ------------------------------------------------------------------------------------------

    public Optional<TrackerRow> findByOrder(String orderId, boolean lock) {
        return jdbc.sql(trackerSelect() + " WHERE t.order_id = :order" + (lock ? " FOR UPDATE OF t" : ""))
                .param("order", orderId).query((rs, n) -> trackerRow(rs)).optional();
    }

    private String trackerSelect() {
        return "SELECT t.instance_id, t.school_id, t.order_id, t.customised, t.ordered_count, t.placed_at, t.time_zone, t.school_name, "
                + "t.vendor_name, t.vendor_phone, t.vendor_selections::text AS vendor_selections, t.designer_id, t.design_state, "
                + "t.design_state_note, t.design_state_at, t.design_state_by, t.approved_draft_ref, t.version, i.status AS instance_status, "
                + "t.definition_id, t.category_code, t.item_code, t.item_noun, t.order_number, t.school_contact_name, "
                + "t.school_contact_phone, t.approved_cdr_link, t.design_comment, t.imported_from "
                + "FROM " + trackers + " t JOIN " + instances + " i ON i.id = t.instance_id AND i.school_id = t.school_id";
    }

    private TrackerRow trackerRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TrackerRow(rs.getLong("instance_id"), rs.getLong("school_id"), rs.getString("order_id"),
                rs.getBoolean("customised"), rs.getInt("ordered_count"), rs.getObject("placed_at", OffsetDateTime.class),
                rs.getString("time_zone"), rs.getString("school_name"), rs.getString("vendor_name"), rs.getString("vendor_phone"),
                vendorSelections(rs.getString("order_id"), rs.getString("vendor_selections")), rs.getObject("designer_id", Long.class),
                rs.getString("design_state"), rs.getString("design_state_note"), rs.getObject("design_state_at", OffsetDateTime.class),
                rs.getObject("design_state_by", Long.class), rs.getObject("approved_draft_ref", Long.class), rs.getLong("version"),
                rs.getString("instance_status"), rs.getString("definition_id"), rs.getString("category_code"), rs.getString("item_code"),
                rs.getString("item_noun"), rs.getString("order_number"), rs.getString("school_contact_name"),
                rs.getString("school_contact_phone"), rs.getString("approved_cdr_link"), rs.getString("design_comment"),
                rs.getString("imported_from"));
    }

    /**
     * {@code {"BOARD_SUPPLIERS": [3, 7]}}, as V19 rewrote every selection and V21's check keeps it; each category's ids in
     * the order stored. Anything else found there (an id that is not a whole number, such as a pre-V19
     * {@code "board_shree"}, or a category that is not a list) selects nothing and is logged, never thrown: every call on
     * the order reads this row, so one unreadable selection must not make the whole order answer 500. The next selection
     * saved replaces the column whole.
     */
    Map<String, List<Long>> vendorSelections(String orderId, String jsonText) {
        if (jsonText == null || jsonText.isBlank()) return Map.of();
        Map<String, Object> stored;
        try {
            stored = json.readValue(jsonText, MAP);
        } catch (tools.jackson.core.JacksonException notAnObject) {
            LOG.warn("Vendor selections of order {} are not a JSON object and select nothing", orderId);
            return Map.of();
        }
        if (stored == null) return Map.of();
        Map<String, List<Long>> copy = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        stored.forEach((category, ids) -> {
            List<Long> kept = new ArrayList<>();
            if (ids instanceof List<?> list) {
                for (Object id : list) {
                    if (id instanceof Integer || id instanceof Long || id instanceof Short) kept.add(((Number) id).longValue());
                    else skipped.add(category + "=" + id);
                }
            } else if (ids != null) {
                skipped.add(category + "=" + ids);
            }
            copy.put(category, List.copyOf(kept));
        });
        if (!skipped.isEmpty()) LOG.warn("Vendor selections of order {} hold entries that are not vendor ids, skipped: {}", orderId, skipped);
        return java.util.Collections.unmodifiableMap(copy);
    }

    public long insertInstance(long schoolId, String orderId, String definitionId, int currentStep, String status, Long initiatedBy) {
        return jdbc.sql("INSERT INTO " + instances + " (definition_id, entity_type, entity_id, school_id, current_step, status, initiated_by, "
                        + "completed_at) VALUES (:definition, :entityType, :order, :school, :current, :status, :by, "
                        + "CASE WHEN :status = 'COMPLETED' THEN now() END) RETURNING id")
                .param("definition", definitionId).param("entityType", ENTITY_TYPE).param("order", orderId).param("school", schoolId)
                .param("current", currentStep).param("status", status).param("by", initiatedBy)
                .query(Long.class).single();
    }

    public void insertTracker(NewTracker t) {
        jdbc.sql("INSERT INTO " + trackers + " (instance_id, school_id, order_id, customised, ordered_count, placed_at, time_zone, "
                        + "school_name, definition_id, category_code, item_code, item_noun, order_number, imported_from) "
                        + "VALUES (:instance, :school, :order, :customised, :count, :placed, :zone, :name, :definition, :category, "
                        + ":item, :noun, :number, :imported)")
                .param("instance", t.instanceId()).param("school", t.schoolId()).param("order", t.orderId())
                .param("customised", t.customised()).param("count", t.orderedCount()).param("placed", t.placedAt())
                .param("zone", t.timeZone()).param("name", t.schoolName()).param("definition", t.definitionId())
                .param("category", t.categoryCode()).param("item", t.itemCode()).param("noun", t.itemNoun())
                .param("number", t.orderNumber()).param("imported", t.importedFrom()).update();
    }

    /** Fills the order number school-core minted later (D13); a number already set never changes. */
    public int fillOrderNumber(long instanceId, String orderNumber) {
        return jdbc.sql("UPDATE " + trackers + " SET order_number = :number, version = version + 1 "
                        + "WHERE instance_id = :id AND order_number IS NULL")
                .param("id", instanceId).param("number", orderNumber).update();
    }

    /** Whether another workflow already carries this order number (it is unique, like school-core's). */
    public boolean orderNumberTaken(String orderNumber, String exceptOrderId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM " + trackers + " WHERE order_number = :number AND order_id <> :order)")
                .param("number", orderNumber).param("order", exceptOrderId).query(Boolean.class).single();
    }

    public void insertStep(long instanceId, long schoolId, String definitionId, int stepOrder, String status, Long assignee,
                           OffsetDateTime startedAt, OffsetDateTime dueAt) {
        jdbc.sql("INSERT INTO " + instanceSteps + " (instance_id, school_id, definition_id, step_order, status, assignee_user_id, "
                        + "assigned_at, started_at, due_at) VALUES (:instance, :school, :definition, :step, :status, :assignee, "
                        + "CASE WHEN :assignee::bigint IS NULL THEN NULL ELSE now() END, :started, :due)")
                .param("instance", instanceId).param("school", schoolId).param("definition", definitionId).param("step", stepOrder)
                .param("status", status).param("assignee", assignee).param("started", startedAt).param("due", dueAt).update();
    }

    /** A stage imported as already completed from the retired production workflow (D4); its gates are not applied. */
    public void insertImportedStep(long instanceId, long schoolId, String definitionId, int stepOrder, Long assignee,
                                   OffsetDateTime startedAt, OffsetDateTime completedAt, Long completedBy) {
        jdbc.sql("INSERT INTO " + instanceSteps + " (instance_id, school_id, definition_id, step_order, status, assignee_user_id, "
                        + "assigned_at, started_at, completed_at, completed_by) VALUES (:instance, :school, :definition, :step, 'COMPLETED', "
                        + ":assignee, CASE WHEN :assignee::bigint IS NULL THEN NULL ELSE now() END, :started, :completed, :by)")
                .param("instance", instanceId).param("school", schoolId).param("definition", definitionId).param("step", stepOrder)
                .param("assignee", assignee).param("started", startedAt).param("completed", completedAt).param("by", completedBy)
                .update();
    }

    public void setInstanceProgress(long instanceId, int currentStep, String status, boolean finished) {
        jdbc.sql("UPDATE " + instances + " SET current_step = :current, status = :status, "
                        + "completed_at = CASE WHEN :finished THEN now() ELSE NULL END, version = version + 1 WHERE id = :id")
                .param("id", instanceId).param("current", currentStep).param("status", status).param("finished", finished).update();
    }

    /**
     * Moves the instance version without a step change and returns the new one, so a status queued at it is newer than every
     * status pushed before (the order number filled into a workflow created before numbers, D13).
     */
    public long bumpInstanceVersion(long instanceId) {
        return jdbc.sql("UPDATE " + instances + " SET version = version + 1 WHERE id = :id RETURNING version")
                .param("id", instanceId).query(Long.class).single();
    }

    /** The instance's version: every step change increments it, so it orders the order's status deliveries. */
    public long instanceVersion(long instanceId) {
        return jdbc.sql("SELECT version FROM " + instances + " WHERE id = :id").param("id", instanceId).query(Long.class).single();
    }

    /** The four contact fields of D36; the factory contact is stored in vendor_name/vendor_phone (both or neither). */
    public void setContacts(long instanceId, String schoolName, String schoolPhone, String factoryName, String factoryPhone) {
        jdbc.sql("UPDATE " + trackers + " SET school_contact_name = :schoolName, school_contact_phone = :schoolPhone, "
                        + "vendor_name = :factoryName, vendor_phone = :factoryPhone, version = version + 1 WHERE instance_id = :id")
                .param("id", instanceId).param("schoolName", schoolName).param("schoolPhone", schoolPhone)
                .param("factoryName", factoryName).param("factoryPhone", factoryPhone).update();
    }

    /** Replaces the order's vendor selections while the order is at the version the caller loaded; 0 rows if not. */
    public int setVendorSelections(long instanceId, Map<String, List<Long>> selections, long expectedVersion) {
        try {
            String serialized = json.writeValueAsString(selections);
            return jdbc.sql("UPDATE " + trackers + " SET vendor_selections = CAST(:selections AS jsonb), version = version + 1 "
                            + "WHERE instance_id = :id AND version = :version")
                    .param("id", instanceId).param("selections", serialized).param("version", expectedVersion).update();
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("Could not serialize vendor selections", e);
        }
    }

    public void setDesigner(long instanceId, Long designerId) {
        jdbc.sql("UPDATE " + trackers + " SET designer_id = :designer, version = version + 1 WHERE instance_id = :id")
                .param("id", instanceId).param("designer", designerId).update();
    }

    /** Head Approval approves the CDR link saved now, with its required comment (D21). */
    public void approveDesign(long instanceId, String comment, long userId, String cdrLink) {
        jdbc.sql("UPDATE " + trackers + " SET design_state = 'APPROVED', design_comment = :comment, design_state_note = NULL, "
                        + "design_state_at = now(), design_state_by = :user, approved_cdr_link = :link, approved_draft_ref = NULL, "
                        + "version = version + 1 WHERE instance_id = :id")
                .param("id", instanceId).param("comment", comment).param("user", userId).param("link", cdrLink).update();
    }

    /** Head Approval requests changes (D21): the link must be approved again, so the approval reference goes. */
    public void requestDesignChanges(long instanceId, String comment, long userId) {
        jdbc.sql("UPDATE " + trackers + " SET design_state = 'CHANGES_REQUESTED', design_comment = :comment, design_state_note = NULL, "
                        + "design_state_at = now(), design_state_by = :user, approved_cdr_link = NULL, approved_draft_ref = NULL, "
                        + "version = version + 1 WHERE instance_id = :id")
                .param("id", instanceId).param("comment", comment).param("user", userId).update();
    }

    /**
     * The design reaches Head Approval (the stage before it was completed): a design that is not approved is now waiting
     * for a decision. The last decision's comment, time and author are kept as they were; an approval stays.
     */
    public void markDesignSent(long instanceId) {
        jdbc.sql("UPDATE " + trackers + " SET design_state = 'SENT', version = version + 1 "
                        + "WHERE instance_id = :id AND design_state IN ('NOT_SENT', 'CHANGES_REQUESTED')")
                .param("id", instanceId).update();
    }

    /**
     * A reopen at or before Head Approval takes an approval back: the design is no longer approved, and the reopening
     * superadmin is recorded as the one who changed it. A change request stays as it is.
     */
    public void withdrawDesignApproval(long instanceId, long userId) {
        jdbc.sql("UPDATE " + trackers + " SET design_state = 'NOT_SENT', design_comment = NULL, design_state_note = NULL, "
                        + "design_state_at = now(), design_state_by = :user, approved_cdr_link = NULL, approved_draft_ref = NULL, "
                        + "version = version + 1 WHERE instance_id = :id AND design_state = 'APPROVED'")
                .param("id", instanceId).param("user", userId).update();
    }

    // ---- the list -----------------------------------------------------------------------------------------------------

    /** Who the list is for: null schools and scopes for superadmin, else the operator's school-wide schools and item scopes. */
    public record Scope(Collection<Long> schools, Collection<String> itemScopes) {
        public boolean none() { return schools != null && schools.isEmpty() && itemScopes.isEmpty(); }
    }

    /**
     * The list's filters (design §3.5 {@code GET /item-orders}). {@code assignee} is null (anyone), {@code "unassigned"} or
     * a user id; {@code sla} one of the SLA states, or {@code DUE_TODAY} (the "Due today" chip, {@link #DUE_TODAY});
     * {@code sort} {@code due}, {@code placed} or {@code number}.
     */
    public record ListFilter(Scope scope, Collection<String> items, String stepCode, String phase, String assignee, Long schoolId,
                             String sla, String q, boolean includeFinished, String sort, int limit, int offset,
                             OffsetDateTime now, OffsetDateTime dueSoonUntil) { }

    /** One row of the list: an order's progress, its active step and the step's template code, title and phase. */
    public record ListRow(String orderId, String orderNumber, String definitionId, String categoryCode, String itemCode, long schoolId,
                          String schoolName, boolean customised, int orderedCount, OffsetDateTime placedAt, String timeZone,
                          String instanceStatus, String phase, String stepCode, String stepTitle, Integer activeStep,
                          Long assigneeUserId, OffsetDateTime dueAt, int completed, int total) { }

    public record Counts(long open, long overdue, long dueToday, long unassigned, long mine) { }

    /**
     * The active step is due on today's date in the order's own time zone (the school's), overdue or not. The "Due today"
     * count and the {@code DUE_TODAY} filter share it, so the filter lists exactly what the chip counts. Binds {@code :now}.
     */
    private static final String DUE_TODAY = "s.due_at IS NOT NULL AND (s.due_at AT TIME ZONE t.time_zone)::date "
            + "= (CAST(:now AS timestamptz) AT TIME ZONE t.time_zone)::date";

    private static final String LIST_FROM = " FROM %s t JOIN %s i ON i.id = t.instance_id AND i.school_id = t.school_id "
            + "LEFT JOIN %s s ON s.instance_id = t.instance_id AND s.status = 'ACTIVE' "
            + "LEFT JOIN %s ws ON ws.definition_id = t.definition_id AND ws.step_order = i.current_step WHERE 1 = 1";

    private String listFrom() { return LIST_FROM.formatted(trackers, instances, instanceSteps, steps); }

    public List<ListRow> list(ListFilter filter) {
        if (filter.scope().none()) return List.of();
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("SELECT t.order_id, t.order_number, t.definition_id, t.category_code, t.item_code, "
                + "t.school_id, t.school_name, t.customised, t.ordered_count, t.placed_at, t.time_zone, i.status AS instance_status, "
                + "ws.phase, ws.step_code, ws.step_name, s.step_order AS active_step, s.assignee_user_id, s.due_at, "
                + "(SELECT count(*) FROM " + instanceSteps + " c WHERE c.instance_id = t.instance_id AND c.status = 'COMPLETED') AS completed, "
                + "(SELECT count(*) FROM " + instanceSteps + " c WHERE c.instance_id = t.instance_id) AS total")
                .append(listFrom());
        filters(filter, sql, params, true);
        sql.append(switch (filter.sort() == null ? "due" : filter.sort()) {
            case "placed" -> " ORDER BY t.placed_at DESC, t.order_id";
            case "number" -> " ORDER BY t.order_number NULLS LAST, t.order_id";
            default -> " ORDER BY (s.step_order IS NULL), s.due_at NULLS LAST, t.placed_at DESC, t.order_id";
        });
        sql.append(" LIMIT :limit OFFSET :offset");
        params.put("limit", filter.limit());
        params.put("offset", filter.offset());
        return jdbc.sql(sql.toString()).params(params)
                .query((rs, n) -> new ListRow(rs.getString("order_id"), rs.getString("order_number"), rs.getString("definition_id"),
                        rs.getString("category_code"), rs.getString("item_code"), rs.getLong("school_id"), rs.getString("school_name"),
                        rs.getBoolean("customised"), rs.getInt("ordered_count"), rs.getObject("placed_at", OffsetDateTime.class),
                        rs.getString("time_zone"), rs.getString("instance_status"), rs.getString("phase"), rs.getString("step_code"),
                        rs.getString("step_name"), rs.getObject("active_step", Integer.class), rs.getObject("assignee_user_id", Long.class),
                        rs.getObject("due_at", OffsetDateTime.class), rs.getInt("completed"), rs.getInt("total")))
                .list();
    }

    /** Rows matching every filter, before paging. */
    public long count(ListFilter filter) {
        if (filter.scope().none()) return 0;
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("SELECT count(*)").append(listFrom());
        filters(filter, sql, params, true);
        return jdbc.sql(sql.toString()).params(params).query(Long.class).single();
    }

    /**
     * The summary chips over every unfinished order in scope that matches the item and school filters; the other filters
     * and paging do not change them. "Due today" is the school's own calendar day.
     */
    public Counts counts(ListFilter filter, Long me) {
        if (filter.scope().none()) return new Counts(0, 0, 0, 0, 0);
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("SELECT count(*) AS open, "
                + "count(*) FILTER (WHERE s.due_at < CAST(:now AS timestamptz)) AS overdue, "
                + "count(*) FILTER (WHERE " + DUE_TODAY + ") AS due_today, "
                + "count(*) FILTER (WHERE s.step_order IS NOT NULL AND s.assignee_user_id IS NULL) AS unassigned, "
                + "count(*) FILTER (WHERE s.assignee_user_id = CAST(:me AS bigint)) AS mine")
                .append(listFrom());
        filters(filter, sql, params, false);
        sql.append(" AND i.status <> 'COMPLETED'");
        params.put("now", filter.now());
        params.put("me", me);
        return jdbc.sql(sql.toString()).params(params)
                .query((rs, n) -> new Counts(rs.getLong("open"), rs.getLong("overdue"), rs.getLong("due_today"),
                        rs.getLong("unassigned"), rs.getLong("mine"))).single();
    }

    /** The scope always; then the item and school filters; then, for the rows themselves, every other filter. */
    private static void filters(ListFilter f, StringBuilder sql, Map<String, Object> params, boolean all) {
        Scope scope = f.scope();
        if (scope.schools() != null) {
            List<String> parts = new ArrayList<>();
            if (!scope.schools().isEmpty()) {
                parts.add("t.school_id IN (:scopeSchools)");
                params.put("scopeSchools", List.copyOf(scope.schools()));
            }
            if (!scope.itemScopes().isEmpty()) {
                parts.add("(t.school_id::text || ':' || t.category_code) IN (:scopeItems)");
                params.put("scopeItems", List.copyOf(scope.itemScopes()));
            }
            sql.append(" AND (").append(String.join(" OR ", parts)).append(")");
        }
        if (f.items() != null && !f.items().isEmpty()) {
            sql.append(" AND t.item_code IN (:items)");
            params.put("items", List.copyOf(f.items()));
        }
        if (f.schoolId() != null) {
            sql.append(" AND t.school_id = :school");
            params.put("school", f.schoolId());
        }
        if (!all) return;
        if (!f.includeFinished()) sql.append(" AND i.status <> 'COMPLETED'");
        if (f.stepCode() != null) {
            sql.append(" AND s.step_order IS NOT NULL AND ws.step_code = :stepCode");
            params.put("stepCode", f.stepCode());
        }
        if (f.phase() != null) {
            sql.append(" AND ws.phase = :phase");
            params.put("phase", f.phase());
        }
        if (f.assignee() != null) {
            if ("unassigned".equals(f.assignee())) {
                sql.append(" AND s.step_order IS NOT NULL AND s.assignee_user_id IS NULL");
            } else {
                sql.append(" AND s.assignee_user_id = :assignee");
                params.put("assignee", Long.parseLong(f.assignee()));
            }
        }
        if (f.sla() != null) {
            switch (f.sla()) {
                case "OVERDUE" -> sql.append(" AND s.due_at < CAST(:now AS timestamptz)");
                case "DUE_SOON" -> sql.append(" AND s.due_at >= CAST(:now AS timestamptz) AND s.due_at <= CAST(:soon AS timestamptz)");
                case "ON_TRACK" -> sql.append(" AND s.due_at > CAST(:soon AS timestamptz)");
                case "DUE_TODAY" -> sql.append(" AND ").append(DUE_TODAY);
                default -> sql.append(" AND s.due_at IS NULL");
            }
            params.put("now", f.now());
            params.put("soon", f.dueSoonUntil());
        }
        if (f.q() != null && !f.q().isBlank()) {
            sql.append(" AND (t.order_number ILIKE :q ESCAPE '\\' OR t.order_id ILIKE :q ESCAPE '\\' OR t.school_name ILIKE :q ESCAPE '\\')");
            params.put("q", "%" + f.q().strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
    }

    // ---- steps ------------------------------------------------------------------------------------------------------

    public List<StepRow> steps(long instanceId) {
        return jdbc.sql(stepSelect() + " WHERE instance_id = :id ORDER BY step_order").param("id", instanceId)
                .query((rs, n) -> stepRow(rs)).list();
    }

    private String stepSelect() {
        return "SELECT step_order, status, assignee_user_id, assigned_at, started_at, due_at, completed_at, completed_by, "
                + "data::text AS data, data_saved_at, data_saved_by, version FROM " + instanceSteps;
    }

    private StepRow stepRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StepRow(rs.getInt("step_order"), rs.getString("status"), rs.getObject("assignee_user_id", Long.class),
                rs.getObject("assigned_at", OffsetDateTime.class), rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("due_at", OffsetDateTime.class), rs.getObject("completed_at", OffsetDateTime.class),
                rs.getObject("completed_by", Long.class), readJson(rs.getString("data")),
                rs.getObject("data_saved_at", OffsetDateTime.class), rs.getObject("data_saved_by", Long.class), rs.getLong("version"));
    }

    /**
     * Writes the step's whole data, which the caller has already merged from the row it read under the order's lock;
     * returns 0 when the step changed since the caller loaded it.
     */
    public int saveStepData(long instanceId, int stepOrder, Map<String, Object> data, long userId, long expectedVersion) {
        return jdbc.sql("UPDATE " + instanceSteps + " SET data = CAST(:data AS jsonb), data_saved_at = now(), data_saved_by = :user, "
                        + "version = version + 1 WHERE instance_id = :id AND step_order = :step AND version = :version")
                .param("id", instanceId).param("step", stepOrder).param("data", writeJson(data)).param("user", userId)
                .param("version", expectedVersion).update();
    }

    /** Rewrites a step's data with no version check, under the order's lock (a reopen voiding a later sign-off). */
    public void replaceStepData(long instanceId, int stepOrder, Map<String, Object> data) {
        jdbc.sql("UPDATE " + instanceSteps + " SET data = CAST(:data AS jsonb), version = version + 1 "
                        + "WHERE instance_id = :id AND step_order = :step")
                .param("id", instanceId).param("step", stepOrder).param("data", writeJson(data)).update();
    }

    /** Moves the step's version while it is at the version the caller loaded (a Head Approval decision); 0 rows if not. */
    public int touchStep(long instanceId, int stepOrder, long expectedVersion) {
        return jdbc.sql("UPDATE " + instanceSteps + " SET version = version + 1 WHERE instance_id = :id AND step_order = :step AND version = :version")
                .param("id", instanceId).param("step", stepOrder).param("version", expectedVersion).update();
    }

    /**
     * Moves a step's version with nothing else changed, under the order's lock: a screen that loaded the step before is
     * then refused (Head Approval, once the CDR link it would approve has changed).
     */
    public void bumpStep(long instanceId, int stepOrder) {
        jdbc.sql("UPDATE " + instanceSteps + " SET version = version + 1 WHERE instance_id = :id AND step_order = :step")
                .param("id", instanceId).param("step", stepOrder).update();
    }

    public int completeStep(long instanceId, int stepOrder, long userId, long expectedVersion) {
        return jdbc.sql("UPDATE " + instanceSteps + " SET status = 'COMPLETED', completed_at = now(), completed_by = :user, version = version + 1 "
                        + "WHERE instance_id = :id AND step_order = :step AND status = 'ACTIVE' AND version = :version")
                .param("id", instanceId).param("step", stepOrder).param("user", userId).param("version", expectedVersion).update();
    }

    /** Makes a step active and (re)starts its clock; used for the next step and for a step superadmin reopens. */
    public void activateStep(long instanceId, int stepOrder, OffsetDateTime startedAt, OffsetDateTime dueAt) {
        jdbc.sql("UPDATE " + instanceSteps + " SET status = 'ACTIVE', started_at = :started, due_at = :due, completed_at = NULL, "
                        + "completed_by = NULL, version = version + 1 WHERE instance_id = :id AND step_order = :step")
                .param("id", instanceId).param("step", stepOrder).param("started", startedAt).param("due", dueAt).update();
    }

    /** Later steps revert to not reached when a step is reopened; their saved data and notes are kept. */
    public void lockStepsAfter(long instanceId, int stepOrder) {
        jdbc.sql("UPDATE " + instanceSteps + " SET status = 'LOCKED', started_at = NULL, due_at = NULL, completed_at = NULL, "
                        + "completed_by = NULL, version = version + 1 WHERE instance_id = :id AND step_order > :step AND status <> 'LOCKED'")
                .param("id", instanceId).param("step", stepOrder).update();
    }

    public void assignStep(long instanceId, int stepOrder, Long userId, long assignedBy) {
        jdbc.sql("UPDATE " + instanceSteps + " SET assignee_user_id = :user, assigned_by = :by, assigned_at = now(), version = version + 1 "
                        + "WHERE instance_id = :id AND step_order = :step")
                .param("id", instanceId).param("step", stepOrder).param("user", userId).param("by", assignedBy).update();
    }

    // ---- notes ----------------------------------------------------------------------------------------------------------

    /** Every stage and internal note of the order, newest first. */
    public List<NoteRow> notes(long instanceId) {
        return jdbc.sql("SELECT id, step_order, author_user_id, body, created_at FROM " + notes
                        + " WHERE instance_id = :id ORDER BY created_at DESC, id DESC")
                .param("id", instanceId)
                .query((rs, n) -> noteRow(rs)).list();
    }

    /** Appends a note; {@code createdAt} null means now. */
    public NoteRow addNote(long instanceId, long schoolId, long authorUserId, String body, Integer stepOrder, OffsetDateTime createdAt) {
        return jdbc.sql("INSERT INTO " + notes + " (instance_id, school_id, author_user_id, body, step_order, created_at) "
                        + "VALUES (:id, :school, :author, :body, :step, COALESCE(CAST(:created AS timestamptz), now())) "
                        + "RETURNING id, step_order, author_user_id, body, created_at")
                .param("id", instanceId).param("school", schoolId).param("author", authorUserId).param("body", body)
                .param("step", stepOrder).param("created", createdAt)
                .query((rs, n) -> noteRow(rs)).single();
    }

    private static NoteRow noteRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new NoteRow(rs.getLong("id"), rs.getObject("step_order", Integer.class), rs.getLong("author_user_id"),
                rs.getString("body"), rs.getObject("created_at", OffsetDateTime.class));
    }

    // ---- designers ------------------------------------------------------------------------------------------------------

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

    // ---- history ----------------------------------------------------------------------------------------------------------

    /**
     * One audit row in {@code workflow_actions} (D40). The actor is the signed-in user, or none for a machine call; the
     * notes are cut to the column's 1,000 characters.
     */
    public void recordAction(long instanceId, long schoolId, int stepOrder, String action, String notes) {
        TenantContext actor = TenantContext.get();
        String clipped = notes == null ? null : notes.length() > 1000 ? notes.substring(0, 999) + "…" : notes;
        jdbc.sql("INSERT INTO " + actions + " (instance_id, step_order, action, actor_id, actor_email, notes, school_id, request_correlation_id, authority) "
                        + "VALUES (:id, :step, :action, :actorId, :email, :notes, :school, :request, 'SERVER')")
                .param("id", instanceId).param("step", stepOrder).param("action", action).param("actorId", actor.userId())
                .param("email", actor.email()).param("notes", clipped).param("school", schoolId).param("request", correlationId())
                .update();
    }

    // ---- helpers --------------------------------------------------------------------------------------------------------

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
