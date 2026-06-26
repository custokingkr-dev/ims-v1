package com.custoking.ims.identityservice.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "app_users")
public class AppUserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "app_user_seq")
    @SequenceGenerator(name = "app_user_seq", sequenceName = "seq_app_users", allocationSize = 1)
    private Long id;

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String role;

    private Long branchId;
    private String branchName;
    private Long zoneId;
    private String zoneName;
    private OffsetDateTime createdAt;
    private OffsetDateTime deletedAt;
    private String deletedBy;

    public boolean isDisabled() { return deletedAt != null; }
    public Long getId() { return id; }
    public String getFullName() { return fullName; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getRole() { return role; }
    public Long getBranchId() { return branchId; }
    public String getBranchName() { return branchName; }
    public Long getZoneId() { return zoneId; }
    public String getZoneName() { return zoneName; }
}
