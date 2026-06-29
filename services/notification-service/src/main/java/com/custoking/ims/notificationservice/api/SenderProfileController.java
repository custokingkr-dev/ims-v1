package com.custoking.ims.notificationservice.api;

import com.custoking.ims.notificationservice.application.SenderProfile;
import com.custoking.ims.notificationservice.persistence.SenderProfileRepository;
import com.custoking.ims.notificationservice.security.TenantContext;
import com.custoking.ims.notificationservice.security.TenantScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications/sender-profiles")
public class SenderProfileController {

    private final SenderProfileRepository profiles;
    private final String statusToken;

    public SenderProfileController(SenderProfileRepository profiles,
                                   @Value("${notification.status.token:}") String statusToken) {
        this.profiles = profiles;
        this.statusToken = statusToken == null ? "" : statusToken.trim();
    }

    // Cross-tenant: lists all sender profiles across all schools. Superadmin only (user context);
    // internal/system calls (no user context) bypass the user-level guard.
    @GetMapping
    public List<SenderProfile> list(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token) {
        requireToken(token, "notification:read");
        if (TenantContext.get().isAuthenticated()) {
            TenantScope.requireSuperAdmin();
        }
        return profiles.list(null);
    }

    // Platform default sender profile. Internal delivery system resolves this without a user context;
    // when a user is present only a superadmin may read it.
    @GetMapping("/default")
    public SenderProfile defaultProfile(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token) {
        requireToken(token, "notification:read");
        if (TenantContext.get().isAuthenticated()) {
            TenantScope.requireSuperAdmin();
        }
        return profiles.defaultProfile();
    }

    // Platform admin: update the global default sender configuration.
    // Internal/system calls (no user context) bypass the user-level guard.
    @PutMapping("/default")
    public SenderProfile updateDefaultProfile(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        requireToken(token, "notification:write");
        if (TenantContext.get().isAuthenticated()) {
            TenantScope.requireSuperAdmin();
        }
        return command(() -> profiles.upsert(null, body));
    }

    // System-internal: delivery system resolves the sender for a specific school without a user context.
    // User context: a school admin may only access their own school; superadmin may access any school.
    @GetMapping("/schools/{schoolId}")
    public SenderProfile schoolProfile(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token,
            @PathVariable Long schoolId) {
        requireToken(token, "notification:read");
        if (TenantContext.get().isAuthenticated()) {
            TenantScope.resolveSchoolId(schoolId);
        }
        return profiles.resolve(schoolId);
    }

    // Configure the MSG91 sender credentials for a school.
    // User context: a school admin may only update their own school; superadmin any school;
    // internal/system calls bypass the user-level guard.
    @PutMapping("/schools/{schoolId}")
    public SenderProfile updateSchoolProfile(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token,
            @PathVariable Long schoolId,
            @RequestBody Map<String, Object> body) {
        requireToken(token, "notification:write");
        if (TenantContext.get().isAuthenticated()) {
            TenantScope.resolveSchoolId(schoolId);
        }
        return command(() -> profiles.upsert(schoolId, body));
    }

    // View onboarding sessions for a school's WhatsApp number.
    // User context: a school admin may only view their own school; superadmin any school;
    // internal/system calls bypass the user-level guard.
    @GetMapping("/schools/{schoolId}/whatsapp-onboarding")
    public List<Map<String, Object>> onboardingSessions(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token,
            @PathVariable Long schoolId) {
        requireToken(token, "notification:read");
        if (TenantContext.get().isAuthenticated()) {
            TenantScope.resolveSchoolId(schoolId);
        }
        return profiles.onboardingSessions(schoolId);
    }

    // Initiate WhatsApp business account onboarding for a school.
    // User context: a school admin may only act on their own school; superadmin any school;
    // internal/system calls bypass the user-level guard.
    @PostMapping("/schools/{schoolId}/whatsapp-onboarding")
    public Map<String, Object> requestWhatsappOnboarding(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token,
            @PathVariable Long schoolId,
            @RequestBody Map<String, Object> body) {
        requireToken(token, "notification:write");
        if (TenantContext.get().isAuthenticated()) {
            TenantScope.resolveSchoolId(schoolId);
        }
        return command(() -> profiles.requestWhatsappOnboarding(schoolId, actorId(body), body));
    }

    // Ambiguous: may be called by MSG91/WhatsApp callback (no user context) or by an admin.
    // User context: scoped to the authenticated school (superadmin bypasses); system/callback path passes through.
    @PostMapping("/schools/{schoolId}/whatsapp-onboarding/{sessionId}/complete")
    public Map<String, Object> completeWhatsappOnboarding(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token,
            @PathVariable Long schoolId,
            @PathVariable UUID sessionId,
            @RequestBody Map<String, Object> body) {
        requireToken(token, "notification:write");
        if (TenantContext.get().isAuthenticated()) {
            TenantScope.resolveSchoolId(schoolId);
        }
        return command(() -> profiles.completeWhatsappOnboarding(schoolId, sessionId, body));
    }

    // Ambiguous: same reasoning as completeWhatsappOnboarding.
    // User context: scoped to the authenticated school (superadmin bypasses); system/callback path passes through.
    @PostMapping("/schools/{schoolId}/whatsapp-onboarding/{sessionId}/fail")
    public Map<String, Object> failWhatsappOnboarding(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token,
            @PathVariable Long schoolId,
            @PathVariable UUID sessionId,
            @RequestBody Map<String, Object> body) {
        requireToken(token, "notification:write");
        if (TenantContext.get().isAuthenticated()) {
            TenantScope.resolveSchoolId(schoolId);
        }
        return command(() -> profiles.failWhatsappOnboarding(schoolId, sessionId, body));
    }

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing internal route scope" );
        }
        if (!StringUtils.hasText(statusToken) || !statusToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid notification service token");
        }
    }

    private <T> T command(Command<T> command) {
        try {
            return command.run();
        } catch (IllegalArgumentException ex) {
            String message = ex.getMessage() == null ? "Invalid request" : ex.getMessage();
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message, ex);
        }
    }

    private Long actorId(Map<String, Object> body) {
        if (body == null || body.get("actorId") == null || String.valueOf(body.get("actorId")).isBlank()) {
            return null;
        }
        Object value = body.get("actorId");
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private interface Command<T> {
        T run();
    }
}
