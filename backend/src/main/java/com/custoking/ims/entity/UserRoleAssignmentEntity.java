package com.custoking.ims.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "user_role_assignments")
public class UserRoleAssignmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUserEntity user;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private RoleEntity role;

    @Column(name = "assigned_at", nullable = false)
    private OffsetDateTime assignedAt = OffsetDateTime.now();

    @Column(name = "assigned_by")
    private Long assignedBy;

    /** Null = platform-wide scope (SUPERADMIN, ZONE_ADMIN). */
    @Column(name = "school_id")
    private Long schoolId;

    /** Null = not zone-scoped. */
    @Column(name = "zone_id")
    private Long zoneId;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "valid_from")
    private OffsetDateTime validFrom;

    @Column(name = "valid_until")
    private OffsetDateTime validUntil;

    @Column(name = "revoked_by")
    private Long revokedBy;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    /** True when this assignment is active, not revoked, and within its valid_from/valid_until window. */
    public boolean isEffective() {
        if (!active) return false;
        if (revokedAt != null) return false;
        OffsetDateTime now = OffsetDateTime.now();
        if (validFrom != null && now.isBefore(validFrom)) return false;
        if (validUntil != null && !now.isBefore(validUntil)) return false;
        return true;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public AppUserEntity getUser() { return user; }
    public void setUser(AppUserEntity user) { this.user = user; }
    public RoleEntity getRole() { return role; }
    public void setRole(RoleEntity role) { this.role = role; }
    public OffsetDateTime getAssignedAt() { return assignedAt; }
    public void setAssignedAt(OffsetDateTime assignedAt) { this.assignedAt = assignedAt; }
    public Long getAssignedBy() { return assignedBy; }
    public void setAssignedBy(Long assignedBy) { this.assignedBy = assignedBy; }
    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }
    public Long getZoneId() { return zoneId; }
    public void setZoneId(Long zoneId) { this.zoneId = zoneId; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public OffsetDateTime getValidFrom() { return validFrom; }
    public void setValidFrom(OffsetDateTime validFrom) { this.validFrom = validFrom; }
    public OffsetDateTime getValidUntil() { return validUntil; }
    public void setValidUntil(OffsetDateTime validUntil) { this.validUntil = validUntil; }
    public Long getRevokedBy() { return revokedBy; }
    public void setRevokedBy(Long revokedBy) { this.revokedBy = revokedBy; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(OffsetDateTime revokedAt) { this.revokedAt = revokedAt; }
}
