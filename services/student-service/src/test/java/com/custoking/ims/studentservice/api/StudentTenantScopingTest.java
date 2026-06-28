package com.custoking.ims.studentservice.api;

import com.custoking.ims.studentservice.persistence.StudentReadRepository;
import com.custoking.ims.studentservice.security.TenantContext;
import com.custoking.ims.studentservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        verify(repo, never()).list(anyLong(), any(), any(), anyInt());
    }

    @Test
    void omittedSchoolId_scopesToAuthenticatedSchool() throws Exception {
        when(repo.list(eq(10L), any(), any(), anyInt())).thenReturn(List.of());
        when(repo.count(10L)).thenReturn(0L);
        mvc.perform(get("/api/v1/students")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10"))
                .andExpect(status().isOk());
        verify(repo).list(eq(10L), any(), any(), anyInt());
        verify(repo).count(10L);
    }

    @Test
    void superadmin_canTargetAnySchool() throws Exception {
        when(repo.list(eq(99L), any(), any(), anyInt())).thenReturn(List.of());
        when(repo.count(99L)).thenReturn(0L);
        mvc.perform(get("/api/v1/students?schoolId=99")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());
        verify(repo).list(eq(99L), any(), any(), anyInt());
    }
}
