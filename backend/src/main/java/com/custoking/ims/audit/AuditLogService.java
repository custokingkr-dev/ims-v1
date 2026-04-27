package com.custoking.ims.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.OffsetDateTime;
import java.util.function.Consumer;

/**
 * Persists every significant application event to the audit_log table and
 * simultaneously emits a structured log line (for Cloud Logging / SIEM).
 *
 * Events are written in a NEW transaction so that a rollback in the calling
 * service does not erase the audit record.  The write is also async so that
 * the calling thread is never delayed by audit I/O.
 *
 * Self-injection via @Lazy is intentional: the public event helpers call
 * self.record() so that Spring's AOP proxy is invoked, activating both
 * @Async and @Transactional(REQUIRES_NEW) on record().  Direct this.record()
 * would bypass the proxy and run synchronously inside the caller's transaction.
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger("audit");

    private final AuditLogRepository repo;

    // Injected lazily to avoid circular-dependency issues at startup.
    @Autowired @Lazy
    private AuditLogService self;

    public AuditLogService(AuditLogRepository repo) {
        this.repo = repo;
    }

    // ── public event helpers ─────────────────────────────────────────────────

    public void loginSuccess(Long userId, String email, String ip) {
        self.record(b -> b.action("LOGIN_SUCCESS")
                     .userId(userId)
                     .actorEmail(email)
                     .ipAddress(ip)
                     .outcome("SUCCESS"));
    }

    public void loginFailure(String email, String ip) {
        self.record(b -> b.action("LOGIN_FAILURE")
                     .actorEmail(email)
                     .ipAddress(ip)
                     .outcome("FAILURE"));
    }

    public void statusTransition(String entityType, String entityId,
                                  String oldStatus, String newStatus, Long actorUserId) {
        self.record(b -> b.action("STATUS_TRANSITION")
                     .entityType(entityType)
                     .entityId(entityId)
                     .oldValue(oldStatus)
                     .newValue(newStatus)
                     .userId(actorUserId)
                     .outcome("SUCCESS"));
    }

    public void dataExport(String entityType, Long actorUserId, Long schoolId) {
        self.record(b -> b.action("DATA_EXPORT")
                     .entityType(entityType)
                     .userId(actorUserId)
                     .schoolId(schoolId)
                     .outcome("SUCCESS"));
    }

    public void permissionDenied(Long actorUserId, String resource) {
        self.record(b -> b.action("PERMISSION_DENIED")
                     .entityType(resource)
                     .userId(actorUserId)
                     .outcome("DENIED"));
    }

    public void recordEvent(String action, Long userId, Long schoolId,
                             String entityType, String entityId,
                             String oldValue, String newValue) {
        self.record(b -> b.action(action)
                     .userId(userId)
                     .schoolId(schoolId)
                     .entityType(entityType)
                     .entityId(entityId)
                     .oldValue(oldValue)
                     .newValue(newValue)
                     .outcome("SUCCESS"));
    }

    // ── internals ────────────────────────────────────────────────────────────

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Consumer<Builder> configure) {
        Builder b = new Builder();
        configure.accept(b);
        AuditLogEntity entity = b.build();

        entity.setRequestId(MDC.get("requestId"));

        try {
            var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                String ua = attrs.getRequest().getHeader("User-Agent");
                if (ua != null) entity.setUserAgent(ua.length() > 512 ? ua.substring(0, 512) : ua);
            }
        } catch (Exception ignored) { }

        try {
            repo.save(entity);
        } catch (Exception ex) {
            log.error("Failed to persist audit event action={}", entity.getAction(), ex);
        }

        log.info("action={} userId={} schoolId={} entityType={} entityId={} outcome={} requestId={}",
                entity.getAction(), entity.getUserId(), entity.getSchoolId(),
                entity.getEntityType(), entity.getEntityId(),
                entity.getOutcome(), entity.getRequestId());
    }

    // ── fluent builder ───────────────────────────────────────────────────────

    public static final class Builder {
        private final AuditLogEntity e = new AuditLogEntity();

        public Builder action(String v)      { e.setAction(v);      return this; }
        public Builder userId(Long v)        { e.setUserId(v);      return this; }
        public Builder schoolId(Long v)      { e.setSchoolId(v);    return this; }
        public Builder entityType(String v)  { e.setEntityType(v);  return this; }
        public Builder entityId(String v)    { e.setEntityId(v);    return this; }
        public Builder actorEmail(String v)  { e.setActorEmail(v);  return this; }
        public Builder ipAddress(String v)   { e.setIpAddress(v);   return this; }
        public Builder oldValue(String v)    { e.setOldValue(v);    return this; }
        public Builder newValue(String v)    { e.setNewValue(v);    return this; }
        public Builder outcome(String v)     { e.setOutcome(v);     return this; }

        public AuditLogEntity build() {
            e.setTimestamp(OffsetDateTime.now());
            return e;
        }
    }
}
