package com.custoking.ims.schoolcoreservice.persistence;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class GuardianCommunicationPolicyTest {

    private final OffsetDateTime now = OffsetDateTime.parse("2026-08-24T10:00:00Z");

    @Test
    void allowsVerifiedPrimaryGuardianWithPreferenceAndCurrentGuardianSpecificGrant() {
        var decision = GuardianCommunicationPolicy.decide(snapshot(), "whatsapp", now, 10L, 1L);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.destination()).isEqualTo("919999999999");
        assertThat(decision.evidence("request-1"))
                .containsEntry("purpose", "SCHOOL_COMMUNICATIONS")
                .containsEntry("guardianId", "guardian-1")
                .containsEntry("consentEventId", "consent-1")
                .containsEntry("consentNoticeVersion", "notice-v1")
                .containsEntry("schoolId", 10L)
                .containsEntry("studentId", 1L)
                .containsEntry("sourceEventId", "request-1")
                .containsEntry("policyVersion", "guardian-communications.v2");
    }

    @Test
    void failsClosedWhenStudentWideConsentHasNoGuardianSemantics() {
        var source = snapshot();
        var decision = GuardianCommunicationPolicy.decide(new GuardianCommunicationPolicy.Snapshot(
                source.guardianId(), source.guardianStatus(), source.receivesNotifications(),
                source.contactVerifiedAt(), source.phone(), source.email(), source.consentEventId(),
                null, source.consentStatus(), source.consentLawfulBasis(), source.consentNoticeVersion(),
                source.consentExpiresAt()), "SMS", now, 10L, 1L);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("CONSENT_GUARDIAN_UNSPECIFIED");
    }

    @Test
    void failsClosedWhenGuardianPreferenceIsDisabled() {
        var source = snapshot();
        var decision = GuardianCommunicationPolicy.decide(new GuardianCommunicationPolicy.Snapshot(
                source.guardianId(), source.guardianStatus(), false, source.contactVerifiedAt(),
                source.phone(), source.email(), source.consentEventId(), source.consentGuardianId(),
                source.consentStatus(), source.consentLawfulBasis(), source.consentNoticeVersion(),
                source.consentExpiresAt()), "SMS", now, 10L, 1L);

        assertThat(decision.reason()).isEqualTo("NOTIFICATION_PREFERENCE_DISABLED");
    }

    @Test
    void failsClosedWhenContactIsUnverifiedOrGrantExpired() {
        var source = snapshot();
        var unverified = GuardianCommunicationPolicy.decide(new GuardianCommunicationPolicy.Snapshot(
                source.guardianId(), source.guardianStatus(), true, null, source.phone(), source.email(),
                source.consentEventId(), source.consentGuardianId(), source.consentStatus(),
                source.consentLawfulBasis(), source.consentNoticeVersion(), null), "SMS", now, 10L, 1L);
        var expired = GuardianCommunicationPolicy.decide(new GuardianCommunicationPolicy.Snapshot(
                source.guardianId(), source.guardianStatus(), true, source.contactVerifiedAt(),
                source.phone(), source.email(), source.consentEventId(), source.consentGuardianId(),
                source.consentStatus(), source.consentLawfulBasis(), source.consentNoticeVersion(), now),
                "SMS", now, 10L, 1L);

        assertThat(unverified.reason()).isEqualTo("CONTACT_NOT_VERIFIED");
        assertThat(expired.reason()).isEqualTo("SCHOOL_COMMUNICATIONS_EXPIRED");
    }

    @Test
    void failsClosedWhenLegalObligationSemanticsHaveNotBeenDefined() {
        var source = snapshot();
        var decision = GuardianCommunicationPolicy.decide(new GuardianCommunicationPolicy.Snapshot(
                source.guardianId(), source.guardianStatus(), true, source.contactVerifiedAt(),
                source.phone(), source.email(), source.consentEventId(), source.consentGuardianId(),
                source.consentStatus(), "LEGAL_OBLIGATION", source.consentNoticeVersion(),
                source.consentExpiresAt()), "SMS", now, 10L, 1L);

        assertThat(decision.reason()).isEqualTo("LAWFUL_BASIS_NOT_CONSENT");
    }

    private GuardianCommunicationPolicy.Snapshot snapshot() {
        return new GuardianCommunicationPolicy.Snapshot(
                "guardian-1", "ACTIVE", true, now.minusDays(2),
                "919999999999", "guardian@example.test", "consent-1", "guardian-1",
                "GRANTED", "CONSENT", "notice-v1", now.plusDays(30));
    }
}
