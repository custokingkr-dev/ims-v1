package com.custoking.ims.controller;

import com.custoking.ims.service.DatabaseStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final DatabaseStore store;
    public DashboardController(DatabaseStore store) { this.store = store; }

    @GetMapping
    public Map<String, Object> get(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @RequestParam(value = "schoolId", required = false) Long schoolId) {
        var actor = store.requireUser(authorization);
        return store.workspace(actor, schoolId);
    }
}
