package com.custoking.ims.controller;

import com.custoking.ims.dto.InvoiceCreateRequest;
import com.custoking.ims.service.UserContextService;
import com.custoking.ims.service.WorkspaceService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/invoices")
@PreAuthorize("hasAnyRole('ADMIN','SUPERADMIN')")
public class InvoiceController {
    private final UserContextService userContext;
    private final WorkspaceService workspaceService;

    public InvoiceController(UserContextService userContext, WorkspaceService workspaceService) {
        this.userContext = userContext;
        this.workspaceService = workspaceService;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        userContext.requireUser(authorization);
        return workspaceService.invoices();
    }

    @PostMapping
    public Map<String, Object> create(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @RequestBody InvoiceCreateRequest request) {
        userContext.requireUser(authorization);
        return workspaceService.addInvoice(request);
    }

    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable long id) {
        userContext.requireUser(authorization);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=invoice-" + id + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(workspaceService.invoicePdf(id));
    }
}
