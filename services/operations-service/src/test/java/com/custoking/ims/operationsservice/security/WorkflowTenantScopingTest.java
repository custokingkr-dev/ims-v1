package com.custoking.ims.operationsservice.security;

import com.custoking.ims.operationsservice.api.WorkflowReadController;
import com.custoking.ims.operationsservice.persistence.WorkflowReadRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkflowTenantScopingTest {

    private final WorkflowReadRepository repo = mock(WorkflowReadRepository.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new WorkflowReadController(repo, "tok"))
            .addFilters(new TenantContextFilter())
            .build();

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void crossTenantSchoolId_isForbidden() throws Exception {
        mvc.perform(get("/api/v1/workflows/instances?schoolId=99")
                        .header("X-Workflow-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10"))
                .andExpect(status().isForbidden());
        verify(repo, never()).instances(anyLong(), any(), any(), anyInt());
    }

    @Test
    void omittedSchoolId_scopesToAuthenticatedSchool() throws Exception {
        when(repo.instances(eq(10L), any(), any(), anyInt())).thenReturn(List.of());
        mvc.perform(get("/api/v1/workflows/instances")
                        .header("X-Workflow-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10"))
                .andExpect(status().isOk());
        verify(repo).instances(eq(10L), any(), any(), anyInt());
    }

    @Test
    void superadmin_canTargetAnySchool() throws Exception {
        when(repo.instances(eq(99L), any(), any(), anyInt())).thenReturn(List.of());
        mvc.perform(get("/api/v1/workflows/instances?schoolId=99")
                        .header("X-Workflow-Service-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());
        verify(repo).instances(eq(99L), any(), any(), anyInt());
    }
}
