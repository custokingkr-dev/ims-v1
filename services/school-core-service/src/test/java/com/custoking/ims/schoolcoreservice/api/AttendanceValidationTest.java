package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.AttendanceReadRepository;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AttendanceValidationTest {

    private static final String VALID_TOKEN = "attendance-token";

    AttendanceReadRepository attendance;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        // Superadmin context so TenantScope.resolveSchoolId(null) returns null without throwing
        TenantContext.set(new TenantContext(1L, "superadmin@test.com", "SUPERADMIN", null, null));
        attendance = mock(AttendanceReadRepository.class);
        AttendanceReadController controller = new AttendanceReadController(attendance, VALID_TOKEN);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ValidationExceptionHandler())
                .build();
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    // ─── PUT /section-register ───────────────────────────────────────────────

    @Test
    void saveSectionRegister_missingClassId_returns400WithFieldError() throws Exception {
        mvc.perform(put("/api/v1/attendance/section-register")
                        .header("X-Attendance-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"sectionId\":\"sec-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.classId").exists());
        verifyNoInteractions(attendance);
    }

    @Test
    void saveSectionRegister_blankClassId_returns400WithFieldError() throws Exception {
        mvc.perform(put("/api/v1/attendance/section-register")
                        .header("X-Attendance-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"classId\":\"\",\"sectionId\":\"sec-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.classId").exists());
        verifyNoInteractions(attendance);
    }

    @Test
    void saveSectionRegister_missingSectionId_returns400WithFieldError() throws Exception {
        mvc.perform(put("/api/v1/attendance/section-register")
                        .header("X-Attendance-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"classId\":\"cls-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.sectionId").exists());
        verifyNoInteractions(attendance);
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveSectionRegister_valid_callsRepoWithClassIdAndSectionId() throws Exception {
        when(attendance.saveSectionRegister(anyMap())).thenReturn(Map.of("ok", true));
        mvc.perform(put("/api/v1/attendance/section-register")
                        .header("X-Attendance-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"classId\":\"cls-1\",\"sectionId\":\"sec-1\",\"date\":\"2026-06-30\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(attendance).saveSectionRegister(captor.capture());
        assertEquals("cls-1", captor.getValue().get("classId"));
        assertEquals("sec-1", captor.getValue().get("sectionId"));
        assertEquals("2026-06-30", captor.getValue().get("date"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveSectionRegister_withActorId_putsActorIdKey() throws Exception {
        when(attendance.saveSectionRegister(anyMap())).thenReturn(Map.of("ok", true));
        mvc.perform(put("/api/v1/attendance/section-register")
                        .header("X-Attendance-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"classId\":\"cls-1\",\"sectionId\":\"sec-1\",\"actorId\":42}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(attendance).saveSectionRegister(captor.capture());
        assertTrue(captor.getValue().containsKey("actorId"), "actorId key must be present when sent");
        assertEquals(42L, captor.getValue().get("actorId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveSectionRegister_withoutActorId_doesNotPutActorIdKey() throws Exception {
        when(attendance.saveSectionRegister(anyMap())).thenReturn(Map.of("ok", true));
        mvc.perform(put("/api/v1/attendance/section-register")
                        .header("X-Attendance-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"classId\":\"cls-1\",\"sectionId\":\"sec-1\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(attendance).saveSectionRegister(captor.capture());
        assertFalse(captor.getValue().containsKey("actorId"), "actorId key must NOT be present when not sent");
    }

    // ─── POST /daily-entry ───────────────────────────────────────────────────

    @Test
    void dailyEntry_missingClassId_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/attendance/daily-entry")
                        .header("X-Attendance-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"sectionId\":\"sec-1\",\"presentCount\":20}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.classId").exists());
        verifyNoInteractions(attendance);
    }

    @Test
    void dailyEntry_blankSectionId_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/attendance/daily-entry")
                        .header("X-Attendance-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"classId\":\"cls-1\",\"sectionId\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.sectionId").exists());
        verifyNoInteractions(attendance);
    }

    @Test
    void dailyEntry_missingSectionId_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/attendance/daily-entry")
                        .header("X-Attendance-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"classId\":\"cls-1\",\"presentCount\":20}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.sectionId").exists());
        verifyNoInteractions(attendance);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dailyEntry_valid_callsRepoWithRequiredKeys() throws Exception {
        when(attendance.saveDailyAttendance(anyMap())).thenReturn(Map.of("ok", true));
        mvc.perform(post("/api/v1/attendance/daily-entry")
                        .header("X-Attendance-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"classId\":\"cls-1\",\"sectionId\":\"sec-1\",\"presentCount\":18,\"totalEnrolled\":30}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(attendance).saveDailyAttendance(captor.capture());
        assertEquals("cls-1", captor.getValue().get("classId"));
        assertEquals("sec-1", captor.getValue().get("sectionId"));
        assertEquals(18, captor.getValue().get("presentCount"));
        assertEquals(30, captor.getValue().get("totalEnrolled"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dailyEntry_withActorId_putsActorIdKey() throws Exception {
        when(attendance.saveDailyAttendance(anyMap())).thenReturn(Map.of("ok", true));
        mvc.perform(post("/api/v1/attendance/daily-entry")
                        .header("X-Attendance-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"classId\":\"cls-1\",\"sectionId\":\"sec-1\",\"actorId\":7}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(attendance).saveDailyAttendance(captor.capture());
        assertTrue(captor.getValue().containsKey("actorId"), "actorId key must be present when sent");
        assertEquals(7L, captor.getValue().get("actorId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dailyEntry_withoutActorId_doesNotPutActorIdKey() throws Exception {
        when(attendance.saveDailyAttendance(anyMap())).thenReturn(Map.of("ok", true));
        mvc.perform(post("/api/v1/attendance/daily-entry")
                        .header("X-Attendance-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"classId\":\"cls-1\",\"sectionId\":\"sec-1\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(attendance).saveDailyAttendance(captor.capture());
        assertFalse(captor.getValue().containsKey("actorId"), "actorId key must NOT be present when not sent");
    }

    // ─── POST /submit-section ────────────────────────────────────────────────

    @Test
    void submitSection_missingClassId_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/attendance/submit-section")
                        .header("X-Attendance-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"sectionId\":\"sec-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.classId").exists());
        verifyNoInteractions(attendance);
    }

    @Test
    void submitSection_missingSectionId_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/attendance/submit-section")
                        .header("X-Attendance-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"classId\":\"cls-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.sectionId").exists());
        verifyNoInteractions(attendance);
    }

    @Test
    void submitSection_blankClassId_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/attendance/submit-section")
                        .header("X-Attendance-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"classId\":\"\",\"sectionId\":\"sec-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.classId").exists());
        verifyNoInteractions(attendance);
    }

    @Test
    @SuppressWarnings("unchecked")
    void submitSection_valid_callsRepoWithClassIdAndSectionId() throws Exception {
        when(attendance.submitAttendanceSection(anyMap())).thenReturn(Map.of("ok", true));
        mvc.perform(post("/api/v1/attendance/submit-section")
                        .header("X-Attendance-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"classId\":\"cls-1\",\"sectionId\":\"sec-1\",\"date\":\"2026-06-30\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(attendance).submitAttendanceSection(captor.capture());
        assertEquals("cls-1", captor.getValue().get("classId"));
        assertEquals("sec-1", captor.getValue().get("sectionId"));
        assertEquals("2026-06-30", captor.getValue().get("date"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void submitSection_withActorId_putsActorIdKey() throws Exception {
        when(attendance.submitAttendanceSection(anyMap())).thenReturn(Map.of("ok", true));
        mvc.perform(post("/api/v1/attendance/submit-section")
                        .header("X-Attendance-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"classId\":\"cls-1\",\"sectionId\":\"sec-1\",\"actorId\":99}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(attendance).submitAttendanceSection(captor.capture());
        assertTrue(captor.getValue().containsKey("actorId"), "actorId key must be present when sent");
        assertEquals(99L, captor.getValue().get("actorId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void submitSection_withoutActorId_doesNotPutActorIdKey() throws Exception {
        when(attendance.submitAttendanceSection(anyMap())).thenReturn(Map.of("ok", true));
        mvc.perform(post("/api/v1/attendance/submit-section")
                        .header("X-Attendance-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"classId\":\"cls-1\",\"sectionId\":\"sec-1\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(attendance).submitAttendanceSection(captor.capture());
        assertFalse(captor.getValue().containsKey("actorId"), "actorId key must NOT be present when not sent");
    }

    // ─── Fix A: bad date string maps to 400 (not 500) ───────────────────────

    @Test
    void dailyEntry_badDateString_returns400NotServerError() throws Exception {
        // Repo runs LocalDate.parse(...) on the "date" value and throws DateTimeParseException.
        when(attendance.saveDailyAttendance(anyMap()))
                .thenThrow(new java.time.format.DateTimeParseException("bad", "not-a-date", 0));
        mvc.perform(post("/api/v1/attendance/daily-entry")
                        .header("X-Attendance-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"classId\":\"cls-1\",\"sectionId\":\"sec-1\",\"date\":\"not-a-date\"}"))
                .andExpect(status().isBadRequest());
    }

    // ─── Fix B: POST /submit-day validated DTO (all optional, format-only) ──

    @Test
    void submitDay_validPartialBody_delegatesOnlySentValues() throws Exception {
        when(attendance.submitAttendanceDay("2026-06-30", null, null)).thenReturn(Map.of("ok", true));
        mvc.perform(post("/api/v1/attendance/submit-day")
                        .header("X-Attendance-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"date\":\"2026-06-30\"}"))
                .andExpect(status().isOk());
        verify(attendance).submitAttendanceDay("2026-06-30", null, null);
    }

    @Test
    void submitDay_negativeActorId_returns400AndRepoNeverCalled() throws Exception {
        mvc.perform(post("/api/v1/attendance/submit-day")
                        .header("X-Attendance-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"actorId\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.actorId").exists());
        verifyNoInteractions(attendance);
    }
}
