package com.custoking.ims.common.domain;

public enum FirefightingRequestStatus {
    DRAFT,
    SUBMITTED,
    AWAITING_ADMIN_APPROVAL,
    APPROVED,
    QUOTATION_ADDED,
    FULFILLED_BY_CUSTOKING,
    REJECTED,
    CANCELLED;

    public boolean canTransitionTo(FirefightingRequestStatus newStatus) {
        return switch (this) {
            case DRAFT -> newStatus == SUBMITTED || newStatus == CANCELLED;
            case SUBMITTED -> newStatus == AWAITING_ADMIN_APPROVAL || newStatus == CANCELLED;
            case AWAITING_ADMIN_APPROVAL -> newStatus == APPROVED || newStatus == REJECTED || newStatus == CANCELLED;
            case APPROVED -> newStatus == QUOTATION_ADDED || newStatus == CANCELLED;
            case QUOTATION_ADDED -> newStatus == FULFILLED_BY_CUSTOKING || newStatus == CANCELLED;
            case FULFILLED_BY_CUSTOKING, REJECTED, CANCELLED -> false; // Terminal states
        };
    }

    public boolean canBeApprovedByAdmin() {
        return this == AWAITING_ADMIN_APPROVAL;
    }

    public boolean canBeFulfilledBySuperadmin() {
        return this == QUOTATION_ADDED;
    }
}