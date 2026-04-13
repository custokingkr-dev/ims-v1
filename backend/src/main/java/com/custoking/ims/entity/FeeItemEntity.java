package com.custoking.ims.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "fee_items")
public class FeeItemEntity {
    @Id private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "band_id") private FeeBandEntity band;
    private String name; private String frequency; private long amount; private OffsetDateTime createdAt = OffsetDateTime.now(); private OffsetDateTime updatedAt = OffsetDateTime.now();
    public String getId() { return id; } public void setId(String id) { this.id = id; }
    public FeeBandEntity getBand() { return band; } public void setBand(FeeBandEntity band) { this.band = band; }
    public String getName() { return name; } public void setName(String name) { this.name = name; }
    public String getFrequency() { return frequency; } public void setFrequency(String frequency) { this.frequency = frequency; }
    public long getAmount() { return amount; } public void setAmount(long amount) { this.amount = amount; }
    public OffsetDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; } public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
