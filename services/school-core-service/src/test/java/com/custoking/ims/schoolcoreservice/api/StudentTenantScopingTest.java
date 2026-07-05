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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StudentTenantScopingTest {

    private final StudentReadRepository repo = mock(StudentReadRepository.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new StudentReadController(repo, "tok"))
            .addFilters(new TenantContextFilter())
            .build();

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void crossTenantSchoolId_isForbidden() throws Exception {
        mvc.perform(get("/api/v1/students?schoolId=99")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10"))
                .andExpect(status().isForbidden());
        verify(repo, never()).workspaceStudents(anyLong(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void omittedSchoolId_scopesToAuthenticatedSchool() throws Exception {
        when(repo.workspaceStudents(eq(10L), any(), any(), any(), anyInt(), anyInt())).thenReturn(Map.of());
        mvc.perform(get("/api/v1/students")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10"))
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
}
