package com.custoking.ims.controller;

import com.custoking.ims.service.DatabaseStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sa")
public class SuperadminPortalController {
    private final DatabaseStore store;

    public SuperadminPortalController(DatabaseStore store) {
        this.store = store;
    }

    @GetMapping("/orders")
    public Object allOrders(@RequestHeader(value = "Authorization", required = false) String auth) {
        store.requireSuperAdmin(auth);
        return store.listAllOrdersForSuperadmin();
    }

    @GetMapping("/orders/stats")
    public Map<String, Object> orderStats(@RequestHeader(value = "Authorization", required = false) String auth) {
        store.requireSuperAdmin(auth);
        return store.allOrdersStatsForSuperadmin();
    }

    @PatchMapping("/orders/{orderId}/status")
    public Map<String, Object> updateOrderStatus(@RequestHeader(value = "Authorization", required = false) String auth,
                                                 @PathVariable String orderId,
                                                 @RequestBody Map<String, Object> body) {
        store.requireSuperAdmin(auth);
        return store.superadminUpdateOrderStatus(orderId, body);
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createOrder(@RequestHeader(value = "Authorization", required = false) String auth,
                                           @RequestBody Map<String, Object> body) {
        store.requireSuperAdmin(auth);
        return store.superadminCreateOrder(body);
    }

    @GetMapping("/invoices")
    public List<Map<String, Object>> invoices(@RequestHeader(value = "Authorization", required = false) String auth) {
        store.requireSuperAdmin(auth);
        return store.listSuperadminInvoices();
    }

    @GetMapping("/invoices/stats")
    public Map<String, Object> invoiceStats(@RequestHeader(value = "Authorization", required = false) String auth) {
        store.requireSuperAdmin(auth);
        return store.superadminInvoiceStats();
    }

    @PostMapping("/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createInvoice(@RequestHeader(value = "Authorization", required = false) String auth,
                                             @RequestBody Map<String, Object> body) {
        store.requireSuperAdmin(auth);
        return store.createSuperadminInvoice(body);
    }

    @GetMapping("/invoices/by-order/{orderRef}")
    public ResponseEntity<Map<String, Object>> invoiceByOrder(@RequestHeader(value = "Authorization", required = false) String auth,
                                                              @PathVariable String orderRef) {
        store.requireSuperAdmin(auth);
        Map<String, Object> inv = store.findInvoiceByOrderRef(orderRef);
        return inv == null ? new ResponseEntity<>(HttpStatus.NOT_FOUND) : new ResponseEntity<>(inv, HttpStatus.OK);
    }

    @PatchMapping("/invoices/{id}")
    public Map<String, Object> editInvoice(@RequestHeader(value = "Authorization", required = false) String auth,
                                           @PathVariable String id,
                                           @RequestBody Map<String, Object> body) {
        store.requireSuperAdmin(auth);
        return store.updateSuperadminInvoice(id, body);
    }

    @GetMapping("/schools")
    public List<Map<String, Object>> schools(@RequestHeader(value = "Authorization", required = false) String auth) {
        store.requireSuperAdmin(auth);
        return store.listSchoolsWithStats();
    }
}
