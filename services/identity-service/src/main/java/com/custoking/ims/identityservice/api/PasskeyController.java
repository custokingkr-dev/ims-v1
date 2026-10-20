package com.custoking.ims.identityservice.api;

import com.custoking.ims.identityservice.application.PasskeyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import tools.jackson.databind.JsonNode;
import java.util.*;

@RestController
@RequestMapping("/api/v1/auth/passkeys")
public class PasskeyController {
    private final PasskeyService service;
    private final String token;
    public PasskeyController(PasskeyService service,@Value("${identity.introspection-token:}") String token) {
        this.service=service;this.token=token==null?"":token.trim();
    }
    private void requireToken(String supplied,String scope) {
        if(!"identity:passkeys".equals(scope)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Invalid route capability");
        if(token.isBlank() || supplied==null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid identity service token");
    }
    @GetMapping public Map<String,Object> status(@RequestHeader(value="X-Identity-Service-Token",required=false) String supplied, @RequestHeader("X-Authenticated-Session-Id") String session) { requireToken(supplied,"identity:passkeys"); return service.status(session); }
    @PostMapping("/registration/options") public PasskeyService.Ceremony register(@RequestHeader(value="X-Identity-Service-Token",required=false) String supplied, @RequestHeader("X-Authenticated-Session-Id") String session,@Valid @RequestBody PasswordConfirmation request) {
        requireToken(supplied,"identity:passkeys");
        return service.registrationOptions(session,request.password());
    }
    @PostMapping("/registration/verify") public Map<String,Object> registered(@RequestHeader(value="X-Identity-Service-Token",required=false) String supplied, @RequestHeader("X-Authenticated-Session-Id") String session,@Valid @RequestBody Verification request) {
        requireToken(supplied,"identity:passkeys");
        return service.registrationVerify(session,request.challengeId(),request.credential());
    }
    @PostMapping("/assertion/options") public PasskeyService.Ceremony assertOptions(@RequestHeader(value="X-Identity-Service-Token",required=false) String supplied, @RequestHeader("X-Authenticated-Session-Id") String session) { requireToken(supplied,"identity:passkeys"); return service.assertionOptions(session); }
    @PostMapping("/assertion/verify") public Map<String,Object> asserted(@RequestHeader(value="X-Identity-Service-Token",required=false) String supplied, @RequestHeader("X-Authenticated-Session-Id") String session,@Valid @RequestBody Verification request) {
        requireToken(supplied,"identity:passkeys");
        return service.assertionVerify(session,request.challengeId(),request.credential());
    }
    public record PasswordConfirmation(@NotBlank @Size(max=72) String password) { }
    public record Verification(@NotNull UUID challengeId,@NotNull JsonNode credential) { }
    @PostMapping("/recovery/request") public Map<String,Object> recovery(@RequestHeader(value="X-Identity-Service-Token",required=false) String supplied, @RequestHeader("X-Authenticated-Session-Id") String session,@Valid @RequestBody RecoveryRequest request) {
        requireToken(supplied,"identity:passkeys");
        return service.requestRecovery(session,request.userId(),request.reason());
    }
    @PostMapping("/recovery/approve") public Map<String,Object> approve(@RequestHeader(value="X-Identity-Service-Token",required=false) String supplied, @RequestHeader("X-Authenticated-Session-Id") String session,@Valid @RequestBody RecoveryApproval request) {
        requireToken(supplied,"identity:passkeys");
        return service.approveRecovery(session,request.recoveryId());
    }
    public record RecoveryRequest(@Positive long userId,@NotBlank @Size(max=1000) String reason) { }
    public record RecoveryApproval(@NotNull UUID recoveryId) { }
}
