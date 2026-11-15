package com.custoking.ims.platformservice.persistence;

/**
 * A WhatsApp onboarding request could not be completed or failed: it does not exist for the school,
 * or it has already left {@code REQUESTED}. Nothing was written when this is thrown.
 */
public class WhatsappOnboardingStateException extends RuntimeException {

    public enum Reason { NOT_FOUND, ALREADY_COMPLETED, ALREADY_FAILED, NOT_OPEN }

    private final Reason reason;

    public WhatsappOnboardingStateException(Reason reason) {
        super(switch (reason) {
            case NOT_FOUND -> "WhatsApp onboarding request not found";
            case ALREADY_COMPLETED -> "This WhatsApp onboarding request is already completed";
            case ALREADY_FAILED -> "This WhatsApp onboarding request is already failed";
            case NOT_OPEN -> "This WhatsApp onboarding request is no longer open";
        });
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
