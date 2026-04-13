package com.custoking.ims.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "superadmin_invoices")
public class SuperadminInvoiceEntity {
    @Id
    private String id;
    private String orderRef;
    private String school;
    private Long schoolId;
    private String description;
    private int qty;
    private long rate;
    private long amount;
    private long gstAmount;
    private long total;
    private String status;
    private String issuedAt;
    private String dueAt;
    private String notes;
    @Column(nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOrderRef() { return orderRef; }
    public void setOrderRef(String orderRef) { this.orderRef = orderRef; }
    public String getSchool() { return school; }
    public void setSchool(String school) { this.school = school; }
    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getQty() { return qty; }
    public void setQty(int qty) { this.qty = qty; }
    public long getRate() { return rate; }
    public void setRate(long rate) { this.rate = rate; }
    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
    public long getGstAmount() { return gstAmount; }
    public void setGstAmount(long gstAmount) { this.gstAmount = gstAmount; }
    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getIssuedAt() { return issuedAt; }
    public void setIssuedAt(String issuedAt) { this.issuedAt = issuedAt; }
    public String getDueAt() { return dueAt; }
    public void setDueAt(String dueAt) { this.dueAt = dueAt; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
