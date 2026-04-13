package com.custoking.ims.controller;

import com.custoking.ims.dto.InvoiceCreateRequest;
import com.custoking.ims.service.DatabaseStore;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {
    private final DatabaseStore store;
    public InvoiceController(DatabaseStore store) { this.store = store; }

    @GetMapping
    public List<Map<String, Object>> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        store.requireUser(authorization);
        return store.invoices();
    }

    @PostMapping
    public Map<String, Object> create(@RequestHeader(value = "Authorization", required = false) String authorization,
                          @RequestBody InvoiceCreateRequest request) {
        store.requireUser(authorization);
        return store.addInvoice(request);
    }

    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable long id) {
        store.requireUser(authorization);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=invoice-" + id + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(store.invoicePdf(id));
    }
}
