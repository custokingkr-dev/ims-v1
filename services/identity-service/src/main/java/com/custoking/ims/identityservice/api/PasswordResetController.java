package com.custoking.ims.identityservice.api;

import com.custoking.ims.identityservice.application.PasswordResetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth/password-reset")
public class PasswordResetController {
    private final PasswordResetService service;
    private final String serviceToken;
    public PasswordResetController(PasswordResetService service, @Value("${identity.introspection-token:}") String serviceToken) {
        this.service = service; this.serviceToken = serviceToken == null ? "" : serviceToken;
    }

    @GetMapping("/capabilities")
    public Map<String, Boolean> capabilities(@RequestHeader(value = "X-Identity-Service-Token", required = false) String token) {
        requireToken(token, "identity:reset-read");
        return Map.of("enabled", service.enabled());
    }

    @PostMapping("/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> request(@RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @Valid @RequestBody ResetRequest request) {
        requireToken(token, "identity:reset-request");
        service.request(request.email());
        return Map.of("message", "If the account is eligible, a reset link will be sent. Check your inbox and spam folder.");
    }

    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @Valid @RequestBody ResetConfirm request) {
        requireToken(token, "identity:reset-confirm");
        service.confirm(request.token(), request.password());
    }

    private void requireToken(String token, String scope) {
        if (!java.util.Set.of("identity:reset-read", "identity:reset-request", "identity:reset-confirm").contains(scope)
                || serviceToken.isBlank() || token == null || !java.security.MessageDigest.isEqual(
                    serviceToken.getBytes(java.nio.charset.StandardCharsets.UTF_8), token.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid identity service credential");
        }
    }

    public record ResetRequest(@NotBlank @Email @Size(max = 254) String email) {}
    public record ResetConfirm(@NotBlank @Size(max = 100) String token, @NotBlank @Size(max = 72) String password) {}
}
