package com.custoking.ims.operationsservice.application.orderworkflow;

/**
 * The one gate every order-workflow feature passes before touching an order (seam of design §3.4 and §5.2). The order
 * workflow service implements it; the call log, activity, notes and group-link features use it, so the read and act
 * rules of design §3.5 and D42 live in one place.
 *
 * <p>{@code read} means superadmin, or an operator holding {@code notebook-tracker:read} whose scope covers the order's
 * school <em>and</em> category. {@code write} additionally needs {@code notebook-tracker:act}. Whether the caller is the
 * assignee of a particular stage is <em>not</em> checked here: actions that need it check it themselves, because "anyone
 * who can work the order" (log a call, add a note) is exactly {@code require(orderId, true)}.
 */
public interface OrderAccess {

    /**
     * Resolves the caller's access to an order's workflow inside the current transaction (the caller's tenant scope is
     * already applied to the connection).
     *
     * @param orderId the catalogue order id ({@code CK-<n>})
     * @param write   true for an action that changes the order, false for a read
     * @return the order as the caller may see it
     * @throws org.springframework.web.server.ResponseStatusException 404 when the order has no workflow or is outside the
     *         caller's school and category scope, and 403 when the caller lacks the read or act permission or is a school
     *         administrator
     */
    AccessibleOrder require(String orderId, boolean write);
}
