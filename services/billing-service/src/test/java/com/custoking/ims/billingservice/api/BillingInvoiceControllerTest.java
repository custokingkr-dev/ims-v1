package com.custoking.ims.billingservice.api;

import com.custoking.ims.billingservice.application.BillingInvoiceService;
import com.custoking.ims.billingservice.persistence.BillingInvoiceRepository.InvoiceRow;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingInvoiceControllerTest {

    private final BillingInvoiceService invoices = mock(BillingInvoiceService.class);
    private final BillingInvoiceController controller = new BillingInvoiceController(invoices, "billing-token");

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
    void createRequiresWriteTokenAndDelegatesPayload() {
        Map<String, Object> request = Map.of(
                "schoolId", 4L,
                "school", "Delhi Public School",
                "description", "Annual platform subscription",
                "qty", 1,
                "rate", 1000);
        InvoiceRow invoice = invoice("INV-2025-01", "Awaiting payment");
        when(invoices.create(request)).thenReturn(invoice);

        Object response = controller.create("billing-token", request);

        assertThat(response).isSameAs(invoice);
        verify(invoices).create(request);
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
