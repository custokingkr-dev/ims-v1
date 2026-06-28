package com.custoking.ims.studentservice.api;

import com.custoking.ims.studentservice.api.compat.StudentWorkspaceCompatibilityController;
import com.custoking.ims.studentservice.persistence.StudentReadRepository;
import com.custoking.ims.studentservice.persistence.StudentReadRepository.StudentRow;
import com.custoking.ims.studentservice.security.TenantContext;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudentReadControllerTest {

    private final StudentReadRepository students = mock(StudentReadRepository.class);
    private final StudentReadController controller = new StudentReadController(students, "student-token");

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void listRejectsInvalidTokenBeforeQuerying() {
        assertThatThrownBy(() -> controller.list("wrong-token", 4L, "class-9", "section-a", 25))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(students, never()).list(4L, "class-9", "section-a", 25);
        verify(students, never()).count(4L);
    }

    @Test
    void listDelegatesFiltersAndIncludesTotalCount() {
        TenantContext.set(new TenantContext(1L, "admin@x", "SUPERADMIN", null, null));
        StudentRow row = studentRow(101L, "Aarav Sharma");
        when(students.list(4L, "class-9", "section-a", 25)).thenReturn(List.of(row));
        when(students.count(4L)).thenReturn(42L);

        StudentReadController.StudentListResponse response =
                controller.list("student-token", 4L, "class-9", "section-a", 25);

        assertThat(response.content()).containsExactly(row);
        assertThat(response.totalElements()).isEqualTo(42L);
        verify(students).list(4L, "class-9", "section-a", 25);
        verify(students).count(4L);
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
        StudentWorkspaceCompatibilityController compat =
                new StudentWorkspaceCompatibilityController(students, "student-token");
        Map<String, Object> request = Map.of("fullName", "Aarav Sharma");
        when(students.createStudent(request)).thenThrow(new IllegalArgumentException("Admission Number is mandatory"));

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
