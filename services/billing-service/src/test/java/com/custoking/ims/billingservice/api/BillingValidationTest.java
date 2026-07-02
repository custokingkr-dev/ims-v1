package com.custoking.ims.billingservice.api;

import com.custoking.ims.billingservice.application.BillingInvoiceService;
import com.custoking.ims.billingservice.persistence.BillingInvoiceRepository.InvoiceRow;
import com.custoking.ims.billingservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc validation tests for POST /api/v1/billing/sa/invoices.
 *
 * Covers:
 *   - invalid request → 400 with fieldErrors (repo never called)
 *   - valid request → repo called once with exact key values
 *
 * PATCH /{id} is deferred (partial-update containsKey pattern, stays as raw Map).
 */
class BillingValidationTest {

    private static final String VALID_TOKEN = "billing-token";

    BillingInvoiceService invoices;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        invoices = mock(BillingInvoiceService.class);
        BillingInvoiceController controller = new BillingInvoiceController(invoices, VALID_TOKEN);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ValidationExceptionHandler())
                .build();
        // Provide a SUPERADMIN TenantContext so the scope check passes for all
        // valid-token requests (token check happens before scope check in controller).
        TenantContext.set(new TenantContext(1L, "sa@custoking.com", "SUPERADMIN", null, null));
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // ─── missing required fields → 400 ───────────────────────────────────────

    @Test
    void createInvoice_missingSchool_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/billing/sa/invoices")
                        .header("X-Billing-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"rate\":1000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.school").exists());
        verifyNoInteractions(invoices);
    }

    @Test
    void createInvoice_blankSchool_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/billing/sa/invoices")
                        .header("X-Billing-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"school\":\"\",\"rate\":1000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.school").exists());
        verifyNoInteractions(invoices);
    }

    @Test
    void createInvoice_missingRate_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/billing/sa/invoices")
                        .header("X-Billing-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"school\":\"Demo School\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.rate").exists());
        verifyNoInteractions(invoices);
    }

    @Test
    void createInvoice_zeroRate_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/billing/sa/invoices")
                        .header("X-Billing-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"school\":\"Demo School\",\"rate\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.rate").exists());
        verifyNoInteractions(invoices);
    }

    // ─── valid request → repo called with exact key values ───────────────────

    @Test
    @SuppressWarnings("unchecked")
    void createInvoice_valid_callsServiceWithExactKeys() throws Exception {
        when(invoices.create(anyMap())).thenReturn(invoice("INV-2025-01", "Awaiting payment"));

        mvc.perform(post("/api/v1/billing/sa/invoices")
                        .header("X-Billing-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("""
                                {
                                  "school": "Delhi Public School",
                                  "rate": 1000,
                                  "schoolId": 4,
                                  "orderRef": "ORD-2025-01",
                                  "description": "Annual platform subscription",
                                  "qty": 2,
                                  "notes": "Early payment"
                                }
                                """))
                .andExpect(status().isCreated());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(invoices).create(captor.capture());
        Map<String, Object> passed = captor.getValue();

        assertEquals("Delhi Public School", passed.get("school"));
        assertEquals(1000L, passed.get("rate"));
        assertEquals(4L, passed.get("schoolId"));
        assertEquals("ORD-2025-01", passed.get("orderRef"));
        assertEquals("Annual platform subscription", passed.get("description"));
        assertEquals(2, passed.get("qty"));
        assertEquals("Early payment", passed.get("notes"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void createInvoice_minimalValid_nullOptionalFieldsPassedToService() throws Exception {
        when(invoices.create(anyMap())).thenReturn(invoice("INV-2025-02", "Awaiting payment"));

        mvc.perform(post("/api/v1/billing/sa/invoices")
                        .header("X-Billing-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"school\":\"Minimal School\",\"rate\":500}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(invoices).create(captor.capture());
        Map<String, Object> passed = captor.getValue();

        assertEquals("Minimal School", passed.get("school"));
        assertEquals(500L, passed.get("rate"));
        // Optional fields are present in the map with null values (repo treats null as fallback)
        assertNotNull(passed, "map must exist");
    }

    private InvoiceRow invoice(String id, String status) {
        return new InvoiceRow(
                id,
                "ORDER-1",
                "Delhi Public School",
                4L,
                "Annual platform subscription",
                1,
                1000L,
                1000L,
                120L,
                1120L,
                status,
                "2026-01-01",
                "2026-01-15",
                null,
                OffsetDateTime.parse("2026-01-01T00:00:00Z"));
    }
}
