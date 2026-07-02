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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
        // All create endpoints require SUPERADMIN; set context so tenant gate passes.
        TenantContext.set(new TenantContext(1L, "sa@custoking.com", "SUPERADMIN", null, null));
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    // ── POST /billing/sa/invoices — blank/missing school ─────────────────────

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

    // ── POST /billing/sa/invoices — valid full body ───────────────────────────

    @Test
    void createInvoice_valid_callsRepoWithAllKeys() throws Exception {
        when(invoices.create(any())).thenReturn(stubInvoiceRow("INV-2025-01"));

        mvc.perform(post("/api/v1/billing/sa/invoices")
                        .header("X-Billing-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"school\":\"Delhi Public School\",\"orderRef\":\"ORD-001\","
                                + "\"schoolId\":4,\"description\":\"Annual platform subscription\","
                                + "\"qty\":2,\"rate\":1000,\"amount\":2000,\"notes\":\"Test note\"}"))
                .andExpect(status().isCreated());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(invoices).create(captor.capture());
        Map<String, Object> body = captor.getValue();
        assertEquals("Delhi Public School", body.get("school"));
        assertEquals("ORD-001", body.get("orderRef"));
        assertEquals(4L, ((Number) body.get("schoolId")).longValue());
        assertEquals("Annual platform subscription", body.get("description"));
        assertEquals(2, ((Number) body.get("qty")).intValue());
        assertEquals(1000L, ((Number) body.get("rate")).longValue());
        assertEquals(2000L, ((Number) body.get("amount")).longValue());
        assertEquals("Test note", body.get("notes"));
    }

    // ── POST /billing/sa/invoices — minimal valid body ────────────────────────

    @Test
    void createInvoice_minimal_callsRepoWithSchoolAndNoOptionalKeys() throws Exception {
        when(invoices.create(any())).thenReturn(stubInvoiceRow("INV-2025-02"));

        mvc.perform(post("/api/v1/billing/sa/invoices")
                        .header("X-Billing-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"school\":\"Demo School\"}"))
                .andExpect(status().isCreated());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(invoices).create(captor.capture());
        Map<String, Object> body = captor.getValue();
        // Required field always present
        assertEquals("Demo School", body.get("school"));
        // Optional fields omitted from JSON must not appear in the forwarded map
        // (the repo defaults qty=1, rate=0, amount=qty*rate when these are absent)
        assertFalse(body.containsKey("rate"), "rate should be absent when not sent; repo defaults to 0");
        assertFalse(body.containsKey("qty"), "qty should be absent when not sent; repo defaults to 1");
        assertFalse(body.containsKey("amount"), "amount should be absent when not sent; repo computes it");
        assertNull(body.get("orderRef"), "orderRef should be absent when not sent");
    }

    // ── POST /billing/sa/invoices — zero-rate (credit/zero-amount invoice) ───

    @Test
    void createInvoice_zeroRate_succeeds() throws Exception {
        // rate=0 must not be rejected; zero-amount and credit invoices are valid.
        when(invoices.create(any())).thenReturn(stubInvoiceRow("INV-2025-03"));

        mvc.perform(post("/api/v1/billing/sa/invoices")
                        .header("X-Billing-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"school\":\"Demo School\",\"rate\":0}"))
                .andExpect(status().isCreated());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(invoices).create(captor.capture());
        // rate=0 is a valid long; controller must forward it as 0, not reject it.
        assertEquals(0L, ((Number) captor.getValue().get("rate")).longValue());
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private InvoiceRow stubInvoiceRow(String id) {
        return new InvoiceRow(
                id, "ORD-001", "Demo School", 4L,
                "Annual platform subscription", 1, 1000L, 1000L,
                120L, 1120L, "Awaiting payment",
                "2026-01-01", "2026-01-15", null,
                OffsetDateTime.parse("2026-01-01T00:00:00Z"));
    }
}
