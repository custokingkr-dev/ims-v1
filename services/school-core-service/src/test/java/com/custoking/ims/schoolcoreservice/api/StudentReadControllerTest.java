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

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
        assertThatThrownBy(() -> controller.list("wrong-token", 4L, "9", "A", null, false, 0, 25))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(students, never()).workspaceStudents(anyLong(), any(), any(), any(), anyInt(), anyInt(), anyBoolean());
    }

    @Test
    void listDelegatesFiltersToWorkspaceStudents() {
        TenantContext.set(new TenantContext(1L, "admin@x", "SUPERADMIN", null, null));
        Map<String, Object> workspace = Map.of("items", List.of(), "filteredCount", 42);
        when(students.workspaceStudents(4L, "9", "A", "Pending", 0, 25, false)).thenReturn(workspace);

        Map<String, Object> response = controller.list("student-token", 4L, "9", "A", "Pending", false, 0, 25);

        assertThat(response).isSameAs(workspace);
        verify(students).workspaceStudents(4L, "9", "A", "Pending", 0, 25, false);
    }

    @Test
    void getReturnsNotFoundForMissingStudent() {
        when(students.schoolIdForStudentIncludingDeleted(404L)).thenThrow(new IllegalArgumentException("student not found"));
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
        TenantContext.set(new TenantContext(1L, "admin@x", "SUPERADMIN", null, null));
        when(students.schoolIdForStudentIncludingDeleted(404L)).thenReturn(4L);
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
        TenantContext.set(new TenantContext(1L, "admin@x", "ADMIN", 10L, null, Set.of(), Set.of("student:import")));

        ResponseEntity<byte[]> response = controller.legacyImportTemplate("student-token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentDisposition().getFilename())
                .isEqualTo("student-import-template.xlsx");
        byte[] body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).startsWith((byte) 'P', (byte) 'K');

        Map<String, String> entries = unzipTextEntries(body);
        assertThat(entries).containsKeys("[Content_Types].xml", "xl/workbook.xml", "xl/worksheets/sheet1.xml");
        assertThat(entries.get("xl/worksheets/sheet1.xml"))
                .contains("Name", "Class", "Section", "AdmissionNo", "Photo", "PhotoUrl");
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
    void workspaceCompatibilityCreateRequiresStudentCreatePermission() {
        TenantContext.set(new TenantContext(1L, "admin@x", "ADMIN", 10L, null, Set.of(), Set.of("student:read")));
        StudentWorkspaceCompatibilityController compat =
                new StudentWorkspaceCompatibilityController(students, "student-token");
        Map<String, Object> request = Map.of(
                "schoolId", 10L,
                "fullName", "Aarav Sharma",
                "admissionNumber", "ADM-1");

        assertThatThrownBy(() -> compat.createFromWorkspace("student-token", request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(response.getReason()).contains("student:create");
                });
        verify(students, never()).createStudent(any());
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

    @Test
    void historyAllowsSoftDeletedStudentsForPreservedLifecycleLookup() {
        TenantContext.set(new TenantContext(1L, "admin@x", "ADMIN", 10L, null, Set.of(), Set.of("student:read")));
        Map<String, Object> history = Map.of(
                "student", Map.of("id", 42L),
                "completedAcademicYears", 1L,
                "historyPreserved", true);
        when(students.schoolIdForStudentIncludingDeleted(42L)).thenReturn(10L);
        when(students.studentHistory(42L)).thenReturn(history);

        Map<String, Object> response = controller.history("student-token", 42L);

        assertThat(response).isSameAs(history);
        verify(students).schoolIdForStudentIncludingDeleted(42L);
        verify(students, never()).schoolIdForStudent(42L);
    }

    @Test
    void restoreRequiresSuperadmin() {
        TenantContext.set(new TenantContext(1L, "admin@x", "ADMIN", 10L, null, Set.of(), Set.of("student:update")));

        assertThatThrownBy(() -> controller.restore("student-token", 42L, Map.of("reason", "Mistake")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(response.getReason()).contains("superadmin");
                });

        verify(students, never()).restoreStudent(anyLong(), any());
        verify(students, never()).schoolIdForStudentIncludingDeleted(anyLong());
    }

    @Test
    void restoreAllowsSuperadminAndIncludesDeletedStudentScope() {
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        Map<String, Object> restored = Map.of("id", 42L, "restored", true);
        when(students.schoolIdForStudentIncludingDeleted(42L)).thenReturn(10L);
        when(students.restoreStudent(42L, Map.of("reason", "Accidental delete"))).thenReturn(restored);

        Map<String, Object> response = controller.restore("student-token", 42L, Map.of("reason", "Accidental delete"));

        assertThat(response).isSameAs(restored);
        verify(students).schoolIdForStudentIncludingDeleted(42L);
        verify(students).restoreStudent(42L, Map.of("reason", "Accidental delete"));
        verify(students, never()).schoolIdForStudent(42L);
    }

    @Test
    void importStatusScopesNonSuperAdminToAuthenticatedSchool() {
        TenantContext.set(new TenantContext(1L, "admin@x", "ADMIN", 10L, null, Set.of(), Set.of("student:import")));
        when(students.importStatus("job-1", 10L)).thenReturn(Map.of("done", true));

        Map<String, Object> response = controller.importStatus("student-token", "job-1");

        assertThat(response).isEqualTo(Map.of("done", true));
        verify(students).importStatus("job-1", 10L);
        verify(students, never()).importStatus("job-1", null);
    }

    @Test
    void importBatchesRequireStudentImportPermission() {
        TenantContext.set(new TenantContext(1L, "admin@x", "ADMIN", 10L, null, Set.of(), Set.of("student:read")));

        assertThatThrownBy(() -> controller.importBatches("student-token", 25))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(response.getReason()).contains("student:import");
                });

        verify(students, never()).importBatches(any(), anyInt());
    }

    private Map<String, String> unzipTextEntries(byte[] body) {
        Map<String, String> entries = new java.util.HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(body))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            return entries;
        } catch (Exception ex) {
            throw new AssertionError("Could not read XLSX template", ex);
        }
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
