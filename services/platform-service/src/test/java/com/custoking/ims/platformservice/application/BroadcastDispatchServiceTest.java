package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.application.BroadcastRecipientPolicy.Recipient;
import com.custoking.ims.platformservice.persistence.BroadcastDispatchRepository;
import com.custoking.ims.platformservice.persistence.BroadcastDispatchRepository.Broadcast;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BroadcastDispatchServiceTest {
    final UUID id = UUID.randomUUID();
    final BroadcastDispatchRepository repository = mock(BroadcastDispatchRepository.class);
    final BroadcastRecipientPolicy policy = mock(BroadcastRecipientPolicy.class);
    final NotificationDeliveryCommandService delivery = mock(NotificationDeliveryCommandService.class);
    BroadcastDispatchService service;
    @BeforeEach void setup() {
        when(policy.configured()).thenReturn(true); when(delivery.dryRun()).thenReturn(true);
        service = new BroadcastDispatchService(repository, policy, delivery, "DRY_RUN", true);
        when(repository.find(id, true)).thenReturn(broadcast("DRAFT"));
        when(repository.find(id, false)).thenReturn(broadcast("DRAFT"));
    }
    Broadcast broadcast(String status) { return new Broadcast(id, 10L, "Notice", "Message", "ALL_PARENTS", List.of("SMS"), "SCHOOL_NOTICE", status, "DRY_RUN"); }
    Recipient recipient(long student, boolean allowed, String hash) {
        return new Recipient(10, student, "SMS", "broadcast:" + id + ":" + student + ":SMS", allowed,
                allowed ? "ALLOWED" : "SCHOOL_COMMUNICATIONS_NOT_GRANTED", "guardian", "9999999999", hash, Map.of("consentEventId", "consent"));
    }
    @Test void previewCountsUniqueDestinationsAndApprovalBindsExactReviewedEvidence() {
        var recipients = List.of(recipient(1, true, "hash"), recipient(2, true, "hash"), recipient(3, false, "other"));
        when(policy.resolve(10, id, List.of("SMS"), null)).thenReturn(recipients);
        var preview = service.preview(id);
        assertThat(preview).containsEntry("eligible", 1L).containsEntry("duplicate", 1L).containsEntry("suppressed", 1L);
        assertThat(preview.toString()).doesNotContain("9999999999");
        service.approve(id, 5L, (String) preview.get("fingerprint"));
        verify(repository).approve(broadcast("DRAFT"), recipients, 5L);
        verifyNoInteractions(delivery);
    }
    @Test void changedOrUnreviewedAudienceCannotBeApproved() {
        when(policy.resolve(10, id, List.of("SMS"), null)).thenReturn(List.of(recipient(1, true, "hash")));
        String fingerprint = (String) service.preview(id).get("fingerprint");
        when(policy.resolve(10, id, List.of("SMS"), null)).thenReturn(List.of(recipient(1, false, "hash")));
        assertThatThrownBy(() -> service.approve(id, 5L, fingerprint)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("eligibility changed");
        assertThatThrownBy(() -> service.approve(id, 5L, null)).isInstanceOf(ResponseStatusException.class);
        verify(repository, never()).approve(any(), any(), any());
    }
    @Test void zeroEligibleCannotBeApprovedEvenWithFreshPreview() {
        when(policy.resolve(10, id, List.of("SMS"), null)).thenReturn(List.of(recipient(1, false, "hash")));
        String fingerprint = (String) service.preview(id).get("fingerprint");
        assertThatThrownBy(() -> service.approve(id, 5L, fingerprint)).hasMessageContaining("No recipients");
        verify(repository, never()).approve(any(), any(), any());
    }
    @Test void queueRequiresApprovalAndDefaultsOffAndNeverAdvertisesLiveSend() {
        assertThatThrownBy(() -> service.queue(id, 5L)).hasMessageContaining("Preview and approve");
        when(repository.find(id, true)).thenReturn(broadcast("APPROVED"));
        var disabled = new BroadcastDispatchService(repository, policy, delivery, "OFF", true);
        assertThat(disabled.capabilities(true)).containsEntry("canQueue", false).containsEntry("canSend", false);
        assertThatThrownBy(() -> disabled.queue(id, 5L)).hasMessageContaining("off");
        when(delivery.dryRun()).thenReturn(false);
        assertThat(service.capabilities(true)).containsEntry("canQueue", false).containsEntry("canSend", false);
        assertThatThrownBy(() -> service.queue(id, 5L)).hasMessageContaining("Live delivery");
        verify(repository, never()).queue(any(), any(), any());
    }
    @Test void queueAndApprovalReplaysPreserveApprovedManifest() {
        when(repository.find(id, true)).thenReturn(broadcast("QUEUED"));
        when(repository.outcomes(any())).thenReturn(Map.of("status", "QUEUED"));
        assertThat(service.queue(id, 5L)).containsEntry("status", "QUEUED");
        service.approve(id, 5L, "old-fingerprint");
        verify(repository, never()).queue(any(), any(), any());
        verify(repository, never()).approve(any(), any(), any());
        verify(policy, never()).resolve(anyLong(), any(), any(), any());
    }
    @Test void unsupportedLegacyDraftAndMissingPolicyConnectionFailClosed() {
        when(repository.find(id, false)).thenReturn(new Broadcast(id, null, "Legacy", "Message", "ALL", List.of("PUSH"), "UNCLASSIFIED", "DRAFT", null));
        assertThatThrownBy(() -> service.preview(id)).hasMessageContaining("lacks supported recipient policy");
        when(policy.configured()).thenReturn(false);
        assertThat(service.capabilities(true)).containsEntry("canPreview", false).containsEntry("canApprove", false).containsEntry("canQueue", false);
    }
    @Test void dryRunNeedsExplicitVerifiedRequestDrivenWorker() {
        var unverified = new BroadcastDispatchService(repository, policy, delivery, "DRY_RUN", false);
        assertThat(unverified.capabilities(true)).containsEntry("canQueue",false);
        assertThat(unverified.capabilities(true).get("queueUnavailableReason")).asString().contains("worker has not been verified");
    }
}
