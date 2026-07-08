package com.custoking.ims.platformservice.api;

import com.custoking.ims.platformservice.persistence.NotificationDeliveryAttemptRepository;
import com.custoking.ims.platformservice.persistence.NotificationInboxEvent;
import com.custoking.ims.platformservice.persistence.NotificationInboxRepository;
import com.custoking.ims.platformservice.security.TenantContext;
import com.custoking.ims.platformservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies GET /api/v1/notifications/{eventId} (notification-status read) is superadmin-only.
 * This endpoint returns raw event payload/status/error details for any event id — a non-superadmin
 * caller must not be able to enumerate/read other tenants' notification events (IDOR).
 */
class NotificationStatusControllerTest {

    private static final String TOKEN = "notif-status-token";

    private final NotificationInboxRepository inboxRepository = mock(NotificationInboxRepository.class);
    private final NotificationDeliveryAttemptRepository attemptRepository =
            mock(NotificationDeliveryAttemptRepository.class);

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new NotificationStatusController(inboxRepository, attemptRepository, TOKEN))
            .addFilters(new TenantContextFilter())
            .build();

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void nonSuperadmin_isForbiddenReadingStatus() throws Exception {
        mvc.perform(get("/api/v1/notifications/evt-1")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "ADMIN"))
                .andExpect(status().isForbidden());

        verify(inboxRepository, never()).findById(any());
    }

    @Test
    void unauthenticated_isForbiddenReadingStatus() throws Exception {
        mvc.perform(get("/api/v1/notifications/evt-1")
                        .header("X-Notification-Service-Token", TOKEN))
                .andExpect(status().isForbidden());

        verify(inboxRepository, never()).findById(any());
    }

    @Test
    void superadmin_canReadStatus() throws Exception {
        NotificationInboxEvent event = new NotificationInboxEvent();
        event.setEventId("evt-1");
        event.setEventType("student.review.completed.v1");
        event.setStatus(NotificationInboxEvent.STATUS_PROCESSED);
        when(inboxRepository.findById("evt-1")).thenReturn(Optional.of(event));
        when(attemptRepository.findByEventIdOrderByAttemptedAtDesc("evt-1")).thenReturn(List.of());

        mvc.perform(get("/api/v1/notifications/evt-1")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());

        verify(inboxRepository).findById("evt-1");
    }
}
