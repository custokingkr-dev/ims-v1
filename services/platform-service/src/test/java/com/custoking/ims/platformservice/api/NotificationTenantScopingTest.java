package com.custoking.ims.platformservice.api;

import com.custoking.ims.platformservice.application.SenderProfile;
import com.custoking.ims.platformservice.persistence.NotificationBroadcastCommandRepository;
import com.custoking.ims.platformservice.application.BroadcastDispatchService;
import com.custoking.ims.platformservice.persistence.SenderProfileRepository;
import com.custoking.ims.platformservice.persistence.WhatsappOnboardingStateException;
import com.custoking.ims.platformservice.persistence.WhatsappOnboardingStateException.Reason;
import com.custoking.ims.platformservice.security.TenantContext;
import com.custoking.ims.platformservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies tenant-scoping guards on notification controllers using a real TenantContextFilter
 * in a standalone MockMvc setup.
 *
 * Judgment calls documented inline:
 * - NotificationBroadcastCommandController list is tenant-scoped: school admins can read their
 *   own school's broadcasts, cross-school reads are fail-closed.
 * - SenderProfileController: ALL guards are UNCONDITIONAL. The Msg91 delivery path reads
 *   sender profiles in-process via SenderProfileRepository — there is no legitimate
 *   header-less HTTP caller of this controller. A header-less HTTP request is fail-closed → 403.
 */
class NotificationTenantScopingTest {
    private final BroadcastDispatchService dispatch = mock(BroadcastDispatchService.class);

    private static final String TOKEN = "notif-token";

    private final NotificationBroadcastCommandRepository broadcasts =
            mock(NotificationBroadcastCommandRepository.class);
    private final SenderProfileRepository senderProfiles =
            mock(SenderProfileRepository.class);

    private static final SenderProfile STUB_PROFILE = new SenderProfile(
            null, 10L, "Test Profile", null, null, null, null,
            null, null, null, null, "en", null, null, null);

    private final MockMvc broadcastMvc = MockMvcBuilders
            .standaloneSetup(new NotificationBroadcastCommandController(broadcasts, TOKEN, dispatch))
            .addFilters(new TenantContextFilter())
            .build();

    private final MockMvc senderProfileMvc = MockMvcBuilders
            .standaloneSetup(new SenderProfileController(senderProfiles, TOKEN))
            .setControllerAdvice(new ValidationExceptionHandler())
            .addFilters(new TenantContextFilter())
            .build();

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    // --- broadcast endpoint: tenant-scoped list ---

    @Test
    void schoolAdmin_canListOwnSchoolBroadcasts() throws Exception {
        when(broadcasts.list(eq(10L), isNull(), anyInt())).thenReturn(List.of());

        broadcastMvc.perform(get("/api/v1/notifications/broadcasts")
                        .param("schoolId", "10")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "notification:read"))
                .andExpect(status().isOk());

