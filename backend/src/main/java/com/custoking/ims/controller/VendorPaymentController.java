package com.custoking.ims.controller;

import com.custoking.ims.commandcenter.VendorPaymentService;
import com.custoking.ims.commandcenter.dto.MarkVendorPaidRequest;
import com.custoking.ims.commandcenter.dto.VendorDuesListResponse;
import com.custoking.ims.common.domain.PermissionConstants;
import com.custoking.ims.context.TenantContext;
import com.custoking.ims.model.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/dashboard/vendor-dues")
public class VendorPaymentController {

    private final VendorPaymentService vendorPaymentService;

    public VendorPaymentController(VendorPaymentService vendorPaymentService) {
        this.vendorPaymentService = vendorPaymentService;
    }

    @GetMapping
    @PreAuthorize(PermissionConstants.ORDER_READ)
    public VendorDuesListResponse getVendorDues() {
        return vendorPaymentService.getVendorDuesList(TenantContext.get());
    }

    @PostMapping("/catalog-orders/{orderId}/mark-paid")
    @PreAuthorize(PermissionConstants.ORDER_UPDATE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markCatalogOrderPaid(@PathVariable String orderId,
                                     @RequestBody(required = false) MarkVendorPaidRequest request,
                                     @AuthenticationPrincipal AuthUser actor) {
        String notes = request != null ? request.notes() : null;
        vendorPaymentService.markCatalogOrderPaid(TenantContext.get(), orderId, notes, actor);
    }

    @PostMapping("/firefighting/{code}/mark-paid")
    @PreAuthorize(PermissionConstants.FIREFIGHTING_UPDATE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markFirefightingPaid(@PathVariable String code,
                                     @RequestBody(required = false) MarkVendorPaidRequest request,
                                     @AuthenticationPrincipal AuthUser actor) {
        String notes = request != null ? request.notes() : null;
        vendorPaymentService.markFirefightingPaid(TenantContext.get(), code, notes, actor);
    }
}
