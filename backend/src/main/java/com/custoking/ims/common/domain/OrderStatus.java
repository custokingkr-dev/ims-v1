package com.custoking.ims.common.domain;

public enum OrderStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    IN_PROGRESS,
    READY_FOR_DELIVERY,
    DELIVERED,
    CANCELLED;

    public boolean canTransitionTo(OrderStatus newStatus) {
        return switch (this) {
            case DRAFT -> newStatus == SUBMITTED || newStatus == CANCELLED;
            case SUBMITTED -> newStatus == APPROVED || newStatus == CANCELLED;
            case APPROVED -> newStatus == IN_PROGRESS || newStatus == CANCELLED;
            case IN_PROGRESS -> newStatus == READY_FOR_DELIVERY || newStatus == CANCELLED;
            case READY_FOR_DELIVERY -> newStatus == DELIVERED;
            case DELIVERED, CANCELLED -> false; // Terminal states
        };
    }
}