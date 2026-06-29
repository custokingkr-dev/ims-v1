package com.custoking.ims.firefightingservice.api;

import com.custoking.ims.firefightingservice.persistence.FirefightingReadRepository;
import com.custoking.ims.firefightingservice.security.TenantContext;
import com.custoking.ims.firefightingservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FirefightingTenantScopingTest {

    private final FirefightingReadRepository repo = mock(FirefightingReadRepository.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new FirefightingReadController(repo, "tok"))
            .addFilters(new TenantContextFilter())
            .build();

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    // ── requests: Recipe A ─────────────────────────────────────────────────

    @Test
    void requests_crossTenantSchoolId_isForbidden() throws Exception {
        mvc.perform(get("/api/v1/ff/requests?schoolId=99")
                        .header("X-Firefighting-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10"))
                .andExpect(status().isForbidden());
        verify(repo, never()).requests(anyLong(), any(), anyInt());
    }

    @Test
    void requests_omittedSchoolId_scopesToAuthenticatedSchool() throws Exception {
        when(repo.requests(eq(10L), any(), anyInt())).thenReturn(List.of());
        mvc.perform(get("/api/v1/ff/requests")
                        .header("X-Firefighting-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10"))
                .andExpect(status().isOk());
        verify(repo).requests(eq(10L), any(), anyInt());
    }

    @Test
    void requests_superadmin_canTargetAnySchool() throws Exception {
        when(repo.requests(eq(99L), any(), anyInt())).thenReturn(List.of());
        mvc.perform(get("/api/v1/ff/requests?schoolId=99")
                        .header("X-Firefighting-Service-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());
        verify(repo).requests(eq(99L), any(), anyInt());
    }

    // ── createRequest: Recipe B ────────────────────────────────────────────

    @Test
    void create_crossTenantSchoolId_isForbidden() throws Exception {
        mvc.perform(post("/api/v1/ff/requests")
                        .header("X-Firefighting-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Test\",\"schoolId\":99}"))
                .andExpect(status().isForbidden());
        verify(repo, never()).createRequest(anyMap());
    }

    @Test
    void create_superadmin_canCreateForAnySchool() throws Exception {
        when(repo.createRequest(anyMap())).thenReturn(Map.of("code", "FF-001", "status", "DRAFT"));
        mvc.perform(post("/api/v1/ff/requests")
                        .header("X-Firefighting-Service-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Test\",\"schoolId\":99}"))
                .andExpect(status().isOk());
        verify(repo).createRequest(argThat(m -> Long.valueOf(99L).equals(m.get("schoolId"))));
    }

    // ── approveCustoking: requireSuperAdmin ────────────────────────────────

    @Test
    void approveCustoking_nonSuperadmin_isForbidden() throws Exception {
        mvc.perform(post("/api/v1/ff/requests/FF-001/approve-custoking")
                        .header("X-Firefighting-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10"))
                .andExpect(status().isForbidden());
        verify(repo, never()).approveCustoking(anyString());
    }

    @Test
    void approveCustoking_superadmin_isAllowed() throws Exception {
        when(repo.approveCustoking("FF-001")).thenReturn(Map.of("code", "FF-001", "status", "CUSTOKING_APPROVED"));
        mvc.perform(post("/api/v1/ff/requests/FF-001/approve-custoking")
                        .header("X-Firefighting-Service-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());
        verify(repo).approveCustoking("FF-001");
    }
}
