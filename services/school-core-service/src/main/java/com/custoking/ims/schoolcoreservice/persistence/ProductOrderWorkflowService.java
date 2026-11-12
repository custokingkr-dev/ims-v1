package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.persistence.CatalogReadRepository.CatalogOrderRow;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.security.TenantScope;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * The production workflow of a placed product order outside the notebook tracker: nine fixed stages, from order details to
 * delivery and invoicing. Permissions mirror the notebook order tracker (operations-service NotebookTrackerService):
 * superadmin and the school's operators run the stages, only superadmin completes Head Approval, and everyone else who may
 * read the order sees its progress with each stage's details and notes withheld.
 *
 * <p>Every call starts from {@link CatalogOrderFormService#readableOrder}, the same scope, item-operator, read-scope and
 * ORDERS-module checks as every other order read. The tenant data source already sets the school and superadmin bypass on
 * each connection; that load adds an operator's transaction-local school set, which the workflow tables' row-level policy
 * reads too, so there is no scoping code here.
 */
@Service
public class ProductOrderWorkflowService {
    /**
     * The categories that run this workflow. The server is the source of truth for the list: the order form detail tells the
     * client per order whether a workflow is available ({@link #available}). Notebooks run the notebook tracker; ties,
     * trophies and the rest have no production workflow yet.
     */
    public static final Set<String> CATEGORIES = Set.of("BILLBOOKS", "BELTS", "BROCHURES", "CERTIFICATES", "FLEX", "FLIERS",
            "FLUTE_BOARDS", "REPORT_CARDS", "STANDEES");
    /** A draft has nothing to produce yet and a cancelled order never will. */
    static final Set<String> UNPLACED = Set.of("DRAFT", "CANCELLED");
    /**
     * Finished one way or another: the statuses the order summary no longer counts as active, plus the legacy FULFILLED. Such
     * an order keeps a workflow it already has, read-only, and is never given a new one.
     */
    static final Set<String> TERMINAL = Set.of("DELIVERED", "FULFILLED", "REJECTED", "RETURNED", "COMPLETED");
    static final String HEAD_APPROVAL = "HEAD_APPROVAL";
    private static final List<Stage> STAGES = List.of(
            new Stage("ORDER_DETAILS", "Order Details"),
            new Stage("DESIGN_ACTIVITY", "Designing Activity"),
            new Stage("CDR_CONVERSION", "CDR Conversion"),
            new Stage(HEAD_APPROVAL, "Head Approval"),
            new Stage("PRODUCTION_PLANNING", "Production Planning"),
            new Stage("PRE_PRINT_QUALITY_CHECK", "Pre-Print Quality Check"),
            new Stage("PRODUCTION_FOLLOW_UP", "Production Follow-up"),
            new Stage("FINAL_VERIFICATION", "Final Verification"),
            new Stage("DELIVERY_INVOICING", "Delivery & Invoicing"));
    /**
     * Creates a workflow and all its stages in one statement. A concurrent creator that got there first leaves the workflow
     * insert with nothing to return, so this one adds no stages either.
     */
    private static final String CREATE = """
            WITH workflow AS (
                INSERT INTO catalog.product_order_workflows (order_id, school_id, category_code, current_stage, status)
                VALUES (:order, :school, :category, :first, 'ACTIVE')
                ON CONFLICT (order_id) DO NOTHING
                RETURNING order_id, school_id
            )
            INSERT INTO catalog.product_order_workflow_steps (order_id, school_id, stage_code, stage_order, title, status, started_at)
            SELECT workflow.order_id, workflow.school_id, stage.code, stage.stage_no, stage.title,
                   CASE WHEN stage.stage_no = 1 THEN 'ACTIVE' ELSE 'LOCKED' END,
                   CASE WHEN stage.stage_no = 1 THEN now() END
            FROM workflow CROSS JOIN (VALUES %s) AS stage(code, title, stage_no)
            """.formatted(IntStream.range(0, STAGES.size())
            .mapToObj(i -> "(:code" + i + ", :title" + i + ", " + (i + 1) + ")").collect(Collectors.joining(", ")));
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final CatalogOrderFormService orders;

    public ProductOrderWorkflowService(JdbcClient jdbc, CatalogOrderFormService orders) {
        this.jdbc = jdbc;
        this.orders = orders;
    }

    public record Stage(String code, String title) { }
    /** One stage. A school-side reader gets {@code data} empty and {@code note} null: they follow progress only. */
    public record StageView(String code, int order, String title, String status, Map<String, Object> data, String note,
                            String completedAt) { }
    public record WorkflowView(String orderId, String category, String status, String currentStage, long version,
                               boolean canEdit, boolean canComplete, List<StageView> stages) { }
    public record StageUpdate(Map<String, Object> data, String note, Long expectedVersion) { }
    private record Instance(String status, String currentStage, long version) { }
    private record Order(String id, long schoolId, String category, String status) { }

    /**
     * The order's workflow. A placed, still-open order of a workflow category is given one the first time it is opened; a
     * finished order shows the one it has, read-only, and is refused when it has none, so a read never starts a workflow for
     * an order that is past production.
     */
    @Transactional
    public WorkflowView view(String orderId) {
        Order order = order(orderId);
        Instance instance = find(order.id(), false);
        if (instance == null) instance = create(order, false);
        return view(order, instance);
    }

    @Transactional
    public WorkflowView saveStage(String orderId, String stageCode, StageUpdate update) {
        Order order = order(orderId);
        Stage stage = stage(stageCode);
        requireWorker(order);
        StageUpdate request = update == null ? new StageUpdate(null, null, null) : update;
        Map<String, Object> data = request.data() == null ? Map.of() : request.data();
        if (data.size() > 40 || data.values().stream().anyMatch(value ->
                !(value == null || value instanceof String || value instanceof Number || value instanceof Boolean))) {
            throw bad("Stage data must contain at most 40 simple values");
        }
        String encoded;
        try {
            encoded = JSON.writeValueAsString(data);
        } catch (RuntimeException ex) {
            throw bad("Stage data could not be encoded");
        }
        if (encoded.length() > 12_000) throw bad("Stage data is too large");
        String note = request.note() == null || request.note().isBlank() ? null : request.note().strip();
        if (note != null && note.length() > 4000) throw bad("A stage note is limited to 4000 characters");
        Instance instance = lockForChange(order, request.expectedVersion());
        if (!stage.code().equals(instance.currentStage())) throw conflict("Only the active production stage can be updated");
        jdbc.sql("""
                UPDATE catalog.product_order_workflow_steps
                SET data = CAST(:data AS jsonb), stage_note = :note, updated_by = :actor, version = version + 1
                WHERE order_id = :order AND stage_code = :stage AND status = 'ACTIVE'
                """).param("data", encoded).param("note", note).param("actor", TenantContext.get().userId())
                .param("order", order.id()).param("stage", stage.code()).update();
        jdbc.sql("UPDATE catalog.product_order_workflows SET version = version + 1, updated_at = now() WHERE order_id = :order")
                .param("order", order.id()).update();
        return view(order, find(order.id(), false));
    }

    @Transactional
    public WorkflowView completeStage(String orderId, String stageCode, Long expectedVersion) {
        Order order = order(orderId);
        Stage stage = stage(stageCode);
        requireWorker(order);
        if (HEAD_APPROVAL.equals(stage.code()) && !TenantContext.get().isSuperAdmin()) {
            throw forbidden("Only superadmin can complete Head Approval");
        }
        Instance instance = lockForChange(order, expectedVersion);
        if (!stage.code().equals(instance.currentStage())) throw conflict("Only the active production stage can be completed");
        jdbc.sql("""
                UPDATE catalog.product_order_workflow_steps
                SET status = 'COMPLETED', completed_at = now(), updated_by = :actor, version = version + 1
                WHERE order_id = :order AND stage_code = :stage AND status = 'ACTIVE'
                """).param("actor", TenantContext.get().userId()).param("order", order.id()).param("stage", stage.code()).update();
        int next = STAGES.indexOf(stage) + 1;
        if (next == STAGES.size()) {
            jdbc.sql("UPDATE catalog.product_order_workflows SET status = 'COMPLETED', updated_at = now(), version = version + 1 WHERE order_id = :order")
                    .param("order", order.id()).update();
        } else {
            jdbc.sql("UPDATE catalog.product_order_workflow_steps SET status = 'ACTIVE', started_at = now() WHERE order_id = :order AND stage_code = :stage")
                    .param("order", order.id()).param("stage", STAGES.get(next).code()).update();
            jdbc.sql("UPDATE catalog.product_order_workflows SET current_stage = :stage, updated_at = now(), version = version + 1 WHERE order_id = :order")
                    .param("stage", STAGES.get(next).code()).param("order", order.id()).update();
        }
        return view(order, find(order.id(), false));
    }

    /**
     * Whether {@link #view} would answer this caller with a workflow, for the order form detail. The caller has already read
     * the order through the same checks (scope, read scope, ORDERS module), so only the order itself decides, and a database
     * read is needed only for a finished order, which shows a workflow only if it already has one.
     */
    @Transactional(readOnly = true)
    public boolean available(CatalogOrderRow row) {
        if (row == null || row.id() == null || !CATEGORIES.contains(upper(row.category())) || UNPLACED.contains(upper(row.status()))) {
            return false;
        }
        if (!TERMINAL.contains(upper(row.status()))) return true;
        try {
            orders.readableOrder(row.id());
        } catch (ResponseStatusException refused) {
            return false;
        }
        return find(row.id(), false) != null;
    }

    private Order order(String orderId) {
        Map<String, Object> row = orders.readableOrder(orderId);
        String category = upper(row.get("category"));
        if (!CATEGORIES.contains(category)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This order has no production workflow");
        }
        String status = upper(row.get("status"));
        if ("DRAFT".equals(status)) throw conflict("The production workflow starts once the order is placed");
        if (UNPLACED.contains(status)) throw conflict("A cancelled order has no production workflow");
        return new Order(String.valueOf(row.get("id")), ((Number) row.get("school_id")).longValue(), category, status);
    }

    /** Creates the workflow of an order that has none, which only a placed order still in production may be given. */
    private Instance create(Order order, boolean lock) {
        if (TERMINAL.contains(order.status())) {
            throw conflict("This order is " + order.status().toLowerCase(Locale.ROOT) + " and has no production workflow");
        }
        var insert = jdbc.sql(CREATE).param("order", order.id()).param("school", order.schoolId())
                .param("category", order.category()).param("first", STAGES.getFirst().code());
        for (int i = 0; i < STAGES.size(); i++) {
            insert = insert.param("code" + i, STAGES.get(i).code()).param("title" + i, STAGES.get(i).title());
        }
        insert.update();
        Instance created = find(order.id(), lock);
        if (created == null) throw new IllegalStateException("Production workflow was not created for " + order.id());
        return created;
    }

    /**
     * Locks the workflow for a change and checks the caller's version against it. A finished order and a completed
     * workflow are refused with 403, like every other case in which {@link #view} reports {@code canEdit=false}; only a
     * stale version or stage is a 409 conflict, which a refresh resolves.
     */
    private Instance lockForChange(Order order, Long expectedVersion) {
        if (expectedVersion == null || expectedVersion < 0) throw bad("expectedVersion is required");
        if (TERMINAL.contains(order.status())) {
            throw forbidden("This order is " + order.status().toLowerCase(Locale.ROOT) + "; its production workflow is read-only");
        }
        Instance instance = find(order.id(), true);
        if (instance == null) instance = create(order, true);
        if (!"ACTIVE".equals(instance.status())) throw forbidden("Production workflow is already complete");
        if (instance.version() != expectedVersion) throw conflict("Workflow changed; refresh and try again");
        return instance;
    }

    private Instance find(String orderId, boolean lock) {
        return jdbc.sql("SELECT status, current_stage, version FROM catalog.product_order_workflows WHERE order_id = :order"
                        + (lock ? " FOR UPDATE" : ""))
                .param("order", orderId)
                .query((rs, n) -> new Instance(rs.getString("status"), rs.getString("current_stage"), rs.getLong("version")))
                .optional().orElse(null);
    }

    private WorkflowView view(Order order, Instance instance) {
        boolean internal = internal();
        List<StageView> stages = jdbc.sql("""
                SELECT stage_code, stage_order, title, status, data, stage_note, completed_at
                FROM catalog.product_order_workflow_steps WHERE order_id = :order ORDER BY stage_order
                """).param("order", order.id()).query((rs, n) -> {
            OffsetDateTime completed = rs.getObject("completed_at", OffsetDateTime.class);
            return new StageView(rs.getString("stage_code"), rs.getInt("stage_order"), rs.getString("title"),
                    rs.getString("status"), internal ? data(rs.getString("data")) : Map.of(),
                    internal ? rs.getString("stage_note") : null, completed == null ? null : completed.toInstant().toString());
        }).list();
        boolean canEdit = worker(order) && mayUpdate() && "ACTIVE".equals(instance.status()) && !TERMINAL.contains(order.status());
        boolean canComplete = canEdit && (!HEAD_APPROVAL.equals(instance.currentStage()) || TenantContext.get().isSuperAdmin());
        return new WorkflowView(order.id(), order.category(), instance.status(), instance.currentStage(), instance.version(),
                canEdit, canComplete, stages);
    }

    private static Map<String, Object> data(String raw) {
        try {
            return raw == null ? Map.of() : JSON.readValue(raw, MAP);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Could not read production stage data", ex);
        }
    }

    /** Superadmin, and an operator who works this order's school (or its item there), run the stages. */
    private static boolean worker(Order order) {
        TenantContext ctx = TenantContext.get();
        return ctx.isSuperAdmin() || ctx.isOperations() && ctx.operatesItem(order.schoolId(), order.category());
    }

    /** Superadmin and operators see stage details and notes; a school sees titles and statuses only. */
    private static boolean internal() {
        TenantContext ctx = TenantContext.get();
        return ctx.isSuperAdmin() || ctx.isOperations();
    }

    private static boolean mayUpdate() {
        TenantContext ctx = TenantContext.get();
        return ctx.isSuperAdmin() || ctx.hasPermission("order:update");
    }

    private static void requireWorker(Order order) {
        if (!worker(order)) throw forbidden("Production stages are run by superadmin and operators");
        TenantScope.requirePermissionIfAuthenticated("order:update");
    }

    private static Stage stage(String code) {
        return STAGES.stream().filter(value -> value.code().equals(code)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Production stage not found"));
    }

    private static String upper(Object value) {
        return value == null ? "" : String.valueOf(value).trim().toUpperCase(Locale.ROOT);
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
}
