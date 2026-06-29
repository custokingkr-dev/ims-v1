package com.custoking.ims.tenantschoolservice.api.compat;

import com.custoking.ims.tenantschoolservice.persistence.SchoolStructureReadRepository;
import com.custoking.ims.tenantschoolservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantSchoolPublicCompatibilityControllerTest {

    private final SchoolStructureReadRepository schools = mock(SchoolStructureReadRepository.class);
    private final TenantSchoolPublicCompatibilityController controller =
            new TenantSchoolPublicCompatibilityController(schools, "tok");

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void addsStaffUsingSchoolIdFromBody() {
        // Superadmin context; body schoolId 5 is resolved and passed through.
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        Map<String, Object> request = Map.of("schoolId", 5, "name", "Asha");
        when(schools.addStaff(eq(5L), eq(request))).thenReturn(Map.of("id", 1));

        assertThat(controller.addStaffFromWorkspace("tok", request)).containsEntry("id", 1);
        verify(schools).addStaff(5L, request);
    }

    @Test
    void rejectsMissingSchoolId() {
        // Superadmin with no schoolId in body → resolveSchoolId(null) returns null → 400
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        Map<String, Object> request = Map.of("name", "Asha");

        assertThatThrownBy(() -> controller.addStaffFromWorkspace("tok", request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verify(schools, never()).addStaff(null, request);
    }

    @Test
    void rejectsInvalidToken() {
        Map<String, Object> request = Map.of("schoolId", 5, "name", "Asha");

        assertThatThrownBy(() -> controller.addStaffFromWorkspace("bad", request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(schools, never()).addStaff(5L, request);
    }

    @Test
    void addsTimetableUsingAuthenticatedSchoolHeader() {
        // TenantContextFilter (in production) populates schoolId=5 from X-Authenticated-School-Id header.
        // In direct controller tests, we set TenantContext manually; resolveSchoolId(null) returns auth school.
        TenantContext.set(new TenantContext(1L, "admin@x", "ADMIN", 5L, null));
        Map<String, Object> request = Map.of(
                "day", "Monday",
                "period", "P1",
                "classSection", "9-B",
                "subject", "Math",
                "teacher", "Asha");
        when(schools.addTimetableEntry(eq(5L), eq(request))).thenReturn(Map.of("id", "1"));

        assertThat(controller.addTimetableFromWorkspace("tok", "5", request)).containsEntry("id", "1");
        verify(schools).addTimetableEntry(5L, request);
    }

    @Test
    void timetableRequiresSchoolScope() {
        // Superadmin with no schoolId in body/context → resolveSchoolId(null) returns null → 400
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        Map<String, Object> request = Map.of("day", "Monday", "period", "P1", "classSection", "9-B");

        assertThatThrownBy(() -> controller.addTimetableFromWorkspace("tok", null, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verify(schools, never()).addTimetableEntry(null, request);
    }
}
