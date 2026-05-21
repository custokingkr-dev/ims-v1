package com.custoking.ims.controller;

import com.custoking.ims.common.domain.PermissionConstants;
import com.custoking.ims.context.TenantContext;
import com.custoking.ims.dto.InvoiceCreateRequest;
import com.custoking.ims.service.ModuleEntitlementService;
import com.custoking.ims.service.ModuleEntitlementService.Module;
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
@RequestMapping("/api/v1/invoices")
@PreAuthorize(PermissionConstants.INVOICE_READ)
public class InvoiceController {
    private final UserContextService userContext;
    private final WorkspaceService workspaceService;
    private final ModuleEntitlementService moduleService;

    public InvoiceController(UserContextService userContext, WorkspaceService workspaceService,
                             ModuleEntitlementService moduleService) {
        this.userContext = userContext;
        this.workspaceService = workspaceService;
        this.moduleService = moduleService;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        userContext.requireUser(authorization);
        moduleService.requireModule(TenantContext.get(), Module.INVOICES);
        return workspaceService.invoices();
    }

    @PostMapping
    @PreAuthorize(PermissionConstants.INVOICE_CREATE)
    public Map<String, Object> create(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @RequestBody InvoiceCreateRequest request) {
        userContext.requireUser(authorization);
        moduleService.requireModule(TenantContext.get(), Module.INVOICES);
        return workspaceService.addInvoice(request);
    }

    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable long id) {
        userContext.requireUser(authorization);
        moduleService.requireModule(TenantContext.get(), Module.INVOICES);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=invoice-" + id + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(workspaceService.invoicePdf(id));
    }
}
