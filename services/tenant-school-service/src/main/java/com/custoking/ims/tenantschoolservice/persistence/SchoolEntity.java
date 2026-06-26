package com.custoking.ims.tenantschoolservice.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "schools")
public class SchoolEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "school_seq")
    @SequenceGenerator(name = "school_seq", sequenceName = "tenant_school.seq_schools", allocationSize = 1)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "short_code", nullable = false)
    private String shortCode;

    private String city;
    private String state;
    private String contactEmail;
    private String contactPhone;
    private boolean active;
    private Integer configuredClassCount;
    private Integer configuredSectionCount;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getShortCode() { return shortCode; }
    public String getCity() { return city; }
    public String getState() { return state; }
    public String getContactEmail() { return contactEmail; }
    public String getContactPhone() { return contactPhone; }
    public boolean isActive() { return active; }
    public Integer getConfiguredClassCount() { return configuredClassCount; }
    public Integer getConfiguredSectionCount() { return configuredSectionCount; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
