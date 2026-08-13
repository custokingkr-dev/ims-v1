package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.StudentReadRepository;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import org.springframework.http.MediaType;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StudentTenantScopingTest {

    private final StudentReadRepository repo = mock(StudentReadRepository.class);
    private final com.custoking.ims.schoolcoreservice.infrastructure.ImageUrlFetcher fetcher =
            mock(com.custoking.ims.schoolcoreservice.infrastructure.ImageUrlFetcher.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new StudentReadController(repo, fetcher, "tok"))
            .setControllerAdvice(new ValidationExceptionHandler())
            .addFilters(new TenantContextFilter())
            .build();

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void crossTenantSchoolId_isForbidden() throws Exception {
        mvc.perform(get("/api/v1/students?schoolId=99")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "student:read"))
                .andExpect(status().isForbidden());
        verify(repo, never()).workspaceStudents(anyLong(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void omittedSchoolId_scopesToAuthenticatedSchool() throws Exception {
        when(repo.workspaceStudents(eq(10L), any(), any(), any(), anyInt(), anyInt())).thenReturn(Map.of());
        mvc.perform(get("/api/v1/students")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "student:read"))
                .andExpect(status().isOk());
        verify(repo).workspaceStudents(eq(10L), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void superadmin_canTargetAnySchool() throws Exception {
        when(repo.workspaceStudents(eq(99L), any(), any(), any(), anyInt(), anyInt())).thenReturn(Map.of());
        mvc.perform(get("/api/v1/students?schoolId=99")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());
        verify(repo).workspaceStudents(eq(99L), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void malformedSchoolId_inRequestBody_returns400() throws Exception {
        mvc.perform(post("/api/v1/students")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"abc\"}"))
                .andExpect(status().isBadRequest());
        verify(repo, never()).createStudent(any());
    }

    @Test
    void schoolAdmin_canPermanentlyDeleteStudentWithAdmissionConfirmationHeader() throws Exception {
        when(repo.schoolIdForStudent(42L)).thenReturn(10L);
        when(repo.deleteStudent(42L, "ADM-42"))
                .thenReturn(Map.of("id", 42L, "deleted", true, "permanent", true));

        mvc.perform(delete("/api/v1/students/42")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-User-Id", "7")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "student:delete")
                        .header("X-Student-Delete-Confirmation", "ADM-42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.permanent").value(true));

        verify(repo).schoolIdForStudent(42L);
        verify(repo).deleteStudent(42L, "ADM-42");
    }

    @Test
    void permanentDelete_requiresAdmissionConfirmationHeader() throws Exception {
        when(repo.schoolIdForStudent(42L)).thenReturn(10L);
        mvc.perform(delete("/api/v1/students/42")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "student:delete"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Admission number confirmation is required"));

        verify(repo, never()).deleteStudent(anyLong(), any());
    }

    @Test
    void archiveAndRestoreRoutesAreRemoved() throws Exception {
        mvc.perform(post("/api/v1/students/42/archive")
                        .header("X-Student-Service-Token", "tok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/students/42/restore")
                        .header("X-Student-Service-Token", "tok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }
}
