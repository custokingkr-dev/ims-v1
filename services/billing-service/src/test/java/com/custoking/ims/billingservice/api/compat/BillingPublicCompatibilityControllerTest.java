package com.custoking.ims.billingservice.api.compat;

import com.custoking.ims.billingservice.application.BillingInvoiceService;
import com.custoking.ims.billingservice.persistence.BillingInvoiceRepository.CustomerRow;
import com.custoking.ims.billingservice.persistence.BillingInvoiceRepository.PaymentRow;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingPublicCompatibilityControllerTest {

    private final BillingInvoiceService invoices = mock(BillingInvoiceService.class);
    private final BillingPublicCompatibilityController controller =
            new BillingPublicCompatibilityController(invoices, "billing-token");

    @Test
    void customersRejectsInvalidTokenBeforeDelegating() {
        assertThatThrownBy(() -> controller.customers("wrong-token"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(invoices, never()).customers();
    }

    @Test
    void createCustomerDelegatesPayload() {
        Map<String, Object> request = Map.of("code", "C-1", "name", "Delhi Public School");
        CustomerRow customer = new CustomerRow(1L, "C-1", "Delhi Public School", null, null,
                null, null, 1L, "Main Branch", true);
        when(invoices.createCustomer(request)).thenReturn(customer);

        Object response = controller.createCustomer("billing-token", request);

        assertThat(response).isSameAs(customer);
        verify(invoices).createCustomer(request);
    }

    @Test
    void schoolInvoicesDelegates() {
        List<Map<String, Object>> rows = List.of(Map.of("invoiceNo", "INV-1"));
        when(invoices.schoolInvoices()).thenReturn(rows);

        assertThat(controller.schoolInvoices("billing-token")).isSameAs(rows);
    }

    @Test
    void createSchoolInvoiceDelegatesPayload() {
        Map<String, Object> request = Map.of("customerId", 1L);
        Map<String, Object> invoice = Map.of("id", 10L, "invoiceNo", "INV-10");
        when(invoices.createSchoolInvoice(request)).thenReturn(invoice);

        Object response = controller.createSchoolInvoice("billing-token", request);

        assertThat(response).isSameAs(invoice);
        verify(invoices).createSchoolInvoice(request);
    }

    @Test
    void schoolInvoicePdfReturnsPdfBytes() {
        when(invoices.schoolInvoicePdf(10L)).thenReturn(new byte[] {1, 2, 3});

        var response = controller.schoolInvoicePdf("billing-token", 10L);

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getBody()).containsExactly(1, 2, 3);
    }

    @Test
    void schoolInvoicePdfMapsMissingInvoiceToNotFound() {
        when(invoices.schoolInvoicePdf(10L)).thenThrow(new IllegalArgumentException("Invoice not found"));

        assertThatThrownBy(() -> controller.schoolInvoicePdf("billing-token", 10L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void billingPaymentsDelegate() {
        List<PaymentRow> rows = List.of(payment());
        when(invoices.billingPayments()).thenReturn(rows);

        assertThat(controller.billingPayments("billing-token")).isSameAs(rows);
    }

    @Test
    void createBillingPaymentDelegatesPayload() {
        Map<String, Object> request = Map.of("invoiceId", 10L, "amount", 500);
        PaymentRow payment = payment();
        when(invoices.createBillingPayment(request)).thenReturn(payment);

        Object response = controller.createBillingPayment("billing-token", request);

        assertThat(response).isSameAs(payment);
        verify(invoices).createBillingPayment(request);
    }

    private PaymentRow payment() {
        return new PaymentRow(1L, 10L, "INV-10", 1L, "Main Branch", LocalDate.parse("2026-06-27"),
                500L, "UPI", "REF-1", null, "Admin");
    }
}
