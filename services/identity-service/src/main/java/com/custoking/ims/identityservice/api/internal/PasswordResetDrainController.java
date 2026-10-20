package com.custoking.ims.identityservice.api.internal;
import com.custoking.ims.identityservice.application.PasswordResetService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
/** Cloud Run IAM and MachineCallerFilter independently authorize the scheduler service account. */
@RestController
@RequestMapping("/api/v1/internal/password-reset")
public class PasswordResetDrainController {
 private final PasswordResetService service;
 public PasswordResetDrainController(PasswordResetService service) { this.service=service; }
 @PostMapping("/drain") public Map<String,Boolean> drain() { service.drainPending(); return Map.of("enabled",service.enabled()); }
}
