package com.custoking.ims.schoolcoreservice.api.compat;

import com.custoking.ims.schoolcoreservice.persistence.StudentReadRepository;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StudentWorkspaceCompatibilityControllerTest {

    private final StudentReadRepository repo = mock(StudentReadRepository.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new StudentWorkspaceCompatibilityController(repo, "tok"))
            .addFilters(new TenantContextFilter())
            .build();

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    // ---- createFromWorkspace POST /api/v1/workspace/students ----

    @Test
    void createStudent_crossTenantSchoolId_isForbidden() throws Exception {
        mvc.perform(post("/api/v1/workspace/students")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "student:create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":99,\"fullName\":\"Alice\",\"admissionNumber\":\"A001\"}"))
                .andExpect(status().isForbidden());
        verify(repo, never()).createStudent(any());
    }

    @Test
    void createStudent_ownSchool_scopedToAuthenticatedSchool() throws Exception {
        when(repo.createStudent(argThat(m -> Long.valueOf(10L).equals(m.get("schoolId")))))
                .thenReturn(Map.of("id", 1L));
        mvc.perform(post("/api/v1/workspace/students")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "student:create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":10,\"fullName\":\"Alice\",\"admissionNumber\":\"A001\"}"))
                .andExpect(status().isOk());
        verify(repo).createStudent(argThat(m -> Long.valueOf(10L).equals(m.get("schoolId"))));
    }

    @Test
    void createStudent_superadmin_passesThrough() throws Exception {
        when(repo.createStudent(argThat(m -> Long.valueOf(99L).equals(m.get("schoolId")))))
                .thenReturn(Map.of("id", 2L));
        mvc.perform(post("/api/v1/workspace/students")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":99,\"fullName\":\"Bob\",\"admissionNumber\":\"A002\"}"))
                .andExpect(status().isOk());
        verify(repo).createStudent(argThat(m -> Long.valueOf(99L).equals(m.get("schoolId"))));
    }

    // ---- studentsForClassSection GET /api/v1/classes/{classId}/sections/{sectionId}/students ----

    @Test
    void listStudents_crossTenantSchoolId_isForbidden() throws Exception {
        mvc.perform(get("/api/v1/classes/c1/sections/s1/students")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "student:read")
                        .param("schoolId", "99"))
                .andExpect(status().isForbidden());
        verify(repo, never()).list(anyLong(), any(), any(), anyInt());
    }

    @Test
    void listStudents_omittedSchoolId_scopesToAuthenticatedSchool() throws Exception {
        when(repo.list(eq(10L), eq("c1"), eq("s1"), anyInt())).thenReturn(List.of());
        mvc.perform(get("/api/v1/classes/c1/sections/s1/students")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "student:read"))
                .andExpect(status().isOk());
        verify(repo).list(eq(10L), eq("c1"), eq("s1"), anyInt());
    }

    @Test
    void listStudents_superadmin_canTargetAnySchool() throws Exception {
        when(repo.list(eq(99L), any(), any(), anyInt())).thenReturn(List.of());
        mvc.perform(get("/api/v1/classes/c1/sections/s1/students")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .param("schoolId", "99"))
                .andExpect(status().isOk());
        verify(repo).list(eq(99L), any(), any(), anyInt());
    }

    // ---- updateReviewItem PUT /api/v1/student-review-items/{itemId} ----

    @Test
    void updateReviewItem_crossTenantItem_isForbidden() throws Exception {
        // Item belongs to school 99; authenticated as school 10 → 403
        when(repo.schoolIdForReviewItem("RV-9")).thenReturn(99L);
        mvc.perform(put("/api/v1/student-review-items/RV-9")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "student:update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isForbidden());
        verify(repo, never()).updateReviewItem(any(), any());
    }

    @Test
    void updateReviewItem_ownSchool_invokesRepo() throws Exception {
        // Item belongs to school 10; authenticated as school 10 → OK, schoolId injected as 10
        when(repo.schoolIdForReviewItem("RV-9")).thenReturn(10L);
        when(repo.updateReviewItem(eq("RV-9"), argThat(m -> Long.valueOf(10L).equals(m.get("schoolId")))))
                .thenReturn(Map.of("ok", true));
        mvc.perform(put("/api/v1/student-review-items/RV-9")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "student:update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk());
        verify(repo).updateReviewItem(eq("RV-9"), argThat(m -> Long.valueOf(10L).equals(m.get("schoolId"))));
    }

    @Test
    void updateReviewItem_superadmin_widensToItemSchool() throws Exception {
        // Superadmin: itemSchool=99, scope=99 (widened)
        when(repo.schoolIdForReviewItem("RV-9")).thenReturn(99L);
        when(repo.updateReviewItem(eq("RV-9"), argThat(m -> Long.valueOf(99L).equals(m.get("schoolId")))))
                .thenReturn(Map.of("ok", true));
        mvc.perform(put("/api/v1/student-review-items/RV-9")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk());
        verify(repo).updateReviewItem(eq("RV-9"), argThat(m -> Long.valueOf(99L).equals(m.get("schoolId"))));
    }

    @Test
    void updateReviewItemRejectsMissingToken() {
        // Token check happens before TenantScope - no context needed
        var controller = new StudentWorkspaceCompatibilityController(repo, "tok");
        assertThatThrownBy(() -> controller.updateReviewItem(null, "RV-9", Map.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid student service token");
        verify(repo, never()).schoolIdForReviewItem(any());
    }

    @Test
    void updateReviewItemMapsIllegalArgumentTo400() throws Exception {
        // Superadmin to avoid tenant scope complexity, repo throws IAE → 400
        when(repo.schoolIdForReviewItem("RV-9")).thenReturn(10L);
        when(repo.updateReviewItem(eq("RV-9"), any())).thenThrow(new IllegalArgumentException("Review item not found"));
        mvc.perform(put("/api/v1/student-review-items/RV-9")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isBadRequest());
    }
}
