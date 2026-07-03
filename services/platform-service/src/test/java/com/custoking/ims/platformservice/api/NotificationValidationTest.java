package com.custoking.ims.platformservice.api;

import com.custoking.ims.platformservice.application.SenderProfile;
import com.custoking.ims.platformservice.persistence.NotificationBroadcastCommandRepository;
import com.custoking.ims.platformservice.persistence.NotificationLogCommandRepository;
import com.custoking.ims.platformservice.persistence.SenderProfileRepository;
import com.custoking.ims.platformservice.security.TenantContext;
import com.custoking.ims.platformservice.security.TenantContextFilter;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.isNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
    void createBroadcast_blankMessage_returns400WithFieldError() throws Exception {
        broadcastMvc.perform(post("/api/v1/notifications/broadcasts")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType("application/json")
                        .content("{\"title\":\"Announcement\",\"message\":\"\"}"))
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
    void createLog_blankNotificationType_returns400WithFieldError() throws Exception {
        logMvc.perform(post("/api/v1/notifications/logs")
                        .header("X-Notification-Service-Token", TOKEN)
                        .contentType("application/json")
                        .content("{\"channel\":\"SMS\",\"notificationType\":\"\"}"))
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

    // ─── PUT /notifications/sender-profiles/default (partial upsert) ────────────

    @Test
    void updateDefaultProfile_invalidEmail_returns400WithFieldError() throws Exception {
        senderProfileMvc.perform(put("/api/v1/notifications/sender-profiles/default")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType("application/json")
                        .content("{\"emailFromAddress\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.emailFromAddress").exists());
        verify(senderProfiles, never()).upsert(any(), anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateDefaultProfile_validPartial_callsRepoWithOnlySentKeys() throws Exception {
        when(senderProfiles.upsert(isNull(), anyMap())).thenReturn(
                new SenderProfile(null, null, "Test Profile", null, "test@example.com",
                        null, null, null, null, null, null, null, null, null, null));

        senderProfileMvc.perform(put("/api/v1/notifications/sender-profiles/default")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType("application/json")
                        .content("{\"profileName\":\"Test Profile\",\"emailFromAddress\":\"test@example.com\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(senderProfiles).upsert(isNull(), captor.capture());
        assertEquals("Test Profile", captor.getValue().get("profileName"));
        assertEquals("test@example.com", captor.getValue().get("emailFromAddress"));
        assertTrue(!captor.getValue().containsKey("emailFromName"),
                "emailFromName must not be present when omitted");
        assertTrue(!captor.getValue().containsKey("msg91SmsFlowId"),
                "msg91SmsFlowId must not be present when omitted");
    }

    // ─── POST /notifications/broadcasts/{id}/approve (broadcast action) ──────

    @Test
    @SuppressWarnings("unchecked")
    void approveBroadcast_validActorId_callsRepoWithActorId() throws Exception {
        UUID id = UUID.randomUUID();
        when(broadcasts.approve(eq(id), eq(5L))).thenReturn(Map.of("id", id, "status", "SCHEDULED"));

        broadcastMvc.perform(post("/api/v1/notifications/broadcasts/" + id + "/approve")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType("application/json")
                        .content("{\"actorId\":5}"))
                .andExpect(status().isOk());

        verify(broadcasts).approve(eq(id), eq(5L));
    }

    @Test
    void sendBroadcast_validActorId_callsRepoWithActorId() throws Exception {
        UUID id = UUID.randomUUID();
        when(broadcasts.send(eq(id), eq(5L))).thenReturn(Map.of("id", id, "status", "SENT"));

        broadcastMvc.perform(post("/api/v1/notifications/broadcasts/" + id + "/send")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType("application/json")
                        .content("{\"actorId\":5}"))
                .andExpect(status().isOk());

        verify(broadcasts).send(eq(id), eq(5L));
    }

    @Test
    void approveBroadcast_noBody_callsRepoWithNullActorId() throws Exception {
        UUID id = UUID.randomUUID();
        when(broadcasts.approve(eq(id), isNull())).thenReturn(Map.of("id", id, "status", "SCHEDULED"));

        broadcastMvc.perform(post("/api/v1/notifications/broadcasts/" + id + "/approve")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());

        verify(broadcasts).approve(eq(id), isNull());
    }

    @Test
    @SuppressWarnings("unchecked")
    void requestWhatsappOnboarding_forwardsActorIdAsRequestedByParamNotBodyKey() throws Exception {
        when(senderProfiles.requestWhatsappOnboarding(eq(4L), eq(9L), anyMap()))
                .thenReturn(Map.of("sessionId", UUID.randomUUID()));

        senderProfileMvc.perform(post("/api/v1/notifications/sender-profiles/schools/4/whatsapp-onboarding")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType("application/json")
                        .content("{\"actorId\":9,\"schoolName\":\"Demo\",\"contactEmail\":\"a@b.com\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(senderProfiles).requestWhatsappOnboarding(eq(4L), eq(9L), captor.capture());
        assertEquals("Demo", captor.getValue().get("schoolName"));
        assertEquals("a@b.com", captor.getValue().get("contactEmail"));
        assertFalse(captor.getValue().containsKey("actorId"),
                "actorId is forwarded as the requestedBy parameter, not a body-map key");
    }

    @Test
    void requestWhatsappOnboarding_invalidContactEmail_returns400() throws Exception {
        senderProfileMvc.perform(post("/api/v1/notifications/sender-profiles/schools/4/whatsapp-onboarding")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType("application/json")
                        .content("{\"contactEmail\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.contactEmail").exists());

        verify(senderProfiles, never()).requestWhatsappOnboarding(anyLong(), any(), anyMap());
    }

    @Test
    void approveBroadcast_negativeActorId_returns400WithFieldError() throws Exception {
        UUID id = UUID.randomUUID();

        broadcastMvc.perform(post("/api/v1/notifications/broadcasts/" + id + "/approve")
                        .header("X-Notification-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType("application/json")
                        .content("{\"actorId\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.actorId").exists());
        verify(broadcasts, never()).approve(any(), any());
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
        // Need a valid tenant context (resolveSchoolId call)
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
