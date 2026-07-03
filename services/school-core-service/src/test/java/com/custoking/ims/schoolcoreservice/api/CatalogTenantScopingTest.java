package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.CatalogReadRepository;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CatalogTenantScopingTest {

    private final CatalogReadRepository repo = mock(CatalogReadRepository.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new CatalogReadController(repo, "tok"))
            .addFilters(new TenantContextFilter())
            .build();

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    // --- orders endpoint ---

    @Test
    void crossTenantSchoolId_isForbidden() throws Exception {
        mvc.perform(get("/api/v1/catalog/orders?schoolId=99")
                        .header("X-Catalog-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10"))
                .andExpect(status().isForbidden());
        verify(repo, never()).orders(anyLong(), any(), anyInt());
    }

    @Test
    void omittedSchoolId_scopesToAuthenticatedSchool() throws Exception {
        when(repo.orders(eq(10L), isNull(), eq(100))).thenReturn(List.of());
        mvc.perform(get("/api/v1/catalog/orders")
                        .header("X-Catalog-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10"))
                .andExpect(status().isOk());
        verify(repo).orders(eq(10L), isNull(), eq(100));
    }

    @Test
    void superadmin_canTargetAnySchool() throws Exception {
        when(repo.orders(eq(99L), isNull(), eq(100))).thenReturn(List.of());
        mvc.perform(get("/api/v1/catalog/orders?schoolId=99")
                        .header("X-Catalog-Service-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());
        verify(repo).orders(eq(99L), isNull(), eq(100));
    }

    // --- pendingApprovalOrders endpoint (superadmin-only) ---

    @Test
    void pendingApprovalOrders_nonSuperadmin_isForbidden() throws Exception {
        mvc.perform(get("/api/v1/catalog/orders/pending-approval")
                        .header("X-Catalog-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10"))
                .andExpect(status().isForbidden());
        verify(repo, never()).pendingApprovalOrders(anyInt());
    }

    @Test
    void pendingApprovalOrders_superadmin_isAllowed() throws Exception {
        when(repo.pendingApprovalOrders(anyInt())).thenReturn(List.of());
        mvc.perform(get("/api/v1/catalog/orders/pending-approval")
                        .header("X-Catalog-Service-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());
        verify(repo).pendingApprovalOrders(100);
    }
}
