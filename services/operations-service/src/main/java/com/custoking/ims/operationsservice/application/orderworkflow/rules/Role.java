package com.custoking.ims.operationsservice.application.orderworkflow.rules;

/**
 * The two roles that work an order workflow. School administrators never see one; they keep only the order status view
 * (design D42). "Operator" covers school-wide and item-scoped operators alike: their scope is checked by
 * {@code OrderAccess}, and whether they are the assignee of a stage is checked by the service.
 */
public enum Role { OPERATOR, SUPERADMIN }
