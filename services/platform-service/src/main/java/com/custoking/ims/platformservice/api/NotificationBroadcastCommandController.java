package com.custoking.ims.platformservice.api;

import com.custoking.ims.platformservice.api.dto.BroadcastActionRequest;
import com.custoking.ims.platformservice.api.dto.CreateBroadcastRequest;
import com.custoking.ims.platformservice.application.BroadcastDispatchService;
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
    private final BroadcastDispatchService dispatch;

    public NotificationBroadcastCommandController(NotificationBroadcastCommandRepository broadcasts,
                                                  @Value("${notification.status.token:}") String statusToken,
                                                  BroadcastDispatchService dispatch) {
        this.broadcasts = broadcasts;
        this.dispatch = dispatch;
        this.statusToken = statusToken == null ? "" : statusToken.trim();
    }

    /** CONVERTED: required fields title + message validated before repo call. */
    @PostMapping
    public Map<String, Object> create(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token,
            @Valid @RequestBody CreateBroadcastRequest req) {
        requireToken(token, "notification:write");
        TenantScope.requirePermissionIfAuthenticated("notification:send");
        TenantScope.requireSuperAdmin();
        Map<String, Object> body = new HashMap<>();
        body.put("title", req.title());
        body.put("message", req.message());
        if (req.schoolId() != null) body.put("schoolId", req.schoolId());
        if (req.module() != null) body.put("module", req.module());
        if (req.audienceType() != null) body.put("audienceType", req.audienceType());
        if (req.channels() != null) body.put("channels", req.channels());
        if (req.scheduledAt() != null) body.put("scheduledAt", req.scheduledAt());
        if (req.communicationCategory() != null) body.put("communicationCategory", req.communicationCategory());
        Long createdBy = TenantContext.get().userId();
        if (createdBy != null) body.put("createdBy", createdBy);
        return command(() -> broadcasts.create(body));
    }

    /** Capabilities describe the same restrictions enforced by the command endpoints. */
    @GetMapping("/capabilities")
    public Map<String, Object> capabilities(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId) {
        requireToken(token, "notification:read");
        TenantScope.requirePermissionIfAuthenticated("notification:read");
        boolean canManage = TenantContext.get().isSuperAdmin();
        return schoolId == null ? dispatch.capabilities(canManage) : dispatch.capabilities(canManage, TenantScope.resolveSchoolId(schoolId));
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "notification:read");
        TenantScope.requirePermissionIfAuthenticated("notification:read");
        Long resolvedSchoolId = TenantScope.resolveSchoolId(schoolId);
        return broadcasts.list(resolvedSchoolId, status, limit);
    }

    /** Approval requires the current preview fingerprint; the authenticated actor is authoritative. */
    @PostMapping("/{id}/approve")
    public Map<String, Object> approve(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token,
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) BroadcastActionRequest req) {
        requireToken(token, "notification:write");
        TenantScope.requirePermissionIfAuthenticated("notification:send");
        TenantScope.requireSuperAdmin();
        Long actorId = TenantContext.get().userId();
        return command(() -> {
            dispatch.approve(id, actorId, req == null ? null : req.previewFingerprint());
            return broadcasts.get(id);
        });
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
        TenantScope.requirePermissionIfAuthenticated("notification:send");
        TenantScope.requireSuperAdmin();
        Long actorId = TenantContext.get().userId();
        return command(() -> req == null || req.mode() == null ? dispatch.queue(id, actorId)
                : dispatch.queue(id, actorId, req.mode(), req.previewFingerprint()));
    }

    @GetMapping("/{id}/delivery-status")
    public Map<String, Object> deliveryStatus(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token,
            @PathVariable UUID id) {
        requireToken(token, "notification:read");
        TenantScope.requirePermissionIfAuthenticated("notification:read");
        TenantScope.requireSuperAdmin();
        return command(() -> dispatch.outcomes(id));
    }

    @PostMapping("/{id}/preview")
    public Map<String, Object> preview(@RequestHeader(value = "X-Notification-Service-Token", required = false) String token,
            @PathVariable UUID id) {
        requireToken(token, "notification:read");
        TenantScope.requirePermissionIfAuthenticated("notification:read");
        TenantScope.requireSuperAdmin();
        return command(() -> dispatch.preview(id));
    }

    @PostMapping("/{id}/retry")
    public Map<String, Object> retry(@RequestHeader(value = "X-Notification-Service-Token", required = false) String token,
            @PathVariable UUID id) {
        requireToken(token, "notification:write");
        TenantScope.requirePermissionIfAuthenticated("notification:send");
        TenantScope.requireSuperAdmin();
        return command(() -> dispatch.retry(id));
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

