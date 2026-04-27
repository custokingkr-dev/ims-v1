package com.custoking.ims.audit;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "audit_log", indexes = {
        @Index(name = "idx_audit_log_user_id",   columnList = "user_id"),
        @Index(name = "idx_audit_log_school_ts", columnList = "school_id,timestamp DESC"),
        @Index(name = "idx_audit_log_action",    columnList = "action")
})
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String action;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "school_id")
    private Long schoolId;

    @Column(name = "entity_type")
    private String entityType;

    @Column(name = "entity_id")
    private String entityId;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "actor_email", length = 255)
    private String actorEmail;

    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    @Column(length = 32, nullable = false)
    private String outcome = "SUCCESS";

    @Column(nullable = false)
    private OffsetDateTime timestamp = OffsetDateTime.now();

    // ── getters / setters ────────────────────────────────────────────────────

    public Long getId()                        { return id; }
    public String getAction()                  { return action; }
    public void setAction(String action)       { this.action = action; }
    public Long getUserId()                    { return userId; }
    public void setUserId(Long userId)         { this.userId = userId; }
    public Long getSchoolId()                  { return schoolId; }
    public void setSchoolId(Long schoolId)     { this.schoolId = schoolId; }
    public String getEntityType()              { return entityType; }
    public void setEntityType(String t)        { this.entityType = t; }
    public String getEntityId()                { return entityId; }
    public void setEntityId(String id)         { this.entityId = id; }
    public String getIpAddress()               { return ipAddress; }
    public void setIpAddress(String ip)        { this.ipAddress = ip; }
    public String getUserAgent()               { return userAgent; }
    public void setUserAgent(String ua)        { this.userAgent = ua; }
    public String getRequestId()               { return requestId; }
    public void setRequestId(String rid)       { this.requestId = rid; }
    public String getActorEmail()               { return actorEmail; }
    public void setActorEmail(String email)    { this.actorEmail = email; }
    public String getOldValue()                { return oldValue; }
    public void setOldValue(String v)          { this.oldValue = v; }
    public String getNewValue()                { return newValue; }
    public void setNewValue(String v)          { this.newValue = v; }
    public String getOutcome()                 { return outcome; }
    public void setOutcome(String outcome)     { this.outcome = outcome; }
    public OffsetDateTime getTimestamp()       { return timestamp; }
    public void setTimestamp(OffsetDateTime ts){ this.timestamp = ts; }
}
