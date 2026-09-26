package com.custoking.ims.schoolcoreservice.api.internal;

import com.custoking.ims.schoolcoreservice.persistence.BroadcastRecipientPolicyRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Private peer endpoint: Cloud Run IAM plus a dedicated shared credential; gateway refuses internal routes. */
@RestController
@RequestMapping("/api/v1/internal/notifications/broadcast-recipients")
public class BroadcastRecipientPolicyController {
    private final BroadcastRecipientPolicyRepository repository;
    private final String token;

    public BroadcastRecipientPolicyController(BroadcastRecipientPolicyRepository repository,
            @Value("${notification.broadcast.policy-token:}") String token) {
        this.repository = repository;
        this.token = token == null ? "" : token.trim();
    }

    @PostMapping
    public List<Map<String, Object>> resolve(@RequestHeader(value = "X-Broadcast-Policy-Token", required = false) String supplied,
            @Valid @RequestBody ResolveRequest request) {
        requireToken(supplied, "notification:broadcast-policy");
        if (!"SCHOOL_NOTICE".equals(request.communicationCategory()) || !"ALL_PARENTS".equals(request.audienceType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only school notices to primary guardians are supported");
        }
        try {
            return repository.resolve(request.schoolId(), request.broadcastId(), request.channels(), request.studentIds());
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
        }
    }

    private void requireToken(String supplied, String scope) {
        if (!"notification:broadcast-policy".equals(scope)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid internal route scope");
        if (token.isBlank() || supplied == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid broadcast policy credential");
        }
    }

    public record ResolveRequest(@Positive long schoolId, @NotNull UUID broadcastId,
            String communicationCategory, String audienceType, @NotEmpty List<String> channels, List<Long> studentIds) {}
}
