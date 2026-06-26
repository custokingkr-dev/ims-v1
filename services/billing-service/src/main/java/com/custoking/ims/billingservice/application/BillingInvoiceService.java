package com.custoking.ims.billingservice.application;

import com.custoking.ims.billingservice.persistence.BillingInvoiceRepository;
import com.custoking.ims.billingservice.persistence.BillingInvoiceRepository.InvoiceRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class BillingInvoiceService {

    private final BillingInvoiceRepository invoices;

    public BillingInvoiceService(BillingInvoiceRepository invoices) {
        this.invoices = invoices;
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
        return invoices.create(request);
    }

    @Transactional
    public InvoiceRow update(String id, Map<String, Object> request) {
        return invoices.update(id, request);
    }
}
