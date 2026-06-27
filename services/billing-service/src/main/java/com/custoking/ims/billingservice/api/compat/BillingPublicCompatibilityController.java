package com.custoking.ims.billingservice.api.compat;

import com.custoking.ims.billingservice.application.BillingInvoiceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
public class BillingPublicCompatibilityController {

    private final BillingInvoiceService invoices;
    private final String serviceToken;

    public BillingPublicCompatibilityController(
            BillingInvoiceService invoices,
            @Value("${billing.service-token:}") String serviceToken) {
        this.invoices = invoices;
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
    }

    @GetMapping("/api/v1/sa/invoices")
    public Object list(
            @RequestHeader(value = "X-Billing-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "billing:read");
        return invoices.list(schoolId, status, limit);
    }

    @GetMapping("/api/v1/sa/invoices/stats")
    public Object stats(@RequestHeader(value = "X-Billing-Service-Token", required = false) String token) {
        requireToken(token, "billing:read");
        return invoices.stats();
    }

    @GetMapping("/api/v1/sa/invoices/by-order/{orderRef}")
    public Object byOrder(
            @RequestHeader(value = "X-Billing-Service-Token", required = false) String token,
            @PathVariable String orderRef) {
        requireToken(token, "billing:read");
        Object invoice = invoices.byOrderRef(orderRef);
        if (invoice == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "invoice not found");
        return invoice;
    }

    @GetMapping("/api/v1/sa/invoices/{id}")
    public Object byId(
            @RequestHeader(value = "X-Billing-Service-Token", required = false) String token,
            @PathVariable String id) {
        requireToken(token, "billing:read");
        Object invoice = invoices.byId(id);
        if (invoice == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "invoice not found");
        return invoice;
    }

    @PostMapping("/api/v1/sa/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    public Object create(
            @RequestHeader(value = "X-Billing-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "billing:read");
        return invoices.create(request);
    }

    @PatchMapping("/api/v1/sa/invoices/{id}")
    public Object update(
            @RequestHeader(value = "X-Billing-Service-Token", required = false) String token,
            @PathVariable String id,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "billing:read");
        Object invoice = invoices.update(id, request);
        if (invoice == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "invoice not found");
        return invoice;
    }

    @GetMapping("/api/v1/customers")
    public Object customers(@RequestHeader(value = "X-Billing-Service-Token", required = false) String token) {
        requireToken(token, "billing:read");
        return invoices.customers();
    }

    @PostMapping("/api/v1/customers")
    @ResponseStatus(HttpStatus.CREATED)
    public Object createCustomer(
            @RequestHeader(value = "X-Billing-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "billing:read");
        return run(() -> invoices.createCustomer(request));
    }

    @GetMapping("/api/v1/invoices")
    public Object schoolInvoices(@RequestHeader(value = "X-Billing-Service-Token", required = false) String token) {
        requireToken(token, "billing:read");
        return invoices.schoolInvoices();
    }

    @PostMapping("/api/v1/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    public Object createSchoolInvoice(
            @RequestHeader(value = "X-Billing-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "billing:read");
        return run(() -> invoices.createSchoolInvoice(request));
    }

    @GetMapping(value = "/api/v1/invoices/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> schoolInvoicePdf(
            @RequestHeader(value = "X-Billing-Service-Token", required = false) String token,
            @PathVariable Long id) {
        requireToken(token, "billing:read");
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(invoices.schoolInvoicePdf(id));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @GetMapping("/api/v1/billing-payments")
    public Object billingPayments(@RequestHeader(value = "X-Billing-Service-Token", required = false) String token) {
        requireToken(token, "billing:read");
        return invoices.billingPayments();
    }

    @PostMapping("/api/v1/billing-payments")
    @ResponseStatus(HttpStatus.CREATED)
    public Object createBillingPayment(
            @RequestHeader(value = "X-Billing-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "billing:read");
        return run(() -> invoices.createBillingPayment(request));
    }

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope) || !StringUtils.hasText(serviceToken) || !serviceToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid billing service token");
        }
    }

    private Object run(java.util.function.Supplier<Object> command) {
        try {
            return command.get();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
