package com.custoking.ims.platformservice.api;

import com.custoking.ims.platformservice.api.dto.BroadcastActionRequest;
import com.custoking.ims.platformservice.api.dto.CreateBroadcastRequest;
import com.custoking.ims.platformservice.persistence.NotificationBroadcastCommandRepository;
import com.custoking.ims.platformservice.security.TenantContext;
import com.custoking.ims.platformservice.security.TenantScope;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications/broadcasts")
public class NotificationBroadcastCommandController {

    private final NotificationBroadcastCommandRepository broadcasts;
    private final String statusToken;

    public NotificationBroadcastCommandController(NotificationBroadcastCommandRepository broadcasts,
                                                  @Value("${notification.status.token:}") String statusToken) {
        this.broadcasts = broadcasts;
        this.statusToken = statusToken == null ? "" : statusToken.trim();
    }

    /** CONVERTED: required fields title + message validated before repo call. */
    @PostMapping
    public Map<String, Object> create(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token,
            @Valid @RequestBody CreateBroadcastRequest req) {
        requireToken(token, "notification:write");
        TenantScope.requireSuperAdmin();
        Map<String, Object> body = new HashMap<>();
        body.put("title", req.title());
        body.put("message", req.message());
        if (req.schoolId() != null) body.put("schoolId", req.schoolId());
        if (req.module() != null) body.put("module", req.module());
        if (req.audienceType() != null) body.put("audienceType", req.audienceType());
        if (req.channels() != null) body.put("channels", req.channels());
        if (req.scheduledAt() != null) body.put("scheduledAt", req.scheduledAt());
        Long createdBy = TenantContext.get().userId();
        if (createdBy != null) body.put("createdBy", createdBy);
        return command(() -> broadcasts.create(body));
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "notification:read");
        TenantScope.requireSuperAdmin();
        return broadcasts.list(schoolId, status, limit);
    }

    /**
     * CONVERTED: optional body with optional actorId. Body may be absent entirely (required=false),
     * in which case req is null and actorId is null. @Positive on actorId fires only when present.
     * No @NotNull/@NotBlank — actorId is optional (null means action taken without a recorded actor).
     */
    @PostMapping("/{id}/approve")
    public Map<String, Object> approve(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token,
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) BroadcastActionRequest req) {
        requireToken(token, "notification:write");
        TenantScope.requireSuperAdmin();
        Long actorId = TenantContext.get().userId();
        return command(() -> broadcasts.approve(id, actorId));
    }

    /**
     * CONVERTED: same optional-body semantics as /{id}/approve. Shared BroadcastActionRequest DTO.
     */
    @PostMapping("/{id}/send")
    public Map<String, Object> send(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token,
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) BroadcastActionRequest req) {
        requireToken(token, "notification:write");
        TenantScope.requireSuperAdmin();
        Long actorId = TenantContext.get().userId();
        return command(() -> broadcasts.send(id, actorId));
    }

    @GetMapping("/{id}/delivery-status")
    public Map<String, Object> deliveryStatus(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token,
            @PathVariable UUID id) {
        requireToken(token, "notification:read");
        TenantScope.requireSuperAdmin();
        return command(() -> broadcasts.deliveryStatus(id));
    }

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing internal route scope" );
        }
        if (!StringUtils.hasText(statusToken) || !statusToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid notification service token");
        }
    }

    private Map<String, Object> command(Command command) {
        try {
            return command.run();
        } catch (IllegalArgumentException ex) {
            String message = ex.getMessage() == null ? "Invalid request" : ex.getMessage();
            HttpStatus status = message.toLowerCase().contains("not found") ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            throw new ResponseStatusException(status, message, ex);
        }
    }

    private interface Command {
        Map<String, Object> run();
    }
}

