package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.studentexport.StudentExportRepository.SchoolOption;
import com.custoking.ims.schoolcoreservice.studentexport.StudentExportService;
import com.custoking.ims.schoolcoreservice.studentexport.StudentExportService.ExportProgress;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudentExportControllerTest {

    private final StudentExportService exports = mock(StudentExportService.class);
    private final StudentExportController controller = new StudentExportController(exports, "student-token");

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void contextReturnsOnlyRepositoryFilteredAssignedSchools() {
        TenantContext.set(new TenantContext(22L, "ops@example.com", "OPERATIONS", null, null,
                Set.of(7L), Set.of("student:export")));
        when(exports.allowedSchools()).thenReturn(List.of(
                new SchoolOption(7L, "Green Valley School", "GVS", 1000, 980)));

        Map<String, Object> response = controller.context("student-token");

        assertThat(response.get("schools")).asList().hasSize(1);
        verify(exports).allowedSchools();
    }

    @Test
    void contextAllowsSuperadminToUseThePlatformWideSchoolList() {
        TenantContext.set(new TenantContext(1L, "superadmin@example.com", "SUPERADMIN", null, null,
                Set.of(), Set.of()));
        when(exports.allowedSchools()).thenReturn(List.of(
                new SchoolOption(7L, "Green Valley School", "GVS", 1000, 980),
                new SchoolOption(8L, "Lake View School", "LVS", 500, 500)));

        Map<String, Object> response = controller.context("student-token");

        assertThat(response.get("schools")).asList().hasSize(2);
        verify(exports).allowedSchools();
    }

    @Test
    void archiveRejectsSchoolOutsideOperatorAssignmentBeforePreparingData() {
        TenantContext.set(new TenantContext(22L, "ops@example.com", "OPERATIONS", null, null,
                Set.of(7L), Set.of("student:export")));

        assertThatThrownBy(() -> controller.archive("student-token", 8L, mock(HttpServletResponse.class)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        verify(exports, never()).prepare(8L);
    }

    @Test
    void exportRequiresDedicatedPermissionEvenWhenStudentReadExists() {
        TenantContext.set(new TenantContext(22L, "ops@example.com", "OPERATIONS", null, null,
                Set.of(7L), Set.of("student:read")));

        assertThatThrownBy(() -> controller.context("student-token"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(response.getReason()).contains("student:export");
                });
    }

    @Test
    void progressIsScopedToTheOperatorsAssignedSchool() {
        UUID exportId = UUID.randomUUID();
        TenantContext.set(new TenantContext(22L, "ops@example.com", "OPERATIONS", null, null,
                Set.of(7L), Set.of("student:export")));
        ExportProgress expected = new ExportProgress(
                exportId, "STARTED", 45, "PHOTOS", 50, 100, 48, 2, null);
        when(exports.progress(exportId, 7L)).thenReturn(expected);

        assertThat(controller.progress("student-token", exportId, 7L)).isEqualTo(expected);
        verify(exports).progress(exportId, 7L);

        assertThatThrownBy(() -> controller.progress("student-token", exportId, 8L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verify(exports, never()).progress(exportId, 8L);
    }
}
