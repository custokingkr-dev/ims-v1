package com.custoking.ims.notificationservice.api;

import com.custoking.ims.notificationservice.application.SenderProfile;
import com.custoking.ims.notificationservice.persistence.NotificationBroadcastCommandRepository;
import com.custoking.ims.notificationservice.persistence.NotificationLogCommandRepository;
import com.custoking.ims.notificationservice.persistence.SenderProfileRepository;
import com.custoking.ims.notificationservice.security.TenantContext;
import com.custoking.ims.notificationservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests for the validated-DTO endpoints in notification-service.
 * Verifies: invalid payload → 400 with fieldErrors (repo never called);
 *            valid payload → repo called once with correct map keys/values.
 */
class NotificationValidationTest {

    private static final String TOKEN = "notif-token";

    private final NotificationBroadcastCommandRepository broadcasts =
            mock(NotificationBroadcastCommandRepository.class);
    private final NotificationLogCommandRepository logs =
            mock(NotificationLogCommandRepository.class);
    private final SenderProfileRepository senderProfiles =
            mock(SenderProfileRepository.class);

    private MockMvc broadcastMvc;
    private MockMvc logMvc;
    private MockMvc senderProfileMvc;

    @BeforeEach
    void setUp() {
        broadcastMvc = MockMvcBuilders
                .standaloneSetup(new NotificationBroadcastCommandController(broadcasts, TOKEN))
                .setControllerAdvice(new ValidationExceptionHandler())
                .addFilters(new TenantContextFilter())
                .build();

        logMvc = MockMvcBuilders
                .standaloneSetup(new NotificationLogCommandController(logs, TOKEN))
                .setControllerAdvice(new ValidationExceptionHandler())
                .addFilters(new TenantContextFilter())
                .build();

        senderProfileMvc = MockMvcBuilders
                .standaloneSetup(new SenderProfileController(senderProfiles, TOKEN))
                .setControllerAdvice(new ValidationExceptionHandler())
                .addFilters(new TenantContextFilter())
                .build();
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    // ─── POST /notifications/broadcasts ──────────────────────────────────────

    @Test
    void createBroadcast_missingTitle_returns400WithFieldError() throws Exception {
        broadcastMvc.perform(post("/api/v1/notifications/broadcasts")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType("application/json")
                        .content("{\"message\":\"Hello everyone\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.title").exists());
        verifyNoInteractions(broadcasts);
    }

    @Test
    void createBroadcast_blankTitle_returns400WithFieldError() throws Exception {
        broadcastMvc.perform(post("/api/v1/notifications/broadcasts")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType("application/json")
                        .content("{\"title\":\"\",\"message\":\"Hello everyone\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.title").exists());
        verifyNoInteractions(broadcasts);
    }

    @Test
    void createBroadcast_missingMessage_returns400WithFieldError() throws Exception {
        broadcastMvc.perform(post("/api/v1/notifications/broadcasts")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType("application/json")
                        .content("{\"title\":\"Announcement\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.message").exists());
        verifyNoInteractions(broadcasts);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createBroadcast_valid_callsRepoWithTitleAndMessage() throws Exception {
        when(broadcasts.create(anyMap())).thenReturn(Map.of("id", UUID.randomUUID()));

        broadcastMvc.perform(post("/api/v1/notifications/broadcasts")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType("application/json")
                        .content("{\"title\":\"School Announcement\",\"message\":\"Dear parents, school is closed tomorrow.\",\"audienceType\":\"ALL\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(broadcasts).create(captor.capture());
        assertEquals("School Announcement", captor.getValue().get("title"));
        assertEquals("Dear parents, school is closed tomorrow.", captor.getValue().get("message"));
        assertEquals("ALL", captor.getValue().get("audienceType"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void createBroadcast_withOptionalFields_putsThemInMap() throws Exception {
        when(broadcasts.create(anyMap())).thenReturn(Map.of("id", UUID.randomUUID()));

        broadcastMvc.perform(post("/api/v1/notifications/broadcasts")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType("application/json")
                        .content("{\"title\":\"Test\",\"message\":\"Body\",\"schoolId\":10,\"module\":\"FEES\",\"createdBy\":99}"))
                .andExpect(status().isOk());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(broadcasts).create(captor.capture());
        assertEquals(10L, captor.getValue().get("schoolId"));
        assertEquals("FEES", captor.getValue().get("module"));
        assertEquals(99L, captor.getValue().get("createdBy"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void createBroadcast_withoutOptionalFields_doesNotPutThemInMap() throws Exception {
        when(broadcasts.create(anyMap())).thenReturn(Map.of("id", UUID.randomUUID()));

        broadcastMvc.perform(post("/api/v1/notifications/broadcasts")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType("application/json")
                        .content("{\"title\":\"Test\",\"message\":\"Body\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(broadcasts).create(captor.capture());
        assertTrue(!captor.getValue().containsKey("schoolId"), "schoolId must not be present when omitted");
        assertTrue(!captor.getValue().containsKey("module"), "module must not be present when omitted");
        assertTrue(!captor.getValue().containsKey("createdBy"), "createdBy must not be present when omitted");
    }

    // ─── POST /notifications/logs ─────────────────────────────────────────────

    @Test
    void createLog_missingChannel_returns400WithFieldError() throws Exception {
        logMvc.perform(post("/api/v1/notifications/logs")
                        .header("X-Notification-Service-Token", TOKEN)
                        .contentType("application/json")
                        .content("{\"notificationType\":\"FEE_REMINDER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.channel").exists());
        verifyNoInteractions(logs);
    }

    @Test
    void createLog_blankChannel_returns400WithFieldError() throws Exception {
        logMvc.perform(post("/api/v1/notifications/logs")
                        .header("X-Notification-Service-Token", TOKEN)
                        .contentType("application/json")
                        .content("{\"channel\":\"\",\"notificationType\":\"FEE_REMINDER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.channel").exists());
        verifyNoInteractions(logs);
    }

    @Test
    void createLog_missingNotificationType_returns400WithFieldError() throws Exception {
        logMvc.perform(post("/api/v1/notifications/logs")
                        .header("X-Notification-Service-Token", TOKEN)
                        .contentType("application/json")
                        .content("{\"channel\":\"SMS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.notificationType").exists());
        verifyNoInteractions(logs);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createLog_valid_callsRepoWithChannelAndType() throws Exception {
        when(logs.createRequestLog(anyMap())).thenReturn(Map.of("id", "log-123"));

        logMvc.perform(post("/api/v1/notifications/logs")
                        .header("X-Notification-Service-Token", TOKEN)
                        .contentType("application/json")
                        .content("{\"channel\":\"SMS\",\"notificationType\":\"FEE_REMINDER\",\"schoolId\":10,\"studentId\":501}"))
                .andExpect(status().isOk());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(logs).createRequestLog(captor.capture());
        assertEquals("SMS", captor.getValue().get("channel"));
        assertEquals("FEE_REMINDER", captor.getValue().get("notificationType"));
        assertEquals(10L, captor.getValue().get("schoolId"));
        assertEquals(501L, captor.getValue().get("studentId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void createLog_withoutOptionalFields_doesNotPutThemInMap() throws Exception {
        when(logs.createRequestLog(anyMap())).thenReturn(Map.of("id", "log-abc"));

        logMvc.perform(post("/api/v1/notifications/logs")
                        .header("X-Notification-Service-Token", TOKEN)
                        .contentType("application/json")
                        .content("{\"channel\":\"EMAIL\",\"notificationType\":\"ATTENDANCE\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(logs).createRequestLog(captor.capture());
        assertTrue(!captor.getValue().containsKey("schoolId"), "schoolId must not be present when omitted");
        assertTrue(!captor.getValue().containsKey("studentId"), "studentId must not be present when omitted");
        assertTrue(!captor.getValue().containsKey("sentBy"), "sentBy must not be present when omitted");
    }

    // ─── POST /notifications/sender-profiles/schools/{schoolId}/whatsapp-onboarding/{sessionId}/complete ──

    @Test
    void completeOnboarding_missingIntegratedNumber_returns400WithFieldError() throws Exception {
        // Need a valid tenant context (resolveSchoolId call)
        senderProfileMvc.perform(post("/api/v1/notifications/sender-profiles/schools/10/whatsapp-onboarding/"
                        + UUID.randomUUID() + "/complete")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType("application/json")
                        .content("{\"providerReference\":\"ref-123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.integratedNumber").exists());
        verify(senderProfiles, never()).completeWhatsappOnboarding(any(), any(), anyMap());
    }

    @Test
    void completeOnboarding_blankIntegratedNumber_returns400WithFieldError() throws Exception {
        senderProfileMvc.perform(post("/api/v1/notifications/sender-profiles/schools/10/whatsapp-onboarding/"
                        + UUID.randomUUID() + "/complete")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType("application/json")
                        .content("{\"integratedNumber\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.integratedNumber").exists());
        verify(senderProfiles, never()).completeWhatsappOnboarding(any(), any(), anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void completeOnboarding_valid_callsRepoWithIntegratedNumber() throws Exception {
        UUID sessionId = UUID.randomUUID();
        when(senderProfiles.completeWhatsappOnboarding(eq(10L), eq(sessionId), anyMap()))
                .thenReturn(Map.of("id", sessionId, "status", "COMPLETED"));

        senderProfileMvc.perform(post("/api/v1/notifications/sender-profiles/schools/10/whatsapp-onboarding/"
                        + sessionId + "/complete")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType("application/json")
                        .content("{\"integratedNumber\":\"911234567890\",\"providerReference\":\"WABA-XYZ\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(senderProfiles).completeWhatsappOnboarding(eq(10L), eq(sessionId), captor.capture());
        assertEquals("911234567890", captor.getValue().get("integratedNumber"));
        assertEquals("WABA-XYZ", captor.getValue().get("providerReference"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void completeOnboarding_withSenderProfileFields_putsThemInMap() throws Exception {
        UUID sessionId = UUID.randomUUID();
        when(senderProfiles.completeWhatsappOnboarding(eq(10L), eq(sessionId), anyMap()))
                .thenReturn(Map.of("id", sessionId, "status", "COMPLETED"));

        senderProfileMvc.perform(post("/api/v1/notifications/sender-profiles/schools/10/whatsapp-onboarding/"
                        + sessionId + "/complete")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType("application/json")
                        .content("{\"integratedNumber\":\"911234567890\",\"whatsappDisplayName\":\"Custoking School\",\"msg91SmsFlowId\":\"flow-1\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(senderProfiles).completeWhatsappOnboarding(eq(10L), eq(sessionId), captor.capture());
        assertEquals("911234567890", captor.getValue().get("integratedNumber"));
        assertEquals("Custoking School", captor.getValue().get("whatsappDisplayName"));
        assertEquals("flow-1", captor.getValue().get("msg91SmsFlowId"));
    }
}
