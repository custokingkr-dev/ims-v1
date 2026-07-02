package com.custoking.ims.billingservice.api;

import com.custoking.ims.billingservice.api.dto.CreateInvoiceRequest;
import com.custoking.ims.billingservice.application.BillingInvoiceService;
import com.custoking.ims.billingservice.persistence.BillingInvoiceRepository.InvoiceRow;
import com.custoking.ims.billingservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingInvoiceControllerTest {

    private final BillingInvoiceService invoices = mock(BillingInvoiceService.class);
    private final BillingInvoiceController controller = new BillingInvoiceController(invoices, "billing-token");

    @BeforeEach
    void setSuperAdminContext() {
        // All endpoints in BillingInvoiceController are superadmin-only (/sa/ paths).
        // Pre-existing tests exercise the token-check and service delegation; they need a
        // SUPERADMIN TenantContext so the gate passes before reaching those assertions.
        TenantContext.set(new TenantContext(1L, "sa@custoking.com", "SUPERADMIN", null, null));
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void listRejectsInvalidTokenBeforeDelegating() {
        assertThatThrownBy(() -> controller.list("wrong-token", 4L, "Paid", 10))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(invoices, never()).list(4L, "Paid", 10);
    }

    @Test
    void byIdReturnsNotFoundWhenInvoiceDoesNotExist() {
        when(invoices.byId("missing")).thenReturn(null);

        assertThatThrownBy(() -> controller.byId("billing-token", "missing"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createRequiresWriteTokenAndDelegatesPayload() {
        // Controller now accepts a typed DTO; the map it passes to the service
        // is built from DTO fields — use anyMap() for stubbing and a captor to
        // assert exact key values.
        InvoiceRow invoice = invoice("INV-2025-01", "Awaiting payment");
        when(invoices.create(anyMap())).thenReturn(invoice);

        CreateInvoiceRequest dto = new CreateInvoiceRequest(
                "Delhi Public School",   // school (required)
                1000L,                   // rate  (required)
                null,                    // orderRef
                4L,                      // schoolId
                "Annual platform subscription", // description
                1,                       // qty
                null,                    // amount (computed by repo)
                null                     // notes
        );
        Object response = controller.create("billing-token", dto);

        assertThat(response).isSameAs(invoice);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(invoices).create(captor.capture());
        Map<String, Object> passed = captor.getValue();
        assertThat(passed.get("school")).isEqualTo("Delhi Public School");
        assertThat(passed.get("rate")).isEqualTo(1000L);
        assertThat(passed.get("schoolId")).isEqualTo(4L);
        assertThat(passed.get("description")).isEqualTo("Annual platform subscription");
        assertThat(passed.get("qty")).isEqualTo(1);
    }

    @Test
    void listDelegatesFiltersWithValidToken() {
        List<InvoiceRow> rows = List.of(invoice("INV-2025-01", "Paid"));
        when(invoices.list(4L, "Paid", 25)).thenReturn(rows);

        Object response = controller.list("billing-token", 4L, "Paid", 25);

        assertThat(response).isSameAs(rows);
        verify(invoices).list(4L, "Paid", 25);
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
