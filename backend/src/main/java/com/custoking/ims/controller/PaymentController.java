package com.custoking.ims.controller;

import com.custoking.ims.dto.PaymentCreateRequest;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.service.DatabaseStore;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/billing-payments")
public class PaymentController {
    private final DatabaseStore store;
    public PaymentController(DatabaseStore store) { this.store = store; }

    @GetMapping
    public List<Map<String, Object>> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        store.requireUser(authorization);
        return store.payments();
    }

    @PostMapping
    public Map<String, Object> create(@RequestHeader(value = "Authorization", required = false) String authorization,
                          @RequestBody PaymentCreateRequest request) {
        AuthUser user = store.requireUser(authorization);
        return store.addPayment(request, user);
    }
}
