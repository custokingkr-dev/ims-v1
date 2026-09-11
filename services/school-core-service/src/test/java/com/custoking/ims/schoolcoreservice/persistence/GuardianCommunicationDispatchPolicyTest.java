package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.absentee.DispatchDecision;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dispatch-time adapter must hand the worker exactly what the queue-time producer recorded and
 * what platform-service's guard will verify: same guardian, same destination hash, same evidence map.
 */
class GuardianCommunicationDispatchPolicyTest {

    private final OffsetDateTime now = OffsetDateTime.parse("2026-09-11T10:00:00Z");

    @Test
    void allowedDecisionMapsBindingAndEvidenceWithoutTranslationLoss() {
        var source = GuardianCommunicationPolicy.decide(snapshot(), "WHATSAPP", now, 10L, 1L);

        DispatchDecision mapped = GuardianCommunicationDispatchPolicy.toDispatchDecision(source, "school-core:absentee:row-1");

        assertThat(mapped.allowed()).isTrue();
        assertThat(mapped.guardianId()).isEqualTo("guardian-1");
        assertThat(mapped.destination()).isEqualTo("919999999999");
        assertThat(mapped.channel()).isEqualTo("WHATSAPP");
        assertThat(mapped.consentEventId()).isEqualTo("consent-1");
        assertThat(mapped.consentNoticeVersion()).isEqualTo("notice-v1");
        assertThat(mapped.evaluatedAt()).isEqualTo(now);
        assertThat(mapped.expiresAt()).isEqualTo(now.plusMinutes(2));
        assertThat(mapped.destinationSha256())
                .isEqualTo(GuardianCommunicationPolicy.destinationSha256("WHATSAPP", "919999999999"));
        assertThat(mapped.evidence()).isEqualTo(source.evidence("school-core:absentee:row-1"));
    }

    @Test
    void deniedDecisionKeepsItsReasonCode() {
        var source = GuardianCommunicationPolicy.decide(null, "WHATSAPP", now, 10L, 1L);

        DispatchDecision mapped = GuardianCommunicationDispatchPolicy.toDispatchDecision(source, "school-core:absentee:row-1");

        assertThat(mapped.allowed()).isFalse();
        assertThat(mapped.reason()).isEqualTo("STUDENT_NOT_FOUND");
    }

    private GuardianCommunicationPolicy.Snapshot snapshot() {
        return new GuardianCommunicationPolicy.Snapshot(
                "guardian-1", "ACTIVE", true, now.minusDays(1), "919999999999", "guardian@example.com",
                "consent-1", "guardian-1", "GRANTED", "CONSENT", "notice-v1", null);
    }
}
