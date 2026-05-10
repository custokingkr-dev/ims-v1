package com.custoking.ims.controller;

import com.custoking.ims.common.domain.PermissionConstants;
import com.custoking.ims.service.SchoolService;
import com.custoking.ims.service.SuperadminService;
import com.custoking.ims.service.UserContextService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sa")
@PreAuthorize(PermissionConstants.SUPERADMIN_ACCESS)
public class SuperadminPortalController {
    private final UserContextService userContext;
    private final SuperadminService superadminService;
    private final SchoolService schoolService;

    public SuperadminPortalController(UserContextService userContext,
                                       SuperadminService superadminService,
                                       SchoolService schoolService) {
        this.userContext = userContext;
        this.superadminService = superadminService;
        this.schoolService = schoolService;
    }

    @GetMapping("/orders")
    public Object allOrders(@RequestHeader(value = "Authorization", required = false) String auth) {
        userContext.requireSuperAdmin(auth);
        return superadminService.listAllOrdersForSuperadmin();
    }

    @GetMapping("/orders/stats")
    public Map<String, Object> orderStats(@RequestHeader(value = "Authorization", required = false) String auth) {
        userContext.requireSuperAdmin(auth);
        return superadminService.allOrdersStatsForSuperadmin();
    }

    @PatchMapping("/orders/{orderId}/status")
    public Map<String, Object> updateOrderStatus(@RequestHeader(value = "Authorization", required = false) String auth,
                                                  @PathVariable String orderId,
                                                  @RequestBody Map<String, Object> body) {
        userContext.requireSuperAdmin(auth);
        return superadminService.superadminUpdateOrderStatus(orderId, body);
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createOrder(@RequestHeader(value = "Authorization", required = false) String auth,
                                            @RequestBody Map<String, Object> body) {
        userContext.requireSuperAdmin(auth);
        return superadminService.superadminCreateOrder(body);
    }

    @GetMapping("/invoices")
    public List<Map<String, Object>> invoices(@RequestHeader(value = "Authorization", required = false) String auth) {
        userContext.requireSuperAdmin(auth);
        return superadminService.listSuperadminInvoices();
    }

    @GetMapping("/invoices/stats")
    public Map<String, Object> invoiceStats(@RequestHeader(value = "Authorization", required = false) String auth) {
        userContext.requireSuperAdmin(auth);
        return superadminService.superadminInvoiceStats();
    }

    @PostMapping("/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createInvoice(@RequestHeader(value = "Authorization", required = false) String auth,
                                              @RequestBody Map<String, Object> body) {
        userContext.requireSuperAdmin(auth);
        return superadminService.createSuperadminInvoice(body);
    }

    @GetMapping("/invoices/by-order/{orderRef}")
    public ResponseEntity<Map<String, Object>> invoiceByOrder(@RequestHeader(value = "Authorization", required = false) String auth,
                                                               @PathVariable String orderRef) {
        userContext.requireSuperAdmin(auth);
        Map<String, Object> inv = superadminService.findInvoiceByOrderRef(orderRef);
        return inv == null ? new ResponseEntity<>(HttpStatus.NOT_FOUND) : new ResponseEntity<>(inv, HttpStatus.OK);
    }

    @PatchMapping("/invoices/{id}")
    public Map<String, Object> editInvoice(@RequestHeader(value = "Authorization", required = false) String auth,
                                            @PathVariable String id,
                                            @RequestBody Map<String, Object> body) {
        userContext.requireSuperAdmin(auth);
        return superadminService.updateSuperadminInvoice(id, body);
    }

    @GetMapping("/schools")
    public List<Map<String, Object>> schools(@RequestHeader(value = "Authorization", required = false) String auth) {
        userContext.requireSuperAdmin(auth);
        return schoolService.listSchoolsWithStats();
    }
}
