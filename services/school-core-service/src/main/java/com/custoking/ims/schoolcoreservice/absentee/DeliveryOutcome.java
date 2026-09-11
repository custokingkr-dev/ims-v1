package com.custoking.ims.schoolcoreservice.absentee;

/**
 * What a single delivery attempt produced, independent of how it was attempted (dry-run, HTTP to
 * platform-service, ...). The gateway never throws for expected failures; it returns one of these so
 * the worker's state handling has a single shape.
 */
public record DeliveryOutcome(Kind kind, String error, String provider, String providerMessageId) {

    public enum Kind {
        /** The provider accepted the message for a real, non-dry-run send. */
        DELIVERED,
        /** Every step ran except the real send. */
        DRY_RUN,
        /** Communication policy denied dispatch. Terminal; never retried. */
        SUPPRESSED,
        /** Something that may succeed later (peer unavailable, 5xx, timeout). */
        TRANSIENT_FAILURE,
        /** Something that will not succeed by retrying (peer dead-lettered the event). */
        PERMANENT_FAILURE
    }

    public static DeliveryOutcome delivered(String provider, String providerMessageId) {
        return new DeliveryOutcome(Kind.DELIVERED, null, provider, providerMessageId);
    }

    public static DeliveryOutcome dryRun(String provider) {
        return new DeliveryOutcome(Kind.DRY_RUN, null, provider, null);
    }

    public static DeliveryOutcome suppressed(String reason) {
        return new DeliveryOutcome(Kind.SUPPRESSED, reason, null, null);
    }

    public static DeliveryOutcome transientFailure(String error) {
        return new DeliveryOutcome(Kind.TRANSIENT_FAILURE, error, null, null);
    }

    public static DeliveryOutcome permanentFailure(String error) {
        return new DeliveryOutcome(Kind.PERMANENT_FAILURE, error, null, null);
    }
}
