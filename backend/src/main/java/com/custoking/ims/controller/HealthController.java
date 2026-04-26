package com.custoking.ims.controller;

import com.custoking.ims.dto.HealthResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@PreAuthorize("isAuthenticated()")
public class HealthController {
    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("UP", "custoking-ims-backend");
    }
}
