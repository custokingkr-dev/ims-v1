package com.custoking.ims.schoolcoreservice.absentee;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The state machine is pure: (attempts so far, delivery outcome, now) -> next row state. It owns the
 * retry budget and the backoff curve so the worker and the SQL never have to agree on them twice.
 */
class AbsenteeDeliveryStateMachineTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 9, 11, 9, 0, 0, 0, ZoneOffset.UTC);

    private final AbsenteeDeliveryStateMachine machine =
            new AbsenteeDeliveryStateMachine(3, Duration.ofSeconds(30), Duration.ofMinutes(10));

    @Test
    void deliveredOutcomeBecomesSentAndTerminal() {
        var transition = machine.apply(0, DeliveryOutcome.delivered("msg91", "provider-msg-1"), NOW);

        assertThat(transition.status()).isEqualTo(AbsenteeNotificationStatus.SENT);
        assertThat(transition.terminal()).isTrue();
        assertThat(transition.deliveredAt()).isEqualTo(NOW);
        assertThat(transition.nextAttemptAt()).isNull();
        assertThat(transition.deadLetteredAt()).isNull();
    }

    @Test
    void dryRunOutcomeIsTerminalButIsNotRecordedAsDelivered() {
        var transition = machine.apply(0, DeliveryOutcome.dryRun("dry-run"), NOW);

        assertThat(transition.status()).isEqualTo(AbsenteeNotificationStatus.SENT_DRY_RUN);
        assertThat(transition.terminal()).isTrue();
        assertThat(transition.deliveredAt()).isNull();
        assertThat(transition.nextAttemptAt()).isNull();
    }

    @Test
    void suppressedOutcomeIsTerminalWithoutRetry() {
        var transition = machine.apply(1, DeliveryOutcome.suppressed("GUARDIAN_INACTIVE"), NOW);

        assertThat(transition.status()).isEqualTo(AbsenteeNotificationStatus.SUPPRESSED);
        assertThat(transition.terminal()).isTrue();
        assertThat(transition.nextAttemptAt()).isNull();
        assertThat(transition.deadLetteredAt()).isNull();
    }

    @Test
    void transientFailureSchedulesRetryWithExponentialBackoff() {
        var first = machine.apply(0, DeliveryOutcome.transientFailure("platform unavailable"), NOW);
        var second = machine.apply(1, DeliveryOutcome.transientFailure("platform unavailable"), NOW);

        assertThat(first.status()).isEqualTo(AbsenteeNotificationStatus.FAILED);
        assertThat(first.terminal()).isFalse();
        assertThat(first.nextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(second.status()).isEqualTo(AbsenteeNotificationStatus.FAILED);
        assertThat(second.nextAttemptAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void transientFailureOnLastAllowedAttemptDeadLetters() {
        var transition = machine.apply(2, DeliveryOutcome.transientFailure("platform unavailable"), NOW);

        assertThat(transition.status()).isEqualTo(AbsenteeNotificationStatus.DEAD_LETTER);
        assertThat(transition.terminal()).isTrue();
        assertThat(transition.deadLetteredAt()).isEqualTo(NOW);
        assertThat(transition.nextAttemptAt()).isNull();
    }

    @Test
    void permanentFailureDeadLettersImmediately() {
        var transition = machine.apply(0, DeliveryOutcome.permanentFailure("platform dead-lettered event"), NOW);

        assertThat(transition.status()).isEqualTo(AbsenteeNotificationStatus.DEAD_LETTER);
        assertThat(transition.terminal()).isTrue();
        assertThat(transition.deadLetteredAt()).isEqualTo(NOW);
    }

    @Test
    void backoffIsCappedAtTheConfiguredMaximum() {
        assertThat(machine.backoffFor(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(machine.backoffFor(5)).isEqualTo(Duration.ofMinutes(8));
        assertThat(machine.backoffFor(6)).isEqualTo(Duration.ofMinutes(10));
        assertThat(machine.backoffFor(64)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void retryBudgetIsNeverLowerThanOneAttempt() {
        var degenerate = new AbsenteeDeliveryStateMachine(0, Duration.ofSeconds(30), Duration.ofSeconds(30));

        var transition = degenerate.apply(0, DeliveryOutcome.transientFailure("x"), NOW);

        assertThat(transition.status()).isEqualTo(AbsenteeNotificationStatus.DEAD_LETTER);
    }
}
