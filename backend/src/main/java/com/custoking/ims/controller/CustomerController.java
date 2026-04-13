package com.custoking.ims.controller;

import com.custoking.ims.dto.CustomerCreateRequest;
import com.custoking.ims.service.DatabaseStore;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {
    private final DatabaseStore store;
    public CustomerController(DatabaseStore store) { this.store = store; }

    @GetMapping
    public List<Map<String, Object>> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        store.requireUser(authorization);
        return store.customers();
    }

    @PostMapping
    public Map<String, Object> create(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @RequestBody CustomerCreateRequest request) {
        store.requireUser(authorization);
        return store.addCustomer(Map.of("code", request.code(), "name", request.name(), "email", request.email(), "phone", request.phone(), "gstin", request.gstin()));
    }
}
