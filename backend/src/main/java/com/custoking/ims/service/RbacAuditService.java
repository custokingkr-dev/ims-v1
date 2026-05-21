package com.custoking.ims.service;

import com.custoking.ims.context.TenantContext;
import com.custoking.ims.entity.RbacAuditLogEntity;
import com.custoking.ims.repo.RbacAuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Records RBAC lifecycle events to the rbac_audit_log table.
 * Runs in a new transaction so audit writes don't roll back with the main operation.
 *
 * Event types:
 *   ROLE_CREATED, ROLE_UPDATED, ROLE_DISABLED,
 *   PERMISSION_ASSIGNED, PERMISSION_REMOVED,
 *   ROLE_ASSIGNED, ROLE_REVOKED, ROLE_SCOPE_CHANGED, ROLE_EXPIRY_CHANGED,
 *   USER_DISABLED, USER_ENABLED, PASSWORD_RESET, RBAC_ATTEMPT_DENIED
 */
@Service
public class RbacAuditService {

    private final RbacAuditLogRepository auditRepo;

    public RbacAuditService(RbacAuditLogRepository auditRepo) {
        this.auditRepo = auditRepo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logRoleAssigned(Long actorId, String actorEmail,
                                 Long targetUserId, Long roleId, String roleName,
                                 Long schoolId, Long zoneId) {
        write("ROLE_ASSIGNED", actorId, actorEmail, targetUserId,
              roleId, roleName, null, schoolId, zoneId, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logRoleRevoked(Long actorId, String actorEmail,
                                Long targetUserId, Long roleId, String roleName,
                                Long schoolId, Long zoneId) {
        write("ROLE_REVOKED", actorId, actorEmail, targetUserId,
              roleId, roleName, null, schoolId, zoneId, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logRoleCreated(Long actorId, String actorEmail,
                                Long roleId, String roleName, String permCodes) {
        write("ROLE_CREATED", actorId, actorEmail, null,
              roleId, roleName, permCodes, null, null, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logRoleUpdated(Long actorId, String actorEmail,
                                Long roleId, String roleName, String oldPerms, String newPerms) {
        write("ROLE_UPDATED", actorId, actorEmail, null,
              roleId, roleName, null, null, null, oldPerms, newPerms);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logPermissionAssigned(Long actorId, String actorEmail,
                                       Long roleId, String roleName, String permCode) {
        write("PERMISSION_ASSIGNED", actorId, actorEmail, null,
              roleId, roleName, permCode, null, null, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logPermissionRemoved(Long actorId, String actorEmail,
                                      Long roleId, String roleName, String permCode) {
        write("PERMISSION_REMOVED", actorId, actorEmail, null,
              roleId, roleName, permCode, null, null, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logRoleDisabled(Long actorId, String actorEmail, Long roleId, String roleName) {
        write("ROLE_DISABLED", actorId, actorEmail, null,
              roleId, roleName, null, null, null, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logRoleScopeChanged(Long actorId, String actorEmail,
                                    Long targetUserId, Long roleId, String roleName,
                                    String oldScope, String newScope) {
        write("ROLE_SCOPE_CHANGED", actorId, actorEmail, targetUserId,
              roleId, roleName, null, null, null, oldScope, newScope);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logRoleExpiryChanged(Long actorId, String actorEmail,
                                     Long targetUserId, Long roleId, String roleName,
                                     String oldExpiry, String newExpiry) {
        write("ROLE_EXPIRY_CHANGED", actorId, actorEmail, targetUserId,
              roleId, roleName, null, null, null, oldExpiry, newExpiry);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logUserDisabled(Long actorId, String actorEmail, Long targetUserId) {
        write("USER_DISABLED", actorId, actorEmail, targetUserId,
              null, null, null, null, null, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logUserEnabled(Long actorId, String actorEmail, Long targetUserId) {
        write("USER_ENABLED", actorId, actorEmail, targetUserId,
              null, null, null, null, null, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logFailedRbacAttempt(Long actorId, String actorEmail, String attemptedAction) {
        write("RBAC_ATTEMPT_DENIED", actorId, actorEmail, null,
              null, null, attemptedAction, null, null, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logPasswordReset(Long actorId, String actorEmail, Long targetUserId) {
        write("PASSWORD_RESET", actorId, actorEmail, targetUserId,
              null, null, null, null, null, null, null);
    }

    private void write(String eventType,
                       Long actorUserId, String actorEmail,
                       Long targetUserId, Long roleId, String roleName,
                       String permissionCodes,
                       Long schoolId, Long zoneId,
                       String oldValue, String newValue) {
        RbacAuditLogEntity e = new RbacAuditLogEntity();
        e.setEventType(eventType);
        e.setActorUserId(actorUserId);
        e.setActorEmail(actorEmail);
        e.setTargetUserId(targetUserId);
        e.setRoleId(roleId);
        e.setRoleName(roleName);
        e.setPermissionCodes(permissionCodes);
        e.setSchoolId(schoolId != null ? schoolId : TenantContext.get());
        e.setZoneId(zoneId);
        e.setOldValue(oldValue);
        e.setNewValue(newValue);
        e.setCorrelationId(MDC.get("requestId"));
        enrichFromRequest(e);
        auditRepo.save(e);
    }

    private static void enrichFromRequest(RbacAuditLogEntity e) {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) {
                HttpServletRequest req = sra.getRequest();
                e.setIpAddress(resolveClientIp(req));
                e.setUserAgent(req.getHeader("User-Agent"));
            }
        } catch (Exception ignored) {
            // Outside request scope (e.g. bootstrap) — skip enrichment
        }
    }

    private static String resolveClientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