        verify(broadcasts).list(eq(10L), isNull(), anyInt());
    }

    @Test
    void schoolAdmin_withoutNotificationReadCannotListOwnSchoolBroadcasts() throws Exception {
        broadcastMvc.perform(get("/api/v1/notifications/broadcasts")
                        .param("schoolId", "10")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "notification:send"))
                .andExpect(status().isForbidden());

        verify(broadcasts, never()).list(any(), any(), anyInt());
    }

    @Test
    void schoolAdmin_isForbiddenOnCrossSchoolBroadcastList() throws Exception {
        broadcastMvc.perform(get("/api/v1/notifications/broadcasts")
                        .param("schoolId", "99")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "notification:read"))
                .andExpect(status().isForbidden());

        verify(broadcasts, never()).list(any(), any(), anyInt());
    }

    @Test
    void unauthenticated_isForbiddenOnBroadcastList() throws Exception {
        // No role header → empty TenantContext → not superadmin → 403
        broadcastMvc.perform(get("/api/v1/notifications/broadcasts")
                        .header("X-Notification-Service-Token", TOKEN))
                .andExpect(status().isForbidden());

        verify(broadcasts, never()).list(any(), any(), anyInt());
    }

    // --- broadcast endpoint: superadmin allowed ---

    @Test
    void superadmin_canListBroadcasts() throws Exception {
        when(broadcasts.list(isNull(), isNull(), anyInt())).thenReturn(List.of());

        broadcastMvc.perform(get("/api/v1/notifications/broadcasts")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());

        verify(broadcasts).list(isNull(), isNull(), anyInt());
    }

    // --- SenderProfileController: cross-tenant read blocked for school admin ---

    @Test
    void schoolAdmin_isForbiddenReadingAnotherSchoolProfile() throws Exception {
        // ADMIN for school 10 tries to read school 99's sender profile → 403
        senderProfileMvc.perform(get("/api/v1/notifications/sender-profiles/schools/99")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "notification:read"))
                .andExpect(status().isForbidden());

        verify(senderProfiles, never()).resolve(any());
    }

    // --- SenderProfileController: school admin may read own school profile ---

    @Test
    void schoolAdmin_canReadOwnSchoolProfile() throws Exception {
        when(senderProfiles.resolve(10L)).thenReturn(STUB_PROFILE);

        senderProfileMvc.perform(get("/api/v1/notifications/sender-profiles/schools/10")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "notification:read"))
                .andExpect(status().isOk());

        verify(senderProfiles).resolve(10L);
    }

    @Test
    void schoolAdmin_withoutNotificationReadCannotReadOwnSchoolProfile() throws Exception {
        senderProfileMvc.perform(get("/api/v1/notifications/sender-profiles/schools/10")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "notification:send"))
                .andExpect(status().isForbidden());

        verify(senderProfiles, never()).resolve(any());
    }

    // --- SenderProfileController: superadmin may read any school profile ---

    @Test
    void superadmin_canReadAnySchoolProfile() throws Exception {
        when(senderProfiles.resolve(99L)).thenReturn(STUB_PROFILE);

        senderProfileMvc.perform(get("/api/v1/notifications/sender-profiles/schools/99")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());

        verify(senderProfiles).resolve(99L);
    }

    // --- SenderProfileController: header-less HTTP is fail-closed (no longer bypasses guard) ---

    @Test
    void headerlessHttp_isForbiddenReadingSchoolProfile() throws Exception {
        // Guard is unconditional. The delivery path reads profiles in-process, not over HTTP.
        // A header-less HTTP caller has no tenant identity → 403 (fail-closed).
        senderProfileMvc.perform(get("/api/v1/notifications/sender-profiles/schools/10")
                        .header("X-Notification-Service-Token", TOKEN))
                .andExpect(status().isForbidden());

        verify(senderProfiles, never()).resolve(any());
    }

    // --- SenderProfileController: PUT /schools/{schoolId} — superadmin-only write ---

    @Test
    void schoolAdmin_isForbiddenUpdatingOwnSchoolSenderProfile() throws Exception {
        // Tightened: even an admin writing their own school's sender credentials is now blocked.
        senderProfileMvc.perform(put("/api/v1/notifications/sender-profiles/schools/10")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "notification:send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authKey\":\"key\"}"))
                .andExpect(status().isForbidden());

        verify(senderProfiles, never()).upsert(any(), any());
    }

    @Test
    void superadmin_canUpdateSchoolSenderProfile() throws Exception {
        when(senderProfiles.upsert(eq(10L), any())).thenReturn(STUB_PROFILE);

        senderProfileMvc.perform(put("/api/v1/notifications/sender-profiles/schools/10")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authKey\":\"key\"}"))
                .andExpect(status().isOk());

        verify(senderProfiles).upsert(eq(10L), any());
    }

    @Test
    void headerlessHttp_isForbiddenUpdatingSchoolSenderProfile() throws Exception {
        // Guard is unconditional — requireSuperAdmin() runs for every HTTP caller.
        // No tenant identity → 403 (fail-closed).
        senderProfileMvc.perform(put("/api/v1/notifications/sender-profiles/schools/10")
                        .header("X-Notification-Service-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authKey\":\"key\"}"))
                .andExpect(status().isForbidden());

        verify(senderProfiles, never()).upsert(any(), any());
    }

    // --- SenderProfileController: platform-default endpoint blocked for non-superadmin user ---

    @Test
    void schoolAdmin_isForbiddenReadingDefaultProfile() throws Exception {
        senderProfileMvc.perform(get("/api/v1/notifications/sender-profiles/default")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "notification:read"))
                .andExpect(status().isForbidden());

        verify(senderProfiles, never()).defaultProfile();
    }

    // --- SenderProfileController: platform-default endpoint — header-less HTTP is fail-closed ---

    @Test
    void headerlessHttp_isForbiddenReadingDefaultProfile() throws Exception {
        // Guard is unconditional — requireSuperAdmin() runs for every HTTP caller.
        // No tenant identity → 403 (fail-closed). Delivery reads profiles in-process.
        senderProfileMvc.perform(get("/api/v1/notifications/sender-profiles/default")
                        .header("X-Notification-Service-Token", TOKEN))
                .andExpect(status().isForbidden());

        verify(senderProfiles, never()).defaultProfile();
    }

    // --- SenderProfileController: completing/failing WhatsApp onboarding — superadmin-only ---
    // Completion writes the school's WhatsApp sender details, which PUT /schools/{schoolId} keeps
    // superadmin-only; a school admin's notification:send must not reach the same write.

    private static final UUID SESSION = UUID.fromString("d0000000-0000-0000-0000-000000000001");
    private static final String ONBOARDING = "/api/v1/notifications/sender-profiles/schools/10/whatsapp-onboarding/" + SESSION;

    @Test
    void schoolAdmin_isForbiddenCompletingOwnSchoolWhatsappOnboarding() throws Exception {
        senderProfileMvc.perform(post(ONBOARDING + "/complete")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "notification:send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"integratedNumber\":\"911234567890\",\"whatsappDefaultTemplateName\":\"fees\"}"))
                .andExpect(status().isForbidden());

        verify(senderProfiles, never()).completeWhatsappOnboarding(any(), any(), anyMap());
        verify(senderProfiles, never()).upsert(any(), any());
    }

    @Test
    void schoolAdmin_isForbiddenFailingOwnSchoolWhatsappOnboarding() throws Exception {
        senderProfileMvc.perform(post(ONBOARDING + "/fail")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "notification:send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"failureReason\":\"changed my mind\"}"))
                .andExpect(status().isForbidden());

        verify(senderProfiles, never()).failWhatsappOnboarding(any(), any(), anyMap());
    }

    @Test
    void headerlessHttp_isForbiddenCompletingWhatsappOnboarding() throws Exception {
        senderProfileMvc.perform(post(ONBOARDING + "/complete")
                        .header("X-Notification-Service-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"integratedNumber\":\"911234567890\"}"))
                .andExpect(status().isForbidden());

        verify(senderProfiles, never()).completeWhatsappOnboarding(any(), any(), anyMap());
    }

    @Test
    void superadmin_canCompleteWhatsappOnboarding() throws Exception {
        when(senderProfiles.completeWhatsappOnboarding(eq(10L), eq(SESSION), anyMap()))
                .thenReturn(Map.of("id", SESSION, "status", "COMPLETED"));

        senderProfileMvc.perform(post(ONBOARDING + "/complete")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"integratedNumber\":\"911234567890\"}"))
                .andExpect(status().isOk());

        verify(senderProfiles).completeWhatsappOnboarding(eq(10L), eq(SESSION), anyMap());
    }

    @Test
    void superadmin_canFailWhatsappOnboarding() throws Exception {
        when(senderProfiles.failWhatsappOnboarding(eq(10L), eq(SESSION), anyMap()))
                .thenReturn(Map.of("id", SESSION, "status", "FAILED"));

        senderProfileMvc.perform(post(ONBOARDING + "/fail")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        verify(senderProfiles).failWhatsappOnboarding(eq(10L), eq(SESSION), anyMap());
    }

    @Test
    void unknownOrOtherSchoolsOnboardingSession_isNotFound() throws Exception {
        when(senderProfiles.completeWhatsappOnboarding(eq(10L), eq(SESSION), anyMap()))
                .thenThrow(new WhatsappOnboardingStateException(Reason.NOT_FOUND));

        senderProfileMvc.perform(post(ONBOARDING + "/complete")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"integratedNumber\":\"911234567890\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("WhatsApp onboarding request not found"));
    }

    @Test
    void closedOnboardingSession_isAConflict() throws Exception {
        when(senderProfiles.completeWhatsappOnboarding(eq(10L), eq(SESSION), anyMap()))
                .thenThrow(new WhatsappOnboardingStateException(Reason.ALREADY_FAILED));
        when(senderProfiles.failWhatsappOnboarding(eq(10L), eq(SESSION), anyMap()))
                .thenThrow(new WhatsappOnboardingStateException(Reason.ALREADY_COMPLETED));

        senderProfileMvc.perform(post(ONBOARDING + "/complete")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"integratedNumber\":\"911234567890\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This WhatsApp onboarding request is already failed"));
        senderProfileMvc.perform(post(ONBOARDING + "/fail")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This WhatsApp onboarding request is already completed"));
    }
}
