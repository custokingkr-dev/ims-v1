package com.custoking.ims.schoolcoreservice.api.compat;

import com.custoking.ims.schoolcoreservice.persistence.StudentReadRepository;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code PUT /api/v1/workspace/students/{id}} — the edit form behind the student detail modal.
 * Gate ({@code student:update}), school-scope resolution, and error mapping. The repository is
 * mocked; the real {@link TenantContextFilter} populates the context from gateway headers.
 */
class StudentUpdateControllerTest {

    private static final String BODY =
            "{\"fullName\":\"Asha Rao\",\"admissionNumber\":\"ADM-1\",\"classId\":\"c2\",\"sectionId\":\"s2\",\"phone\":\"9000000000\"}";

    private final StudentReadRepository repo = mock(StudentReadRepository.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new StudentWorkspaceCompatibilityController(repo, "tok"))
            .addFilters(new TenantContextFilter())
            .build();

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void update_isGatedOnStudentUpdate() throws Exception {
        mvc.perform(put("/api/v1/workspace/students/7")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-User-Id", "42")
                        .header("X-Authenticated-Role", "TEACHER")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "student:read,student:create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());
        verify(repo, never()).updateStudent(anyLong(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void update_withPermission_scopesToTheAuthenticatedSchool_andPassesTheClassSectionTransfer() throws Exception {
        when(repo.updateStudent(eq(7L), any())).thenReturn(Map.of("id", 7L, "classId", "c2", "sectionId", "s2"));

        mvc.perform(put("/api/v1/workspace/students/7")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-User-Id", "42")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "student:update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classId").value("c2"))
                .andExpect(jsonPath("$.sectionId").value("s2"));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(repo).updateStudent(eq(7L), captor.capture());
        assertThat(captor.getValue())
                .containsEntry("schoolId", 10L)
                .containsEntry("classId", "c2")
                .containsEntry("sectionId", "s2")
                .containsEntry("admissionNumber", "ADM-1");
    }

    @Test
    void update_crossTenantSchoolIdInBody_isForbidden() throws Exception {
        mvc.perform(put("/api/v1/workspace/students/7")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-User-Id", "42")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "student:update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":99,\"fullName\":\"Asha\",\"admissionNumber\":\"ADM-1\"}"))
                .andExpect(status().isForbidden());
        verify(repo, never()).updateStudent(anyLong(), any());
    }

    @Test
    void update_nonNumericSchoolId_isBadRequest() throws Exception {
        mvc.perform(put("/api/v1/workspace/students/7")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-User-Id", "42")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "student:update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"ten\",\"fullName\":\"Asha\",\"admissionNumber\":\"ADM-1\"}"))
                .andExpect(status().isBadRequest());
        verify(repo, never()).updateStudent(anyLong(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void update_superadmin_passesTheRequestedSchoolThrough() throws Exception {
        when(repo.updateStudent(eq(7L), any())).thenReturn(Map.of("id", 7L));

        mvc.perform(put("/api/v1/workspace/students/7")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-User-Id", "1")
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":99,\"fullName\":\"Asha\",\"admissionNumber\":\"ADM-1\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(repo).updateStudent(eq(7L), captor.capture());
        assertThat(captor.getValue()).containsEntry("schoolId", 99L);
    }

    @Test
    void update_validationFailure_isBadRequest() throws Exception {
        when(repo.updateStudent(eq(7L), any())).thenThrow(new IllegalArgumentException("Admission Number already exists"));

        mvc.perform(put("/api/v1/workspace/students/7")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-User-Id", "42")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "student:update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update_ofAnotherSchoolsStudent_isForbidden() throws Exception {
        // Repository-level guard: the student's school differs from the resolved scope.
        when(repo.updateStudent(eq(7L), any())).thenThrow(new SecurityException("You do not have access to this student"));

        mvc.perform(put("/api/v1/workspace/students/7")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-User-Id", "42")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "student:update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void update_withoutServiceToken_isUnauthorized_beforeAnyTenantCheck() throws Exception {
        mvc.perform(put("/api/v1/workspace/students/7")
                        .header("X-Authenticated-User-Id", "42")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "student:update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());
        verify(repo, never()).updateStudent(anyLong(), any());
    }
}
