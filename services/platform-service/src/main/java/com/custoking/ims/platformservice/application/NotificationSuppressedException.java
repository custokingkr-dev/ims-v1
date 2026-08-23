package com.custoking.ims.platformservice.application;

/** A terminal policy decision. It must be acknowledged, audited, and never retried. */
public final class NotificationSuppressedException extends RuntimeException {

    private final String reasonCode;

    public NotificationSuppressedException(String reasonCode) {
        super("Notification suppressed by communication policy: " + reasonCode);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
