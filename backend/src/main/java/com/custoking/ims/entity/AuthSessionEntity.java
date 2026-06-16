package com.custoking.ims.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "auth_sessions", indexes = {
        @Index(name = "idx_auth_session_access_hash", columnList = "access_token_hash", unique = true),
        @Index(name = "idx_auth_session_refresh_hash", columnList = "refresh_token_hash", unique = true)
})
public class AuthSessionEntity {
    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private AppUserEntity user;

    @Column(name = "access_token_hash", nullable = false, length = 200)
    private String accessTokenHash;

    @Column(name = "refresh_token_hash", nullable = false, length = 200)
    private String refreshTokenHash;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime expiresAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public AppUserEntity getUser() { return user; }
    public void setUser(AppUserEntity user) { this.user = user; }
    public String getAccessTokenHash() { return accessTokenHash; }
    public void setAccessTokenHash(String accessTokenHash) { this.accessTokenHash = accessTokenHash; }
    public String getRefreshTokenHash() { return refreshTokenHash; }
    public void setRefreshTokenHash(String refreshTokenHash) { this.refreshTokenHash = refreshTokenHash; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
}
