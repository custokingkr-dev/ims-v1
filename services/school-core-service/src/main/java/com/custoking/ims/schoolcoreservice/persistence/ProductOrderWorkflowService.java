package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * The retired PR #380 production workflow of a product order (item order workflows design D4), read-only. Every item now
 * runs on the order workflow engine in operations-service; catalog V38 moved each open order onto it, carrying its stage
 * progress, and made these tables read-only for the runtime role. What remains is the record: an order that had one of
 * these workflows still shows it here, and nothing creates, saves or completes one any more (the controller answers those
 * requests 409 WORKFLOW_MOVED). The tables are dropped in a later release.
 *
 * <p>Every read starts from {@link CatalogOrderFormService#readableOrder}, the same scope, item-operator, read-scope and
 * ORDERS-module checks as every other order read; the workflow tables' row-level policy reads the scope it sets.
 */
@Service
public class ProductOrderWorkflowService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final CatalogOrderFormService orders;

    public ProductOrderWorkflowService(JdbcClient jdbc, CatalogOrderFormService orders) {
        this.jdbc = jdbc;
        this.orders = orders;
    }

    /** One stage. A school-side reader gets {@code data} empty and {@code note} null: they follow progress only. */
    public record StageView(String code, int order, String title, String status, Map<String, Object> data, String note,
                            String completedAt) { }
    /** The workflow as it stood when it was retired. {@code canEdit} and {@code canComplete} are always false. */
    public record WorkflowView(String orderId, String category, String status, String currentStage, long version,
                               boolean canEdit, boolean canComplete, List<StageView> stages) { }
    private record Instance(String category, String status, String currentStage, long version) { }

    /** The order's retired workflow; 404 when the order never had one. */
    @Transactional(readOnly = true)
    public WorkflowView view(String orderId) {
        Map<String, Object> order = orders.readableOrder(orderId);
        String id = String.valueOf(order.get("id"));
        Instance instance = jdbc.sql("SELECT category_code, status, current_stage, version FROM catalog.product_order_workflows WHERE order_id = :order")
                .param("order", id)
                .query((rs, n) -> new Instance(rs.getString("category_code"), rs.getString("status"), rs.getString("current_stage"),
                        rs.getLong("version"))).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "This order has no production workflow record"));
        boolean internal = internal();
        List<StageView> stages = jdbc.sql("""
                SELECT stage_code, stage_order, title, status, data, stage_note, completed_at
                FROM catalog.product_order_workflow_steps WHERE order_id = :order ORDER BY stage_order
                """).param("order", id).query((rs, n) -> {
            OffsetDateTime completed = rs.getObject("completed_at", OffsetDateTime.class);
            return new StageView(rs.getString("stage_code"), rs.getInt("stage_order"), rs.getString("title"),
                    rs.getString("status"), internal ? data(rs.getString("data")) : Map.of(),
                    internal ? rs.getString("stage_note") : null, completed == null ? null : completed.toInstant().toString());
        }).list();
        return new WorkflowView(id, instance.category(), instance.status(), instance.currentStage(), instance.version(),
                false, false, stages);
    }

    private static Map<String, Object> data(String raw) {
        try {
            return raw == null ? Map.of() : JSON.readValue(raw, MAP);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Could not read production stage data", ex);
        }
    }

    /** Superadmin and operators see stage details and notes; a school sees titles and statuses only. */
    private static boolean internal() {
        TenantContext ctx = TenantContext.get();
        return ctx.isSuperAdmin() || ctx.isOperations();
    }
}
