package com.custoking.ims.platformservice.api;

import com.custoking.ims.platformservice.persistence.ReportingCommandRepository;
import com.custoking.ims.platformservice.persistence.ReportingReadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertNull;
import com.custoking.ims.platformservice.security.TenantContext;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReportingValidationTest {

    private static final String VALID_TOKEN = "reporting-token";

    ReportingReadRepository reporting;
    ReportingCommandRepository commands;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        reporting = mock(ReportingReadRepository.class);
        commands = mock(ReportingCommandRepository.class);
        ReportingReadController controller = new ReportingReadController(reporting, commands, VALID_TOKEN);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ValidationExceptionHandler())
                .build();
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ─── POST /command-center/feed ───────────────────────────────────────────
    // All fields are optional in the repo (all have defaults or accept null).
    // schoolId is explicitly nullable — feed rows do not require a tenant.

    @Test
    @SuppressWarnings("unchecked")
    void recordFeed_emptyBody_returns200AndCallsRepo() throws Exception {
        when(commands.recordFeed(anyMap())).thenReturn(Map.of("id", "feed-1"));
        mvc.perform(post("/api/v1/reporting/command-center/feed")
                        .header("X-Reporting-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(commands).recordFeed(captor.capture());
        Map<String, Object> map = captor.getValue();
        // RecordFeedRequest.toMap() puts all keys unconditionally; empty body → all values null
        assertNull(map.get("module"), "module must be null when not sent");
        assertNull(map.get("eventType"), "eventType must be null when not sent");
        assertNull(map.get("title"), "title must be null when not sent");
        assertNull(map.get("message"), "message must be null when not sent");
        assertNull(map.get("severity"), "severity must be null when not sent");
        assertNull(map.get("entityType"), "entityType must be null when not sent");
        assertNull(map.get("entityId"), "entityId must be null when not sent");
        assertNull(map.get("actorUserId"), "actorUserId must be null when not sent");
        assertNull(map.get("schoolId"), "schoolId must be null when not sent");
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordFeed_withAllFields_callsRepoWithExactValues() throws Exception {
        when(commands.recordFeed(anyMap())).thenReturn(Map.of("id", "feed-1"));
        mvc.perform(post("/api/v1/reporting/command-center/feed")
                        .header("X-Reporting-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("""
                                {
                                  "schoolId": 4,
                                  "module": "FEES",
                                  "eventType": "FEE_OVERDUE",
                                  "title": "Fee overdue",
                                  "message": "3 students overdue",
                                  "severity": "high",
                                  "entityType": "student",
                                  "entityId": "stu-101",
                                  "actorUserId": 9
                                }
                                """))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(commands).recordFeed(captor.capture());
        Map<String, Object> map = captor.getValue();
        assertEquals(4L, map.get("schoolId"));
        assertEquals("FEES", map.get("module"));
        assertEquals("FEE_OVERDUE", map.get("eventType"));
        assertEquals("Fee overdue", map.get("title"));
        assertEquals("3 students overdue", map.get("message"));
        assertEquals("high", map.get("severity"));
        assertEquals("student", map.get("entityType"));
        assertEquals("stu-101", map.get("entityId"));
        assertEquals(9L, map.get("actorUserId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordFeed_withoutSchoolId_callsRepoWithNullSchoolId() throws Exception {
        when(commands.recordFeed(anyMap())).thenReturn(Map.of("id", "feed-1"));
        mvc.perform(post("/api/v1/reporting/command-center/feed")
                        .header("X-Reporting-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"module\":\"SYSTEM\",\"title\":\"System event\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(commands).recordFeed(captor.capture());
        // schoolId is nullable on feed — must not be required
        assertNull(captor.getValue().get("schoolId"), "schoolId must be null when not sent");
    }

    // ─── POST /event-contributions/reminders ─────────────────────────────────

    @Test
    void markReminders_missingEventId_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/reporting/event-contributions/reminders")
                        .header("X-Reporting-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"schoolId\":4,\"studentIds\":[101,102]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.eventId").exists());
        verifyNoInteractions(commands);
    }

    @Test
    void markReminders_blankEventId_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/reporting/event-contributions/reminders")
                        .header("X-Reporting-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"eventId\":\"\",\"schoolId\":4,\"studentIds\":[101]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.eventId").exists());
        verifyNoInteractions(commands);
    }

    @Test
    void markReminders_missingSchoolId_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/reporting/event-contributions/reminders")
                        .header("X-Reporting-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"eventId\":\"event-1\",\"studentIds\":[101]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.schoolId").exists());
        verifyNoInteractions(commands);
    }

    @Test
    @SuppressWarnings("unchecked")
    void markReminders_valid_callsRepoWithExactKeys() throws Exception {
        when(commands.markEventContributionReminders(anyMap())).thenReturn(Map.of("updated", 2));
        mvc.perform(post("/api/v1/reporting/event-contributions/reminders")
                        .header("X-Reporting-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"eventId\":\"event-1\",\"schoolId\":4,\"studentIds\":[101,102]}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(commands).markEventContributionReminders(captor.capture());
        assertEquals("event-1", captor.getValue().get("eventId"));
        assertEquals(4L, captor.getValue().get("schoolId"));
        assertEquals(List.of(101L, 102L), captor.getValue().get("studentIds"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void markReminders_withoutStudentIds_callsRepoWithoutStudentIdsKey() throws Exception {
        when(commands.markEventContributionReminders(anyMap())).thenReturn(Map.of("updated", 0));
        mvc.perform(post("/api/v1/reporting/event-contributions/reminders")
                        .header("X-Reporting-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"eventId\":\"event-1\",\"schoolId\":4}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(commands).markEventContributionReminders(captor.capture());
        assertEquals("event-1", captor.getValue().get("eventId"));
        assertEquals(4L, captor.getValue().get("schoolId"));
        // studentIds not sent — key must be absent (repo treats null → empty list → early return)
        assertNull(captor.getValue().get("studentIds"), "studentIds key must be absent when not sent");
    }

    // ─── POST /event-contributions/reminder-targets ──────────────────────────

    @Test
    void reminderTargets_missingEventId_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/reporting/event-contributions/reminder-targets")
                        .header("X-Reporting-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"schoolId\":4}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.eventId").exists());
        verifyNoInteractions(commands);
    }

    @Test
    void reminderTargets_blankEventId_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/reporting/event-contributions/reminder-targets")
                        .header("X-Reporting-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"eventId\":\"  \",\"schoolId\":4}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.eventId").exists());
        verifyNoInteractions(commands);
    }

    @Test
    void reminderTargets_missingSchoolId_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/reporting/event-contributions/reminder-targets")
                        .header("X-Reporting-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"eventId\":\"event-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.schoolId").exists());
        verifyNoInteractions(commands);
    }

    @Test
    @SuppressWarnings("unchecked")
    void reminderTargets_valid_callsRepoWithExactKeys() throws Exception {
        when(commands.eventPaymentReminderTargets(anyMap()))
                .thenReturn(Map.of("eventId", "event-1", "targets", List.of(), "failed", List.of()));
        mvc.perform(post("/api/v1/reporting/event-contributions/reminder-targets")
                        .header("X-Reporting-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"eventId\":\"event-1\",\"schoolId\":4,\"studentIds\":[201,202]}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(commands).eventPaymentReminderTargets(captor.capture());
        assertEquals("event-1", captor.getValue().get("eventId"));
        assertEquals(4L, captor.getValue().get("schoolId"));
        assertEquals(List.of(201L, 202L), captor.getValue().get("studentIds"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reminderTargets_withoutStudentIds_callsRepoWithoutStudentIdsKey() throws Exception {
        when(commands.eventPaymentReminderTargets(anyMap()))
                .thenReturn(Map.of("eventId", "event-1", "targets", List.of(), "failed", List.of()));
        mvc.perform(post("/api/v1/reporting/event-contributions/reminder-targets")
                        .header("X-Reporting-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"eventId\":\"event-1\",\"schoolId\":4}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(commands).eventPaymentReminderTargets(captor.capture());
        assertEquals("event-1", captor.getValue().get("eventId"));
        assertEquals(4L, captor.getValue().get("schoolId"));
        assertNull(captor.getValue().get("studentIds"), "studentIds key must be absent when not sent");
    }

    // ─── POST /command-center/actions/{id}/accept ────────────────────────────────

    @Test
    void acceptAction_negativeActorId_returns400() throws Exception {
        String id = "11111111-1111-1111-1111-111111111111";
        mvc.perform(post("/api/v1/reporting/command-center/actions/{id}/accept", id)
                        .header("X-Reporting-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"actorId\": -1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.actorId").exists());
        verifyNoInteractions(commands);
    }

    @Test
    void dismissAction_negativeActorId_returns400() throws Exception {
        String id = "11111111-1111-1111-1111-111111111111";
        mvc.perform(post("/api/v1/reporting/command-center/actions/{id}/dismiss", id)
                        .header("X-Reporting-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"actorId\": -1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.actorId").exists());
        verifyNoInteractions(commands);
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptAction_validBody_callsRepo() throws Exception {
        // actor comes from the authenticated principal (TenantContext userId=1L), not the request body's actorId.
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(commands.acceptAction(eq(id), eq(1L), eq(4L), eq(true)))
                .thenReturn(Map.of("id", id.toString(), "status", "ACCEPTED"));
        mvc.perform(post("/api/v1/reporting/command-center/actions/{id}/accept", id)
                        .header("X-Reporting-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"actorId\": 9, \"schoolId\": 4}"))
                .andExpect(status().isOk());
        verify(commands).acceptAction(eq(id), eq(1L), eq(4L), eq(true));
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptAction_nullBody_works() throws Exception {
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        UUID id = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(commands.acceptAction(eq(id), eq(1L), eq(null), eq(true)))
                .thenReturn(Map.of("id", id.toString(), "status", "ACCEPTED"));
        mvc.perform(post("/api/v1/reporting/command-center/actions/{id}/accept", id)
                        .header("X-Reporting-Service-Token", VALID_TOKEN))
                .andExpect(status().isOk());
        verify(commands).acceptAction(eq(id), eq(1L), eq(null), eq(true));
    }
}
