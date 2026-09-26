package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.persistence.BroadcastDispatchRepository;
import com.custoking.ims.platformservice.persistence.BroadcastDispatchRepository.Broadcast;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BroadcastLiveDispatchServiceTest {
    final UUID id=UUID.randomUUID();
    final String hash="a".repeat(64);
    final BroadcastDispatchRepository repository=mock(BroadcastDispatchRepository.class);
    final BroadcastRecipientPolicy policy=mock(BroadcastRecipientPolicy.class);
    final NotificationDeliveryCommandService generic=mock(NotificationDeliveryCommandService.class);
    final BroadcastLiveProvider provider=mock(BroadcastLiveProvider.class);
    final LiveBroadcastConfiguration config=new LiveBroadcastConfiguration(true,"EMAIL",true,"10",hash,"w".repeat(32));
    BroadcastDispatchService service;
    @BeforeEach void setup() {
        when(policy.configured()).thenReturn(true); when(provider.configured()).thenReturn(true);
        service=new BroadcastDispatchService(repository,policy,generic,"LIVE",true,config,provider);
        when(repository.find(id,false)).thenReturn(broadcast("DRAFT",null,"DRY_RUN",null));
        when(repository.find(id,true)).thenAnswer(call -> repository.find(id,false));
        when(policy.resolve(10,id,List.of("EMAIL"),null)).thenReturn(List.of(recipient(true,hash)));
        when(repository.outcomes(any())).thenReturn(Map.of("mode","LIVE"));
    }
    Broadcast broadcast(String state,String dispatch,String approval,String fingerprint) {
        return new Broadcast(id,10L,"School notice","Reviewed message","ALL_PARENTS",List.of("EMAIL"),"SCHOOL_NOTICE",state,dispatch,approval,fingerprint);
    }
    BroadcastRecipientPolicy.Recipient recipient(boolean allowed,String destination) {
        return new BroadcastRecipientPolicy.Recipient(10,1,"EMAIL","broadcast:"+id+":1:EMAIL",allowed,
                allowed?"ALLOWED":"SCHOOL_COMMUNICATIONS_NOT_GRANTED","guardian","recipient@example.test",destination,Map.of("consentEventId","consent-1"));
    }
    String reviewedApproval() {
        String fingerprint=(String) service.preview(id).get("fingerprint");
        when(repository.find(id,false)).thenReturn(broadcast("APPROVED",null,"LIVE",fingerprint));
        return fingerprint;
    }
    @Test void capabilityNeedsAdmittedSchoolAndAllReadinessGates() {
        assertThat(service.capabilities(true)).containsEntry("canSend",false).containsEntry("canQueue",false);
        assertThat(service.capabilities(true,11L)).containsEntry("canSend",false);
        assertThat(service.capabilities(false,10L)).containsEntry("canSend",false);
        assertThat(service.capabilities(true,10L)).containsEntry("mode","LIVE").containsEntry("canSend",true).containsEntry("supportedChannels",List.of("EMAIL"));
        when(provider.configured()).thenReturn(false);
        assertThat(service.capabilities(true,10L)).containsEntry("canSend",false);
        verifyNoInteractions(generic);
    }
    @Test void approvalRecordsLiveMeaningAndReviewedFingerprintWithoutProviderCall() {
        String fingerprint=(String) service.preview(id).get("fingerprint");
        service.approve(id,5L,fingerprint);
        verify(repository).approve(any(),any(),eq(5L),eq("LIVE"),eq(fingerprint));
        verify(provider,never()).submit(any());
    }
    @Test void explicitConfirmationMustMatchApprovedEvidence() {
        String fingerprint=reviewedApproval();
        assertThatThrownBy(() -> service.queue(id,5L)).hasMessageContaining("Explicit live confirmation");
        assertThatThrownBy(() -> service.queue(id,5L,"DRY_RUN",fingerprint)).hasMessageContaining("Explicit live confirmation");
        assertThatThrownBy(() -> service.queue(id,5L,"LIVE","b".repeat(64))).hasMessageContaining("Explicit live confirmation");
        service.queue(id,5L,"LIVE",fingerprint);
        verify(repository).queue(any(),eq("LIVE"),eq(5L));
        verify(provider,never()).submit(any());
    }
    @Test void priorDryRunApprovalCannotBecomeLive() {
        String fingerprint=reviewedApproval();
        when(repository.find(id,false)).thenReturn(broadcast("APPROVED",null,"DRY_RUN",fingerprint));
        assertThatThrownBy(() -> service.queue(id,5L,"LIVE",fingerprint)).hasMessageContaining("live-approved");
        verify(repository,never()).queue(any(),any(),any());
    }
    @Test void consentWithdrawalBetweenApprovalAndQueueRequiresNewReview() {
        String fingerprint=reviewedApproval();
        when(policy.resolve(10,id,List.of("EMAIL"),null)).thenReturn(List.of(recipient(false,hash)));
        assertThatThrownBy(() -> service.queue(id,5L,"LIVE",fingerprint)).hasMessageContaining("eligibility changed");
        verify(repository,never()).queue(any(),any(),any());
    }
    @Test void recipientsOutsideAllowlistAreSuppressedInReviewedManifest() {
        when(policy.resolve(10,id,List.of("EMAIL"),null)).thenReturn(List.of(recipient(true,"b".repeat(64))));
        var preview=service.preview(id);
        assertThat(preview).containsEntry("eligible",0L).containsEntry("suppressed",1L);
        assertThat(preview.toString()).contains("LIVE_RECIPIENT_NOT_ADMITTED").doesNotContain("recipient@example.test");
        assertThatThrownBy(() -> service.approve(id,5L,(String)preview.get("fingerprint"))).hasMessageContaining("No recipients");
    }
    @Test void replayReturnsExistingOutcomeAndDoesNotQueueOrResolveAgain() {
        String fingerprint=reviewedApproval(); clearInvocations(policy);
        when(repository.find(id,false)).thenReturn(broadcast("NEEDS_RECONCILIATION","LIVE","LIVE",fingerprint));
        assertThat(service.queue(id,5L,"LIVE",fingerprint)).containsEntry("mode","LIVE");
        verify(repository,never()).queue(any(),any(),any());
        verify(policy,never()).resolve(anyLong(),any(),any(),any());
    }
    @Test void existingDispatchCannotChangeModes() {
        String fingerprint=reviewedApproval();
        when(repository.find(id,false)).thenReturn(broadcast("DRY_RUN_COMPLETE","DRY_RUN","LIVE",fingerprint));
        assertThatThrownBy(() -> service.queue(id,5L,"LIVE",fingerprint)).hasMessageContaining("cannot change its mode");
    }
    @Test void malformedOrWildcardAllowlistAndDisabledSwitchFailClosed() {
        assertThat(new LiveBroadcastConfiguration(true,"EMAIL",true,"*",hash,"w".repeat(32)).configured()).isFalse();
        assertThat(new LiveBroadcastConfiguration(true,"EMAIL",true,"10","*","w".repeat(32)).configured()).isFalse();
        assertThat(new LiveBroadcastConfiguration(true,"SMS",true,"10",hash,"w".repeat(32)).configured()).isFalse();
        assertThat(new LiveBroadcastConfiguration(false,"EMAIL",true,"10",hash,"w".repeat(32)).schoolAllowed(10)).isFalse();
        assertThat(new LiveBroadcastConfiguration(true,"EMAIL",false,"10",hash,"w".repeat(32)).configured()).isFalse();
    }
}
