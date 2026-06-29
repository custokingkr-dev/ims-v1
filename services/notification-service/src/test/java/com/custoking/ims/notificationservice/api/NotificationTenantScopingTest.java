package com.custoking.ims.notificationservice.api;

import com.custoking.ims.notificationservice.persistence.NotificationBroadcastCommandRepository;
import com.custoking.ims.notificationservice.persistence.NotificationLogCommandRepository;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies tenant-scoping guards on notification controllers using a real TenantContextFilter
 * in a standalone MockMvc setup.
 *
 * Judgment calls documented inline:
 * - NotificationLogCommandController (POST /api/v1/notifications/logs): system-internal ingestion,
 *   NOT guarded. Proven to work without a superadmin context in this test.
 * - SenderProfileController GET /default and GET /schools/{id}: system-internal resolution,
 *   NOT guarded.
 * - SenderProfileController POST /schools/{id}/whatsapp-onboarding/{sessionId}/complete|fail:
 *   ambiguous (may be MSG91 webhook callback); left ungated — covered by concern note in report.
 */
class NotificationTenantScopingTest {

    private static final String TOKEN = "notif-token";

    private final NotificationBroadcastCommandRepository broadcasts =
            mock(NotificationBroadcastCommandRepository.class);
    private final NotificationLogCommandRepository logs =
            mock(NotificationLogCommandRepository.class);

    private final MockMvc broadcastMvc = MockMvcBuilders
            .standaloneSetup(new NotificationBroadcastCommandController(broadcasts, TOKEN))
            .addFilters(new TenantContextFilter())
            .build();

    private final MockMvc logMvc = MockMvcBuilders
            .standaloneSetup(new NotificationLogCommandController(logs, TOKEN))
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
                        .content("{\"eventType\":\"FEE_PAYMENT\",\"schoolId\":10}"))
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
                        .content("{\"eventType\":\"ATTENDANCE\",\"schoolId\":5}"))
                .andExpect(status().isOk());

        verify(logs).createRequestLog(any());
    }
}
