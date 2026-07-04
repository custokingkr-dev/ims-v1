package com.custoking.ims.billingservice.application;

import com.custoking.ims.billingservice.outbox.OutboxWriter;
import com.custoking.ims.billingservice.persistence.BillingInvoiceRepository;
import com.custoking.ims.billingservice.persistence.BillingInvoiceRepository.CustomerRow;
import com.custoking.ims.billingservice.persistence.BillingInvoiceRepository.InvoiceRow;
import com.custoking.ims.billingservice.persistence.BillingInvoiceRepository.PaymentRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class BillingInvoiceService {

    private final BillingInvoiceRepository invoices;
    private final OutboxWriter outbox;
    private final ObjectMapper objectMapper;

    public BillingInvoiceService(BillingInvoiceRepository invoices, OutboxWriter outbox, ObjectMapper objectMapper) {
        this.invoices = invoices;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<InvoiceRow> list(Long schoolId, String status, int limit) {
        return invoices.list(schoolId, status, limit);
    }

    @Transactional(readOnly = true)
    public InvoiceRow byId(String id) {
        return invoices.byId(id);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        return invoices.stats();
    }

    @Transactional(readOnly = true)
    public InvoiceRow byOrderRef(String orderRef) {
        return invoices.byOrderRef(orderRef);
    }

    @Transactional
    public InvoiceRow create(Map<String, Object> request) {
        InvoiceRow row = invoices.create(request);
        appendInvoiceOutbox(row);
        return row;
    }

    @Transactional
    public InvoiceRow update(String id, Map<String, Object> request) {
        InvoiceRow row = invoices.update(id, request);
        if (row != null) {
            appendInvoiceOutbox(row);
        }
        return row;
    }

    private void appendInvoiceOutbox(InvoiceRow row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", row.id());
        payload.put("schoolId", row.schoolId());
        payload.put("status", row.status());
        payload.put("total", row.total());
        String payloadJson = objectMapper.writeValueAsString(payload);
        outbox.append(
                "billing.invoice-upserted.v1",
                "InvoiceUpserted:" + row.id(),
                "SuperadminInvoice",
                String.valueOf(row.id()),
                row.schoolId(),
                payloadJson);
    }

    @Transactional(readOnly = true)
    public List<CustomerRow> customers() {
        return invoices.customers();
    }

    @Transactional
    public CustomerRow createCustomer(Map<String, Object> request) {
        return invoices.createCustomer(request);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> schoolInvoices() {
        return invoices.schoolInvoices();
    }

    @Transactional
    public Map<String, Object> createSchoolInvoice(Map<String, Object> request) {
        return invoices.createSchoolInvoice(request);
    }

    @Transactional(readOnly = true)
    public byte[] schoolInvoicePdf(Long id) {
        return invoices.schoolInvoicePdf(id);
    }

    @Transactional(readOnly = true)
    public List<PaymentRow> billingPayments() {
        return invoices.billingPayments();
    }

    @Transactional
    public PaymentRow createBillingPayment(Map<String, Object> request) {
        return invoices.createBillingPayment(request);
    }
}
