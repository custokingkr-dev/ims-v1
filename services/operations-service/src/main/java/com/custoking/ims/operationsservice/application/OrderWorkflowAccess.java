package com.custoking.ims.operationsservice.application;

import com.custoking.ims.operationsservice.application.orderworkflow.AccessibleOrder;
import com.custoking.ims.operationsservice.application.orderworkflow.OrderAccess;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.Role;
import com.custoking.ims.operationsservice.persistence.OrderWorkflowRepository;
import com.custoking.ims.operationsservice.persistence.OrderWorkflowRepository.StepRow;
import com.custoking.ims.operationsservice.persistence.OrderWorkflowRepository.TrackerRow;
import com.custoking.ims.operationsservice.security.TenantContext;
import com.custoking.ims.operationsservice.security.TenantScope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * The read and act rules of the order workflows (design §3.5, D42), in one place for every feature that touches an order:
 * the order workflow service, and the call log, activity, notes and group-link features through {@link OrderAccess}.
 * <ul>
 *   <li>Superadmin reads and acts on every order.</li>
 *   <li>An operator reads with {@code notebook-tracker:read} and acts with {@code notebook-tracker:act}, and only on orders
 *       of a school and category their assignments cover: a school-wide assignment covers every item there, an item
 *       scope only its own category.</li>
 *   <li>Everyone else (school administrators included) is refused; they keep only the order status view.</li>
 * </ul>
 * An order outside the operator's scope answers 404, exactly as one that has no workflow, so the answer reveals nothing.
 * Whether the caller is the assignee of a particular stage is the acting feature's own check.
 *
 * <p>This is its own small bean rather than part of {@link OrderWorkflowService}: the call log and group-link features
 * need it, and the service needs them (callbacks, resolved groups), so keeping it apart keeps the beans free of cycles.
 */
@Component
public class OrderWorkflowAccess implements OrderAccess {
    static final String READ = "notebook-tracker:read";
    static final String ACT = "notebook-tracker:act";
    static final String STAFF_ONLY = "Order workflows are for superadmin and operators only";

    private final OrderWorkflowRepository repo;

    public OrderWorkflowAccess(OrderWorkflowRepository repo) {
        this.repo = repo;
    }

    /** An order the caller has been cleared for: their role and id, and the order's tracker row as read (or locked). */
    record Cleared(Role role, long userId, TrackerRow tracker) { }

    /**
     * Inside the caller's transaction: applies the operator's row-level scope (harmless when it is already applied), then
     * finds the order and checks the caller may see or act on it.
     */
    @Override
    public AccessibleOrder require(String orderId, boolean write) {
        Cleared cleared = clear(orderId, write, false);
        TrackerRow t = cleared.tracker();
        Integer active = repo.steps(t.instanceId()).stream().filter(row -> "ACTIVE".equals(row.status()))
                .map(StepRow::stepOrder).findFirst().orElse(null);
        return new AccessibleOrder(t.instanceId(), t.schoolId(), t.orderId(), t.categoryCode(), t.definitionId(), active,
                cleared.role(), cleared.userId());
    }

    /**
     * {@link #require} for the order workflow service, which goes on to work with the tracker row: the role is checked
     * before anything is read (a school administrator gets 403 whatever the order), then the row is read, locked with
     * {@code lock}, and the order's (school, category) pair is checked against the operator's scope (404 outside it).
     */
    Cleared clear(String orderId, boolean write, boolean lock) {
        Role role = role(write);
        repo.applyOperatorScope();
        TrackerRow t = repo.findByOrder(orderId, lock).orElseThrow(OrderWorkflowAccess::notFound);
        requireScope(t.schoolId(), t.categoryCode());
        return new Cleared(role, TenantContext.get().userId(), t);
    }

    /** The caller's role for a read or, with {@code write}, an action; anyone who is not superadmin or an operator is refused. */
    static Role role(boolean write) {
        return role(write, STAFF_ONLY);
    }

    static Role role(boolean write, String staffOnlyMessage) {
        TenantContext ctx = TenantContext.get();
        if (ctx.userId() == null) throw forbidden("Authenticated user required");
        if (ctx.isSuperAdmin()) return Role.SUPERADMIN;
        if (!ctx.isOperations()) throw forbidden(staffOnlyMessage);
        TenantScope.requirePermission(write ? ACT : READ);
        return Role.OPERATOR;
    }

    /** Whether the caller may act (superadmin, or an operator holding the act permission). */
    static boolean canAct() {
        TenantContext ctx = TenantContext.get();
        return ctx.isSuperAdmin() || (ctx.isOperations() && ctx.hasPermission(ACT));
    }

    /**
     * Row-level security admits every school where the operator works any item, and also the request's own school id; the
     * order's (school, category) pair is what decides. Superadmin sees every order.
     */
    static void requireScope(long schoolId, String categoryCode) {
        TenantContext ctx = TenantContext.get();
        if (ctx.isSuperAdmin()) return;
        if (!ctx.operatorMayWork(schoolId, categoryCode)) throw notFound();
    }

    static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Order workflow not found");
    }

    static ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
}
