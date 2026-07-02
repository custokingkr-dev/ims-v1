package com.custoking.ims.notificationservice.api;

import com.custoking.ims.notificationservice.application.SenderProfile;
import com.custoking.ims.notificationservice.persistence.NotificationBroadcastCommandRepository;
import com.custoking.ims.notificationservice.persistence.NotificationLogCommandRepository;
import com.custoking.ims.notificationservice.persistence.SenderProfileRepository;
import com.custoking.ims.notificationservice.security.TenantContext;
import com.custoking.ims.notificationservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies tenant-scoping guards on notification controllers using a real TenantContextFilter
 * in a standalone MockMvc setup.
 *
 * Judgment calls documented inline:
 * - NotificationLogCommandController (POST /api/v1/notifications/logs): system-internal ingestion,
 *   NOT guarded. Proven to work without a superadmin context in this test.
 * - SenderProfileController: ALL guards are UNCONDITIONAL. The Msg91 delivery path reads
 *   sender profiles in-process via SenderProfileRepository — there is no legitimate
 *   header-less HTTP caller of this controller. A header-less HTTP request is fail-closed → 403.
 */
class NotificationTenantScopingTest {

    private static final String TOKEN = "notif-token";

    private final NotificationBroadcastCommandRepository broadcasts =
            mock(NotificationBroadcastCommandRepository.class);
    private final NotificationLogCommandRepository logs =
            mock(NotificationLogCommandRepository.class);
    private final SenderProfileRepository senderProfiles =
            mock(SenderProfileRepository.class);

    private static final SenderProfile STUB_PROFILE = new SenderProfile(
            null, 10L, "Test Profile", null, null, null, null,
            null, null, null, null, "en", null, null, null);

    private final MockMvc broadcastMvc = MockMvcBuilders
            .standaloneSetup(new NotificationBroadcastCommandController(broadcasts, TOKEN))
            .addFilters(new TenantContextFilter())
            .build();

    private final MockMvc logMvc = MockMvcBuilders
            .standaloneSetup(new NotificationLogCommandController(logs, TOKEN))
            .addFilters(new TenantContextFilter())
            .build();

    private final MockMvc senderProfileMvc = MockMvcBuilders
            .standaloneSetup(new SenderProfileController(senderProfiles, TOKEN))
            .addFilters(new TenantContextFilter())
            .build();

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    // --- broadcast endpoint: non-superadmin blocked ---

    @Test
    void nonSuperadmin_isForbiddenOnBroadcastList() throws Exception {
        broadcastMvc.perform(get("/api/v1/notifications/broadcasts")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10"))
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

    // --- system-internal log ingestion: works WITHOUT a superadmin context ---

    @Test
    void systemInternalLogIngestion_worksWithoutSuperadminContext() throws Exception {
        // NotificationLogCommandController is NOT guarded with requireSuperAdmin.
        // Services call it with a service token only and no user context headers.
        when(logs.createRequestLog(any())).thenReturn(Map.of("id", "log-123"));

        logMvc.perform(post("/api/v1/notifications/logs")
                        .header("X-Notification-Service-Token", TOKEN)
                        // No X-Authenticated-Role — simulating a system-to-system call
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"SMS\",\"notificationType\":\"FEE_PAYMENT\",\"schoolId\":10}"))
                .andExpect(status().isOk());

        verify(logs).createRequestLog(any());
    }

    @Test
    void systemInternalLogIngestion_notBlockedBySchoolAdminContext() throws Exception {
        // Even a non-superadmin user context must not block the ingestion path.
        when(logs.createRequestLog(any())).thenReturn(Map.of("id", "log-456"));

        logMvc.perform(post("/api/v1/notifications/logs")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"SMS\",\"notificationType\":\"ATTENDANCE\",\"schoolId\":5}"))
                .andExpect(status().isOk());

        verify(logs).createRequestLog(any());
    }

    // --- SenderProfileController: cross-tenant read blocked for school admin ---

    @Test
    void schoolAdmin_isForbiddenReadingAnotherSchoolProfile() throws Exception {
        // ADMIN for school 10 tries to read school 99's sender profile → 403
        senderProfileMvc.perform(get("/api/v1/notifications/sender-profiles/schools/99")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10"))
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
                        .header("X-Authenticated-School-Id", "10"))
                .andExpect(status().isOk());

        verify(senderProfiles).resolve(10L);
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
                        .header("X-Authenticated-School-Id", "10"))
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
}
