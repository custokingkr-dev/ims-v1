package com.custoking.ims.operationsservice.api;

import com.custoking.ims.operationsservice.api.compat.FirefightingPublicCompatibilityController;
import com.custoking.ims.operationsservice.persistence.FirefightingReadRepository;
import com.custoking.ims.operationsservice.security.TenantContext;
import com.custoking.ims.operationsservice.security.TenantContextFilter;
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
    private final MockMvc compatMvc = MockMvcBuilders
            .standaloneSetup(new FirefightingPublicCompatibilityController(repo, "tok"))
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
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "firefighting:read"))
                .andExpect(status().isForbidden());
        verify(repo, never()).requests(anyLong(), any(), anyInt());
    }

    @Test
    void requests_omittedSchoolId_scopesToAuthenticatedSchool() throws Exception {
        when(repo.requests(eq(10L), any(), anyInt())).thenReturn(List.of());
        mvc.perform(get("/api/v1/ff/requests")
                        .header("X-Firefighting-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "firefighting:read"))
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
                        .header("X-Authenticated-Permissions", "firefighting:create")
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

    // ── markVendorPaid: Recipe B (cross-tenant write closure) ──────────────

    @Test
    void markVendorPaid_omittedSchoolId_scopesToAuthenticatedSchool() throws Exception {
        when(repo.markVendorPaid(eq("FF-001"), anyMap()))
                .thenReturn(Map.of("code", "FF-001", "status", "VENDOR_PAID"));
        mvc.perform(post("/api/v1/ff/requests/FF-001/vendor-paid")
                        .header("X-Firefighting-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "firefighting:update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        // schoolId must be forced to authenticated school (10), not null/omitted
        verify(repo).markVendorPaid(eq("FF-001"), argThat(m -> Long.valueOf(10L).equals(m.get("schoolId"))));
    }

    @Test
    void markVendorPaid_crossTenantSchoolId_isForbidden() throws Exception {
        mvc.perform(post("/api/v1/ff/requests/FF-001/vendor-paid")
                        .header("X-Firefighting-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "firefighting:update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":99}"))
                .andExpect(status().isForbidden());
        verify(repo, never()).markVendorPaid(anyString(), anyMap());
    }

    @Test
    void compatMarkVendorPaid_omittedSchoolId_scopesToAuthenticatedSchool() throws Exception {
        when(repo.markVendorPaid(eq("FF-001"), anyMap()))
                .thenReturn(Map.of("code", "FF-001", "status", "VENDOR_PAID"));
        compatMvc.perform(post("/api/v1/dashboard/vendor-dues/firefighting/FF-001/mark-paid")
                        .header("X-Firefighting-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "firefighting:update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        verify(repo).markVendorPaid(eq("FF-001"), argThat(m -> Long.valueOf(10L).equals(m.get("schoolId"))));
    }

    @Test
    void compatMarkVendorPaid_crossTenantSchoolId_isForbidden() throws Exception {
        compatMvc.perform(post("/api/v1/dashboard/vendor-dues/firefighting/FF-001/mark-paid")
                        .header("X-Firefighting-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "firefighting:update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":99}"))
                .andExpect(status().isForbidden());
        verify(repo, never()).markVendorPaid(anyString(), anyMap());
    }

    // ── approveCustoking: requireSuperAdmin ────────────────────────────────

    @Test
    void approveCustoking_nonSuperadmin_isForbidden() throws Exception {
        mvc.perform(post("/api/v1/ff/requests/FF-001/approve-custoking")
                        .header("X-Firefighting-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "firefighting:fulfill"))
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
