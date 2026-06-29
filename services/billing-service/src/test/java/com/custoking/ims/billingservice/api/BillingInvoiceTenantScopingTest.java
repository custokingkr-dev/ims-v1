package com.custoking.ims.billingservice.api;

import com.custoking.ims.billingservice.application.BillingInvoiceService;
import com.custoking.ims.billingservice.security.TenantContext;
import com.custoking.ims.billingservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that BillingInvoiceController enforces the superadmin-only guard at the HTTP layer
 * using a real TenantContextFilter in a standalone MockMvc setup.
 */
class BillingInvoiceTenantScopingTest {

    private final BillingInvoiceService invoices = mock(BillingInvoiceService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new BillingInvoiceController(invoices, "billing-token"))
            .addFilters(new TenantContextFilter())
            .build();

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    @Test
    void nonSuperadmin_isForbiddenOnInvoiceList() throws Exception {
        mvc.perform(get("/api/v1/billing/sa/invoices")
                        .header("X-Billing-Service-Token", "billing-token")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10"))
                .andExpect(status().isForbidden());

        verify(invoices, never()).list(any(), any(), anyInt());
    }

    @Test
    void unauthenticated_isForbiddenOnInvoiceList() throws Exception {
        // No X-Authenticated-Role header → empty TenantContext → not superadmin → 403
        mvc.perform(get("/api/v1/billing/sa/invoices")
                        .header("X-Billing-Service-Token", "billing-token"))
                .andExpect(status().isForbidden());

        verify(invoices, never()).list(any(), any(), anyInt());
    }

    @Test
    void superadmin_canListInvoices() throws Exception {
        when(invoices.list(isNull(), isNull(), anyInt())).thenReturn(List.of());

        mvc.perform(get("/api/v1/billing/sa/invoices")
                        .header("X-Billing-Service-Token", "billing-token")
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());

        verify(invoices).list(isNull(), isNull(), anyInt());
    }

    @Test
    void nonSuperadmin_isForbiddenOnStats() throws Exception {
        mvc.perform(get("/api/v1/billing/sa/invoices/stats")
                        .header("X-Billing-Service-Token", "billing-token")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10"))
                .andExpect(status().isForbidden());

        verify(invoices, never()).stats();
    }

    @Test
    void superadmin_canGetStats() throws Exception {
        when(invoices.stats()).thenReturn(java.util.Map.of("total", 0));

        mvc.perform(get("/api/v1/billing/sa/invoices/stats")
                        .header("X-Billing-Service-Token", "billing-token")
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());

        verify(invoices).stats();
    }
}
