package com.custoking.ims.controller;

import com.custoking.ims.service.DatabaseStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final DatabaseStore store;
    public UserController(DatabaseStore store) { this.store = store; }

    @GetMapping
    public List<Map<String, Object>> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        store.requireUser(authorization);
        return store.users();
    }
}
