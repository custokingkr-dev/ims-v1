package com.custoking.ims.commandcenter;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "command_center_actions")
public class CommandCenterActionEntity {

    @Id
    private UUID id;

    @Column(name = "school_id")
    private Long schoolId;

    @Column(nullable = false)
    private String module;

    @Column(nullable = false)
    private String urgency;

    @Column(nullable = false)
    private int confidence = 80;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(columnDefinition = "TEXT")
    private String impact;

    @Column(name = "current_state")
    private String currentState;

    @Column(name = "target_state")
    private String targetState;

    @Column(name = "cta_label")
    private String ctaLabel;

    @Column(nullable = false)
    private String status = "OPEN";

    @Column(name = "source_type")
    private String sourceType;

    @Column(name = "source_id")
    private String sourceId;

    @Column(name = "accepted_by")
    private Long acceptedBy;

    @Column(name = "accepted_at")
    private OffsetDateTime acceptedAt;

    @Column(name = "dismissed_by")
    private Long dismissedBy;

    @Column(name = "dismissed_reason", columnDefinition = "TEXT")
    private String dismissedReason;

    @Column(name = "dismissed_at")
    private OffsetDateTime dismissedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void preUpdate() { updatedAt = OffsetDateTime.now(); }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }
    public String getModule() { return module; }
    public void setModule(String module) { this.module = module; }
    public String getUrgency() { return urgency; }
    public void setUrgency(String urgency) { this.urgency = urgency; }
    public int getConfidence() { return confidence; }
    public void setConfidence(int confidence) { this.confidence = confidence; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getImpact() { return impact; }
    public void setImpact(String impact) { this.impact = impact; }
    public String getCurrentState() { return currentState; }
    public void setCurrentState(String currentState) { this.currentState = currentState; }
    public String getTargetState() { return targetState; }
    public void setTargetState(String targetState) { this.targetState = targetState; }
    public String getCtaLabel() { return ctaLabel; }
    public void setCtaLabel(String ctaLabel) { this.ctaLabel = ctaLabel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public Long getAcceptedBy() { return acceptedBy; }
    public void setAcceptedBy(Long acceptedBy) { this.acceptedBy = acceptedBy; }
    public OffsetDateTime getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(OffsetDateTime acceptedAt) { this.acceptedAt = acceptedAt; }
    public Long getDismissedBy() { return dismissedBy; }
    public void setDismissedBy(Long dismissedBy) { this.dismissedBy = dismissedBy; }
    public String getDismissedReason() { return dismissedReason; }
    public void setDismissedReason(String dismissedReason) { this.dismissedReason = dismissedReason; }
    public OffsetDateTime getDismissedAt() { return dismissedAt; }
    public void setDismissedAt(OffsetDateTime dismissedAt) { this.dismissedAt = dismissedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
