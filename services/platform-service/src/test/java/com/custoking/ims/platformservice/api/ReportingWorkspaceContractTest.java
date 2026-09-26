package com.custoking.ims.platformservice.api;

import com.custoking.ims.platformservice.api.compat.ReportingPublicCompatibilityController;
import com.custoking.ims.platformservice.persistence.ReportingReadRepository;
import com.custoking.ims.platformservice.persistence.ReportingCommandRepository;
import com.custoking.ims.platformservice.security.TenantContext;
import com.custoking.ims.platformservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReportingWorkspaceContractTest {
    private final ReportingReadRepository repo = mock(ReportingReadRepository.class);
    private final ReportingCommandRepository commands = mock(ReportingCommandRepository.class);
    private final ReportingReadController canonical = new ReportingReadController(repo, commands, "tok");
    private final ReportingPublicCompatibilityController legacy = new ReportingPublicCompatibilityController(repo, commands, "tok");
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(canonical).addFilters(new TenantContextFilter()).build();
    @AfterEach void cleanup() { TenantContext.clear(); }
    @Test void canonicalAndCompatibilityShareTheWholeWorkspaceViewIncludingDefaults() {
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        when(repo.workspaceDashboardSummary(10L)).thenReturn(Map.of("students", 12, "feeCollectedPaise", 2500, "feeTargetPaise", 10000));
        Map<String,Object> result = canonical.workspace("tok", 10L);
        assertThat(result).isEqualTo(legacy.workspace("tok", 10L));
        assertThat(result).containsKeys("school", "dashboard", "students", "attendance", "fees", "annualPlan");
        assertThat(((Map<?,?>) result.get("fees")).containsKey("summary")).isTrue();
        assertThat(((Map<?,?>) result.get("school")).get("timeZone")).isEqualTo("Asia/Kolkata");
    }
    @Test void workspaceResolvesSchoolAndRejectsForgedScopeMissingPermissionAndToken() throws Exception {
        when(repo.workspaceDashboardSummary(10L)).thenReturn(Map.of("students", 12));
        mvc.perform(get("/api/v1/reporting/workspace").header("X-Reporting-Service-Token", "tok")
            .header("X-Authenticated-Role", "ADMIN").header("X-Authenticated-School-Id", "10")
            .header("X-Authenticated-Permissions", "report:read"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.dashboard.students").value(12));
        verify(repo).workspaceDashboardSummary(10L);
        clearInvocations(repo);
        mvc.perform(get("/api/v1/reporting/workspace?schoolId=99").header("X-Reporting-Service-Token", "tok")
            .header("X-Authenticated-Role", "ADMIN").header("X-Authenticated-School-Id", "10")
            .header("X-Authenticated-Permissions", "report:read")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/reporting/workspace").header("X-Reporting-Service-Token", "tok")
            .header("X-Authenticated-Role", "ADMIN").header("X-Authenticated-School-Id", "10"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/reporting/workspace").header("X-Reporting-Service-Token", "wrong")
            .header("X-Authenticated-Role", "SUPERADMIN")).andExpect(status().isUnauthorized());
        verifyNoInteractions(repo);
    }
}
