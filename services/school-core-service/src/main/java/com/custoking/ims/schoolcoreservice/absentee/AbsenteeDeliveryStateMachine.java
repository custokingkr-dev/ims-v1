package com.custoking.ims.schoolcoreservice.absentee;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Pure transition function for {@link AbsenteeNotificationStatus}: given how many attempts a row has
 * already consumed and what the latest attempt produced, decide the next persisted state. Mirrors the
 * retry/backoff shape of {@code OutboxRelay} and platform-service's {@code NotificationInboxProcessor}
 * (exponential from an initial delay, capped) so operators see one curve across the pipeline.
 */
public final class AbsenteeDeliveryStateMachine {

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;

    public AbsenteeDeliveryStateMachine(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialBackoff = initialBackoff == null || initialBackoff.isNegative() || initialBackoff.isZero()
                ? Duration.ofSeconds(30) : initialBackoff;
        this.maxBackoff = maxBackoff == null || maxBackoff.compareTo(this.initialBackoff) < 0
                ? this.initialBackoff : maxBackoff;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * @param attemptsBefore attempts already recorded on the row before this one
     * @param outcome        what this attempt produced
     * @param now            the attempt timestamp
     */
    public Transition apply(int attemptsBefore, DeliveryOutcome outcome, OffsetDateTime now) {
        int attempts = Math.max(0, attemptsBefore) + 1;
        return switch (outcome.kind()) {
            case DELIVERED -> new Transition(AbsenteeNotificationStatus.SENT, attempts, null, now, null, true);
            case DRY_RUN -> new Transition(AbsenteeNotificationStatus.SENT_DRY_RUN, attempts, null, null, null, true);
            case SUPPRESSED -> new Transition(AbsenteeNotificationStatus.SUPPRESSED, attempts, null, null, null, true);
            case PERMANENT_FAILURE -> new Transition(AbsenteeNotificationStatus.DEAD_LETTER, attempts, null, null, now, true);
            case TRANSIENT_FAILURE -> attempts >= maxAttempts
                    ? new Transition(AbsenteeNotificationStatus.DEAD_LETTER, attempts, null, null, now, true)
                    : new Transition(AbsenteeNotificationStatus.FAILED, attempts, now.plus(backoffFor(attempts)), null, null, false);
        };
    }

    /** Delay before the attempt following attempt number {@code attempts} (1-based). */
    public Duration backoffFor(int attempts) {
        long multiplier = 1L << Math.min(20, Math.max(0, attempts - 1));
        long seconds;
        try {
            seconds = Math.multiplyExact(initialBackoff.toSeconds(), multiplier);
        } catch (ArithmeticException ignored) {
            seconds = maxBackoff.toSeconds();
        }
        return Duration.ofSeconds(Math.min(maxBackoff.toSeconds(), Math.max(1, seconds)));
    }

    /**
     * @param status        next {@link AbsenteeNotificationStatus}
     * @param attempts      attempts recorded after this one
     * @param nextAttemptAt when the row becomes claimable again ({@code null} unless {@code FAILED})
     * @param deliveredAt   set only for a real send
     * @param deadLetteredAt set only when the row is given up on
     * @param terminal      whether the worker will ever claim the row again
     */
    public record Transition(
            String status,
            int attempts,
            OffsetDateTime nextAttemptAt,
            OffsetDateTime deliveredAt,
            OffsetDateTime deadLetteredAt,
            boolean terminal) {
    }
}
