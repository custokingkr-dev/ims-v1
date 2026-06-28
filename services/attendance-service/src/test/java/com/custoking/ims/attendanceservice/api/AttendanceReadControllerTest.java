package com.custoking.ims.attendanceservice.api;

import com.custoking.ims.attendanceservice.persistence.AttendanceReadRepository;
import com.custoking.ims.attendanceservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        Map<String, Object> request = Map.of("date", "2026-02-02", "schoolId", 4L, "actorId", 9L);
        Map<String, Object> result = Map.of("ok", true, "submitted", 3);
        when(attendance.submitAttendanceDay("2026-02-02", 4L, 9L)).thenReturn(result);

        Object response = controller.submitDay("attendance-token", request);

        assertThat(response).isSameAs(result);
        verify(attendance).submitAttendanceDay("2026-02-02", 4L, 9L);
    }
}
