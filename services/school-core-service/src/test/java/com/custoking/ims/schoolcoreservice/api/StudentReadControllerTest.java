package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.api.compat.StudentWorkspaceCompatibilityController;
import com.custoking.ims.schoolcoreservice.persistence.StudentReadRepository;
import com.custoking.ims.schoolcoreservice.persistence.StudentReadRepository.StudentRow;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudentReadControllerTest {

    private final StudentReadRepository students = mock(StudentReadRepository.class);
    private final com.custoking.ims.schoolcoreservice.infrastructure.ImageUrlFetcher fetcher =
            mock(com.custoking.ims.schoolcoreservice.infrastructure.ImageUrlFetcher.class);
    private final StudentReadController controller = new StudentReadController(students, fetcher, "student-token");

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void listRejectsInvalidTokenBeforeQuerying() {
        assertThatThrownBy(() -> controller.list("wrong-token", 4L, "9", "A", null, 0, 25))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(students, never()).workspaceStudents(anyLong(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void listDelegatesFiltersToWorkspaceStudents() {
        TenantContext.set(new TenantContext(1L, "admin@x", "SUPERADMIN", null, null));
        Map<String, Object> workspace = Map.of("items", List.of(), "filteredCount", 42);
        when(students.workspaceStudents(4L, "9", "A", "Pending", 0, 25)).thenReturn(workspace);

        Map<String, Object> response = controller.list("student-token", 4L, "9", "A", "Pending", 0, 25);

        assertThat(response).isSameAs(workspace);
        verify(students).workspaceStudents(4L, "9", "A", "Pending", 0, 25);
    }

    @Test
    void getReturnsNotFoundForMissingStudent() {
        when(students.find(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.get("student-token", 404L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(response.getReason()).isEqualTo("student not found");
                });
    }

    @Test
    void workspaceStudentMapsValidationFailureToBadRequest() {
        when(students.workspaceStudentDetail(404L)).thenThrow(new IllegalArgumentException("Student not found"));

        assertThatThrownBy(() -> controller.workspaceStudent("student-token", 404L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(response.getReason()).isEqualTo("Student not found");
                });
    }

    @Test
    void legacyImportTemplateReturnsDownloadResponse() {
        ResponseEntity<byte[]> response = controller.legacyImportTemplate("student-token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentDisposition().getFilename())
                .isEqualTo("student-import-template.xlsx");
        assertThat(new String(response.getBody())).contains("Name,Class,Section,AdmissionNo");
    }

    @Test
    void workspaceCompatibilityCreateMapsValidationFailureToBadRequest() {
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        StudentWorkspaceCompatibilityController compat =
                new StudentWorkspaceCompatibilityController(students, "student-token");
        Map<String, Object> request = Map.of("fullName", "Aarav Sharma");
        when(students.createStudent(org.mockito.ArgumentMatchers.any())).thenThrow(new IllegalArgumentException("Admission Number is mandatory"));

        assertThatThrownBy(() -> compat.createFromWorkspace("student-token", request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(response.getReason()).isEqualTo("Admission Number is mandatory");
                });
    }

    @Test
    void workspaceCompatibilityClassSectionRouteDelegates() {
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        StudentWorkspaceCompatibilityController compat =
                new StudentWorkspaceCompatibilityController(students, "student-token");
        StudentRow row = studentRow(102L, "Diya Rao");
        when(students.list(4L, "class-10", "section-b", 100)).thenReturn(List.of(row));

        Object response = compat.studentsForClassSection("student-token", "class-10", "section-b", 4L, 100);

        assertThat(response).isEqualTo(List.of(row));
        verify(students).list(4L, "class-10", "section-b", 100);
    }

    private StudentRow studentRow(Long id, String fullName) {
        return new StudentRow(
                id,
                "ADM-" + id,
                "1",
                "BRN-" + id,
                fullName,
                LocalDate.parse("2010-05-12"),
                "Female",
                "Parent",
                "9876543210",
                "Mother",
                "9876543210",
                "Hyderabad",
                "12",
                "Main Road",
                "Central",
                "Hyderabad",
                "Telangana",
                "500001",
                null,
                "Pending",
                95.5,
                null,
                null,
                null,
                null,
                4L,
                "class-9",
                "section-a",
                "2026",
                null);
    }
}
