package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.api.dto.DailyEntryRequest;
import com.custoking.ims.schoolcoreservice.api.dto.SaveSectionRegisterRequest;
import com.custoking.ims.schoolcoreservice.api.dto.SubmitDayRequest;
import com.custoking.ims.schoolcoreservice.api.dto.SubmitSectionRequest;
import com.custoking.ims.schoolcoreservice.persistence.AttendanceReadRepository;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AttendanceReadControllerTest {

    private final AttendanceReadRepository attendance = mock(AttendanceReadRepository.class);
    private final AttendanceReadController controller = new AttendanceReadController(attendance, "attendance-token");

    @BeforeEach
    void setUpSuperAdminContext() {
        TenantContext.set(new TenantContext(1L, "superadmin@test.com", "SUPERADMIN", null, null));
    }

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    @Test
    void dailyRejectsInvalidTokenBeforeQuerying() {
        assertThatThrownBy(() -> controller.daily("wrong-token", "4-1A", "2026", 25))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(attendance, never()).daily("4-1A", "2026", 25);
    }

    @Test
    void recordsDelegatesFiltersWithValidToken() {
        LocalDate date = LocalDate.parse("2026-02-02");
        when(attendance.records(990001L, 4L, date, 25)).thenReturn(List.of());

        Object response = controller.records("attendance-token", 990001L, 4L, date, 25);

        assertThat(response).isEqualTo(List.of());
        verify(attendance).records(990001L, 4L, date, 25);
    }

    @Test
    void dailySummaryMapsValidationFailureToBadRequest() {
        LocalDate date = LocalDate.parse("2026-02-02");
        when(attendance.dailySummary(date, 4L))
                .thenThrow(new IllegalArgumentException("No active academic year configured"));

        assertThatThrownBy(() -> controller.dailySummary("attendance-token", 4L, date))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(response.getReason()).isEqualTo("No active academic year configured");
                });
    }

    @Test
    void sectionInfoMapsCrossSchoolAccessToForbidden() {
        LocalDate date = LocalDate.parse("2026-02-02");
        when(attendance.sectionInfo(date, "4-1A", 5L))
                .thenThrow(new SecurityException("You do not have access to this section"));

        assertThatThrownBy(() -> controller.sectionInfo("attendance-token", 5L, date, "4-1A"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(response.getReason()).isEqualTo("You do not have access to this section");
                });
    }

    @Test
    void submitDayParsesRequestAndDelegates() {
        // actorId is always sourced from TenantContext (1L here), never the request body's actorId (9L).
        SubmitDayRequest request = new SubmitDayRequest("2026-02-02", 9L, 4L);
        Map<String, Object> result = Map.of("ok", true, "submitted", 3);
        when(attendance.submitAttendanceDay("2026-02-02", 4L, 1L)).thenReturn(result);

        Object response = controller.submitDay("attendance-token", request);

        assertThat(response).isSameAs(result);
        verify(attendance).submitAttendanceDay("2026-02-02", 4L, 1L);
    }

    @Test
    void submitDayNullBodyDelegatesWithDefaults() {
        Map<String, Object> result = Map.of("ok", true);
        when(attendance.submitAttendanceDay("today", null, 1L)).thenReturn(result);

        Object response = controller.submitDay("attendance-token", null);

        assertThat(response).isSameAs(result);
        verify(attendance).submitAttendanceDay("today", null, 1L);
    }

    // --- schoolId containsKey coverage: PUT /section-register ---

    @Test
    @SuppressWarnings("unchecked")
    void saveSectionRegister_schoolIdPresentInMap_nonSuperadmin() {
        TenantContext.set(new TenantContext(42L, "admin@test.com", "ADMIN", 7L, null));
        SaveSectionRegisterRequest body = new SaveSectionRegisterRequest("C1", "S1", "2026-02-02", null, 7L, null);
        when(attendance.saveSectionRegister(any())).thenReturn(Map.of("ok", true));

        controller.saveSectionRegister("attendance-token", body);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(attendance).saveSectionRegister(captor.capture());
        assertThat(captor.getValue().get("schoolId")).isEqualTo(7L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveSectionRegister_schoolIdAbsentNotAdded_superadmin() {
        // @BeforeEach sets SUPERADMIN context
        SaveSectionRegisterRequest body = new SaveSectionRegisterRequest("C1", "S1", "2026-02-02", null, null, null);
        when(attendance.saveSectionRegister(any())).thenReturn(Map.of("ok", true));

        controller.saveSectionRegister("attendance-token", body);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(attendance).saveSectionRegister(captor.capture());
        assertThat(captor.getValue()).doesNotContainKey("schoolId");
    }

    // --- schoolId containsKey coverage: POST /daily-entry ---

    @Test
    @SuppressWarnings("unchecked")
    void dailyEntry_schoolIdPresentInMap_nonSuperadmin() {
        TenantContext.set(new TenantContext(42L, "admin@test.com", "ADMIN", 7L, null));
        DailyEntryRequest body = new DailyEntryRequest("C1", "S1", "2026-02-02", null, null, null, 7L);
        when(attendance.saveDailyAttendance(any())).thenReturn(Map.of("ok", true));

        controller.dailyEntry("attendance-token", body);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(attendance).saveDailyAttendance(captor.capture());
        assertThat(captor.getValue().get("schoolId")).isEqualTo(7L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dailyEntry_schoolIdAbsentNotAdded_superadmin() {
        // @BeforeEach sets SUPERADMIN context
        DailyEntryRequest body = new DailyEntryRequest("C1", "S1", "2026-02-02", null, null, null, null);
        when(attendance.saveDailyAttendance(any())).thenReturn(Map.of("ok", true));

        controller.dailyEntry("attendance-token", body);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(attendance).saveDailyAttendance(captor.capture());
        assertThat(captor.getValue()).doesNotContainKey("schoolId");
    }

    // --- schoolId containsKey coverage: POST /submit-section ---

    @Test
    @SuppressWarnings("unchecked")
    void submitSection_schoolIdPresentInMap_nonSuperadmin() {
        TenantContext.set(new TenantContext(42L, "admin@test.com", "ADMIN", 7L, null));
        SubmitSectionRequest body = new SubmitSectionRequest("C1", "S1", "2026-02-02", null, 7L);
        when(attendance.submitAttendanceSection(any())).thenReturn(Map.of("ok", true));

        controller.submitSection("attendance-token", body);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(attendance).submitAttendanceSection(captor.capture());
        assertThat(captor.getValue().get("schoolId")).isEqualTo(7L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void submitSection_schoolIdAbsentNotAdded_superadmin() {
        // @BeforeEach sets SUPERADMIN context
        SubmitSectionRequest body = new SubmitSectionRequest("C1", "S1", "2026-02-02", null, null);
        when(attendance.submitAttendanceSection(any())).thenReturn(Map.of("ok", true));

        controller.submitSection("attendance-token", body);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(attendance).submitAttendanceSection(captor.capture());
        assertThat(captor.getValue()).doesNotContainKey("schoolId");
    }
}
