package com.custoking.ims.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "catalog_orders")
public class CatalogOrderEntity {
    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "school_id")
    private SchoolEntity school;

    @Column(nullable = false)
    private String category;

    @Column(name = "order_data", columnDefinition = "TEXT")
    private String orderData;

    private long subtotal;
    private long gst;
    @Column(name = "total_amount")
    private long totalAmount;
    private String status;
    private String classGroup;
    private String logoOnUniform;
    private String notebookCoverLogo;
    private String notebookDeliveryMode;
    private String notebookSpineName;
    private String stationeryPackType;
    private String eventName;
    private LocalDate eventDate;
    private String designStatus;
    private String superadminApprovalStatus;
    private LocalDate requiredByDate;
    private String estimatedDelivery;
    private Long placedBy;
    private OffsetDateTime placedAt;
    private String notes;
    private OffsetDateTime vendorPaidAt;
    private Long vendorPaidBy;
    @Column(columnDefinition = "TEXT")
    private String vendorPaymentNotes;
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public SchoolEntity getSchool() { return school; }
    public void setSchool(SchoolEntity school) { this.school = school; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getOrderData() { return orderData; }
    public void setOrderData(String orderData) { this.orderData = orderData; }
    public long getSubtotal() { return subtotal; }
    public void setSubtotal(long subtotal) { this.subtotal = subtotal; }
    public long getGst() { return gst; }
    public void setGst(long gst) { this.gst = gst; }
    public long getTotalAmount() { return totalAmount; }
    public void setTotalAmount(long totalAmount) { this.totalAmount = totalAmount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getClassGroup() { return classGroup; }
    public void setClassGroup(String classGroup) { this.classGroup = classGroup; }
    public String getLogoOnUniform() { return logoOnUniform; }
    public void setLogoOnUniform(String logoOnUniform) { this.logoOnUniform = logoOnUniform; }
    public String getNotebookCoverLogo() { return notebookCoverLogo; }
    public void setNotebookCoverLogo(String notebookCoverLogo) { this.notebookCoverLogo = notebookCoverLogo; }
    public String getNotebookDeliveryMode() { return notebookDeliveryMode; }
    public void setNotebookDeliveryMode(String notebookDeliveryMode) { this.notebookDeliveryMode = notebookDeliveryMode; }
    public String getNotebookSpineName() { return notebookSpineName; }
    public void setNotebookSpineName(String notebookSpineName) { this.notebookSpineName = notebookSpineName; }
    public String getStationeryPackType() { return stationeryPackType; }
    public void setStationeryPackType(String stationeryPackType) { this.stationeryPackType = stationeryPackType; }
    public String getEventName() { return eventName; }
    public void setEventName(String eventName) { this.eventName = eventName; }
    public LocalDate getEventDate() { return eventDate; }
    public void setEventDate(LocalDate eventDate) { this.eventDate = eventDate; }
    public String getDesignStatus() { return designStatus; }
    public void setDesignStatus(String designStatus) { this.designStatus = designStatus; }
    public String getSuperadminApprovalStatus() { return superadminApprovalStatus; }
    public void setSuperadminApprovalStatus(String superadminApprovalStatus) { this.superadminApprovalStatus = superadminApprovalStatus; }
    public LocalDate getRequiredByDate() { return requiredByDate; }
    public void setRequiredByDate(LocalDate requiredByDate) { this.requiredByDate = requiredByDate; }
    public String getEstimatedDelivery() { return estimatedDelivery; }
    public void setEstimatedDelivery(String estimatedDelivery) { this.estimatedDelivery = estimatedDelivery; }
    public Long getPlacedBy() { return placedBy; }
    public void setPlacedBy(Long placedBy) { this.placedBy = placedBy; }
    public OffsetDateTime getPlacedAt() { return placedAt; }
    public void setPlacedAt(OffsetDateTime placedAt) { this.placedAt = placedAt; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public OffsetDateTime getVendorPaidAt() { return vendorPaidAt; }
    public void setVendorPaidAt(OffsetDateTime vendorPaidAt) { this.vendorPaidAt = vendorPaidAt; }
    public Long getVendorPaidBy() { return vendorPaidBy; }
    public void setVendorPaidBy(Long vendorPaidBy) { this.vendorPaidBy = vendorPaidBy; }
    public String getVendorPaymentNotes() { return vendorPaymentNotes; }
    public void setVendorPaymentNotes(String vendorPaymentNotes) { this.vendorPaymentNotes = vendorPaymentNotes; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
