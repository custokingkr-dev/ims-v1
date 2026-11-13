package com.custoking.ims.operationsservice.application.orderworkflow;

import com.custoking.ims.operationsservice.application.orderworkflow.rules.Role;

/**
 * An order workflow the caller has been cleared to see (or act on), as {@link OrderAccess#require} returns it.
 *
 * @param instanceId   {@code workflow_instances.id} of the order's workflow
 * @param schoolId     the order's school (the tenant key of every child row)
 * @param orderId      the catalogue order id ({@code CK-<n>}), the key in every API path
 * @param categoryCode the catalogue category, e.g. {@code NOTEBOOKS}, {@code FLIERS}
 * @param definitionId the workflow template, e.g. {@code NOTEBOOK_ORDER_TRACKER}
 * @param activeStep   the order of the ACTIVE stage, or null once every stage is completed
 * @param role         the caller's role for this order
 * @param userId       the caller's user id (recorded as author or actor)
 */
public record AccessibleOrder(long instanceId, long schoolId, String orderId, String categoryCode, String definitionId,
                              Integer activeStep, Role role, long userId) {

    public AccessibleOrder {
        if (orderId == null || orderId.isBlank()) throw new IllegalArgumentException("An accessible order needs its order id");
        if (role == null) throw new IllegalArgumentException("An accessible order needs the caller's role");
    }

    public boolean superadmin() { return role == Role.SUPERADMIN; }

    /** Whether every stage of the order is completed. */
    public boolean finished() { return activeStep == null; }
}
