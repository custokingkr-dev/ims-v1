package com.custoking.ims.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "firefighting_requests")
public class FirefightingRequestEntity {
    @Id
    private String code;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "school_id")
    private SchoolEntity school;

    private String title;
    private String category;
    private String urgency;
    private LocalDate requiredByDate;
    private long estimatedBudget;
    @Column(columnDefinition = "TEXT")
    private String description;
    private String referenceFileUrl;
    private Long raisedBy;
    private String status;
    private String bursarNote;
    private String principalNote;
    private OffsetDateTime bursarApprovedAt;
    private OffsetDateTime principalApprovedAt;
    private String rejectedBy;
    private String rejectedReason;
    @Column(columnDefinition = "TEXT")
    private String custokingCriteriaJson;
    private String winnerVendor;
    private Long winnerAmount;
    private OffsetDateTime vendorPaidAt;
    private Long vendorPaidBy;
    @Column(columnDefinition = "TEXT")
    private String vendorPaymentNotes;
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public SchoolEntity getSchool() { return school; }
    public void setSchool(SchoolEntity school) { this.school = school; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getUrgency() { return urgency; }
    public void setUrgency(String urgency) { this.urgency = urgency; }
    public LocalDate getRequiredByDate() { return requiredByDate; }
    public void setRequiredByDate(LocalDate requiredByDate) { this.requiredByDate = requiredByDate; }
    public long getEstimatedBudget() { return estimatedBudget; }
    public void setEstimatedBudget(long estimatedBudget) { this.estimatedBudget = estimatedBudget; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getReferenceFileUrl() { return referenceFileUrl; }
    public void setReferenceFileUrl(String referenceFileUrl) { this.referenceFileUrl = referenceFileUrl; }
    public Long getRaisedBy() { return raisedBy; }
    public void setRaisedBy(Long raisedBy) { this.raisedBy = raisedBy; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getBursarNote() { return bursarNote; }
    public void setBursarNote(String bursarNote) { this.bursarNote = bursarNote; }
    public String getPrincipalNote() { return principalNote; }
    public void setPrincipalNote(String principalNote) { this.principalNote = principalNote; }
    public OffsetDateTime getBursarApprovedAt() { return bursarApprovedAt; }
    public void setBursarApprovedAt(OffsetDateTime bursarApprovedAt) { this.bursarApprovedAt = bursarApprovedAt; }
    public OffsetDateTime getPrincipalApprovedAt() { return principalApprovedAt; }
    public void setPrincipalApprovedAt(OffsetDateTime principalApprovedAt) { this.principalApprovedAt = principalApprovedAt; }
    public String getRejectedBy() { return rejectedBy; }
    public void setRejectedBy(String rejectedBy) { this.rejectedBy = rejectedBy; }
    public String getRejectedReason() { return rejectedReason; }
    public void setRejectedReason(String rejectedReason) { this.rejectedReason = rejectedReason; }
    public String getCustokingCriteriaJson() { return custokingCriteriaJson; }
    public void setCustokingCriteriaJson(String custokingCriteriaJson) { this.custokingCriteriaJson = custokingCriteriaJson; }
    public String getWinnerVendor() { return winnerVendor; }
    public void setWinnerVendor(String winnerVendor) { this.winnerVendor = winnerVendor; }
    public Long getWinnerAmount() { return winnerAmount; }
    public void setWinnerAmount(Long winnerAmount) { this.winnerAmount = winnerAmount; }
    public OffsetDateTime getVendorPaidAt() { return vendorPaidAt; }
    public void setVendorPaidAt(OffsetDateTime vendorPaidAt) { this.vendorPaidAt = vendorPaidAt; }
    public Long getVendorPaidBy() { return vendorPaidBy; }
    public void setVendorPaidBy(Long vendorPaidBy) { this.vendorPaidBy = vendorPaidBy; }
    public String getVendorPaymentNotes() { return vendorPaymentNotes; }
    public void setVendorPaymentNotes(String vendorPaymentNotes) { this.vendorPaymentNotes = vendorPaymentNotes; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
