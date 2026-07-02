package com.custoking.ims.notificationservice.api;

import com.custoking.ims.notificationservice.api.dto.CompleteWhatsappOnboardingRequest;
import com.custoking.ims.notificationservice.application.SenderProfile;
import com.custoking.ims.notificationservice.persistence.SenderProfileRepository;
import com.custoking.ims.notificationservice.security.TenantScope;
import jakarta.validation.Valid;
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

import java.util.HashMap;
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

    // Cross-tenant: lists all sender profiles across all schools. Superadmin only.
    // The Msg91 delivery path reads profiles in-process via SenderProfileRepository —
    // there is NO legitimate header-less HTTP caller of this controller.
    @GetMapping
    public List<SenderProfile> list(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token) {
        requireToken(token, "notification:read");
        TenantScope.requireSuperAdmin();
        return profiles.list(null);
    }

    // Platform default sender profile. Superadmin only via HTTP.
    // Internal delivery resolves profiles in-process — not via this HTTP endpoint.
    @GetMapping("/default")
    public SenderProfile defaultProfile(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token) {
        requireToken(token, "notification:read");
        TenantScope.requireSuperAdmin();
        return profiles.defaultProfile();
    }

    // Platform admin: update the global default sender configuration. Superadmin only.
    @PutMapping("/default")
    public SenderProfile updateDefaultProfile(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        requireToken(token, "notification:write");
        TenantScope.requireSuperAdmin();
        return command(() -> profiles.upsert(null, body));
    }

    // School admin may only access their own school; superadmin may access any school.
    // Guard is unconditional: a header-less HTTP caller receives 403 (fail-closed).
    @GetMapping("/schools/{schoolId}")
    public SenderProfile schoolProfile(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token,
            @PathVariable Long schoolId) {
        requireToken(token, "notification:read");
        TenantScope.resolveSchoolId(schoolId);
        return profiles.resolve(schoolId);
    }

    // Configure the MSG91 sender credentials for a school.
    // Sensitive provisioning: only a superadmin may write sender credentials.
    // Guard is unconditional: header-less HTTP is denied (fail-closed).
    @PutMapping("/schools/{schoolId}")
    public SenderProfile updateSchoolProfile(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token,
            @PathVariable Long schoolId,
            @RequestBody Map<String, Object> body) {
        requireToken(token, "notification:write");
        TenantScope.requireSuperAdmin();
        return command(() -> profiles.upsert(schoolId, body));
    }

    // View onboarding sessions for a school's WhatsApp number.
    // School admin: own school only; superadmin: any school.
    // Guard is unconditional: header-less HTTP is denied (fail-closed).
    @GetMapping("/schools/{schoolId}/whatsapp-onboarding")
    public List<Map<String, Object>> onboardingSessions(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token,
            @PathVariable Long schoolId) {
        requireToken(token, "notification:read");
        TenantScope.resolveSchoolId(schoolId);
        return profiles.onboardingSessions(schoolId);
    }

    // Initiate WhatsApp business account onboarding for a school.
    // School admin: own school only; superadmin: any school.
    // Guard is unconditional: header-less HTTP is denied (fail-closed).
    @PostMapping("/schools/{schoolId}/whatsapp-onboarding")
    public Map<String, Object> requestWhatsappOnboarding(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token,
            @PathVariable Long schoolId,
            @RequestBody Map<String, Object> body) {
        requireToken(token, "notification:write");
        TenantScope.resolveSchoolId(schoolId);
        return command(() -> profiles.requestWhatsappOnboarding(schoolId, actorId(body), body));
    }

    /**
     * CONVERTED: integratedNumber is required (no fallback in repo). All other fields optional.
     * The built map is forwarded to the repo which also calls upsert() with the sender-profile
     * fields — optional fields are only put when non-null.
     * Guard is unconditional: resolveSchoolId enforces tenant scope for any HTTP caller.
     */
    @PostMapping("/schools/{schoolId}/whatsapp-onboarding/{sessionId}/complete")
    public Map<String, Object> completeWhatsappOnboarding(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token,
            @PathVariable Long schoolId,
            @PathVariable UUID sessionId,
            @Valid @RequestBody CompleteWhatsappOnboardingRequest req) {
        requireToken(token, "notification:write");
        TenantScope.resolveSchoolId(schoolId);
        Map<String, Object> body = new HashMap<>();
        body.put("integratedNumber", req.integratedNumber());
        if (req.providerReference() != null) body.put("providerReference", req.providerReference());
        if (req.profileName() != null) body.put("profileName", req.profileName());
        if (req.emailFromName() != null) body.put("emailFromName", req.emailFromName());
        if (req.emailFromAddress() != null) body.put("emailFromAddress", req.emailFromAddress());
        if (req.emailDomain() != null) body.put("emailDomain", req.emailDomain());
        if (req.emailReplyTo() != null) body.put("emailReplyTo", req.emailReplyTo());
        if (req.whatsappDisplayName() != null) body.put("whatsappDisplayName", req.whatsappDisplayName());
        if (req.whatsappTemplateNamespace() != null) body.put("whatsappTemplateNamespace", req.whatsappTemplateNamespace());
        if (req.whatsappDefaultTemplateName() != null) body.put("whatsappDefaultTemplateName", req.whatsappDefaultTemplateName());
        if (req.whatsappLanguageCode() != null) body.put("whatsappLanguageCode", req.whatsappLanguageCode());
        if (req.msg91SmsFlowId() != null) body.put("msg91SmsFlowId", req.msg91SmsFlowId());
        if (req.msg91OtpTemplateId() != null) body.put("msg91OtpTemplateId", req.msg91OtpTemplateId());
        if (req.msg91EmailTemplateId() != null) body.put("msg91EmailTemplateId", req.msg91EmailTemplateId());
        return command(() -> profiles.completeWhatsappOnboarding(schoolId, sessionId, body));
    }

    // Fail a WhatsApp onboarding session.
    // Guard is unconditional: resolveSchoolId enforces tenant scope for any HTTP caller.
    @PostMapping("/schools/{schoolId}/whatsapp-onboarding/{sessionId}/fail")
    public Map<String, Object> failWhatsappOnboarding(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token,
            @PathVariable Long schoolId,
            @PathVariable UUID sessionId,
            @RequestBody Map<String, Object> body) {
        requireToken(token, "notification:write");
        TenantScope.resolveSchoolId(schoolId);
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
