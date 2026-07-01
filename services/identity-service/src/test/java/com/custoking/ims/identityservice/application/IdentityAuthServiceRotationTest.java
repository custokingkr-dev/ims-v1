package com.custoking.ims.identityservice.application;

import com.custoking.ims.identityservice.persistence.AppUserEntity;
import com.custoking.ims.identityservice.persistence.AppUserRepository;
import com.custoking.ims.identityservice.persistence.AuthAuditRepository;
import com.custoking.ims.identityservice.persistence.AuthSessionEntity;
import com.custoking.ims.identityservice.persistence.AuthSessionRepository;
import com.custoking.ims.identityservice.persistence.RbacLookupRepository;
import com.custoking.ims.identityservice.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdentityAuthServiceRotationTest {

    private AppUserRepository users;
    private AuthSessionRepository sessions;
    private RbacLookupRepository rbac;
    private PasswordEncoder passwordEncoder;
    private JwtService jwtService;
    private AuthAuditRepository authAudit;
    private IdentityAuthService service;

    private static final String EMAIL = "test@example.com";
    private static final String RAW_TOKEN = "raw-refresh-token";
    private static final String FAMILY_ID = "F";

    @BeforeEach
    void setUp() {
        users = mock(AppUserRepository.class);
        sessions = mock(AuthSessionRepository.class);
        rbac = mock(RbacLookupRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtService = mock(JwtService.class);
        authAudit = mock(AuthAuditRepository.class);
        service = new IdentityAuthService(users, sessions, rbac, passwordEncoder, jwtService, authAudit);
    }

    /** Builds a mock user with id=7 and email=EMAIL. */
    private AppUserEntity mockUser() {
        AppUserEntity user = mock(AppUserEntity.class);
        when(user.getId()).thenReturn(7L);
        when(user.getEmail()).thenReturn(EMAIL);
        when(user.getFullName()).thenReturn("Test User");
        when(user.getRole()).thenReturn("ADMIN");
        when(user.isDisabled()).thenReturn(false);
        return user;
    }

    /** Builds a session fixture with the given status; user is set to a mock with id=7. */
    private AuthSessionEntity sessionFixture(String status, OffsetDateTime expiresAt) {
        AppUserEntity user = mockUser();
        AuthSessionEntity session = new AuthSessionEntity();
        session.setId("old-session-id");
        session.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        session.setFamilyId(FAMILY_ID);
        session.setStatus(status);
        session.setUser(user);
        session.setAccessTokenHash("old-access-hash");
        session.setRefreshTokenHash("old-refresh-hash");
        session.setExpiresAt(expiresAt);
        return session;
    }

    /** Stubs the JWT validation prologue so extractUsername, isRefreshToken, isTokenValid all succeed. */
    private void stubJwtPrologue() {
        when(jwtService.extractUsername(RAW_TOKEN)).thenReturn(EMAIL);
        when(jwtService.isRefreshToken(RAW_TOKEN)).thenReturn(true);
        when(jwtService.isTokenValid(RAW_TOKEN, EMAIL)).thenReturn(true);
    }

    /** Stubs JwtService to generate new tokens and stubs rbac for responseFor(). */
    private void stubTokenGeneration(AppUserEntity user) {
        when(jwtService.generateAccessToken(any())).thenReturn("new-access-token");
        when(jwtService.generateRefreshToken(any())).thenReturn("new-refresh-token");
        when(jwtService.extractExpiration("new-refresh-token"))
                .thenReturn(new Date(System.currentTimeMillis() + 3_600_000));
        when(rbac.roleNames(7L)).thenReturn(List.of());
        when(rbac.permissionCodes(7L)).thenReturn(Set.of());
    }

    @Test
    void refresh_activeToken_rotatesWithinFamily() {
        // Arrange
        AppUserEntity user = mockUser();
        AuthSessionEntity activeSession = sessionFixture(AuthSessionEntity.ACTIVE,
                OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));
        stubJwtPrologue();
        when(sessions.findByRefreshTokenHash(any())).thenReturn(Optional.of(activeSession));
        when(users.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
        stubTokenGeneration(user);

        // Act
        IdentityAuthService.LoginResult result = service.refresh(RAW_TOKEN);

        // Assert: the old session was saved as ROTATED
        ArgumentCaptor<AuthSessionEntity> captor = ArgumentCaptor.forClass(AuthSessionEntity.class);
        verify(sessions, times(2)).save(captor.capture());

        AuthSessionEntity savedOld = captor.getAllValues().get(0);
        assertThat(savedOld.getStatus()).isEqualTo(AuthSessionEntity.ROTATED);
        assertThat(savedOld.getRotatedAt()).isNotNull();

        AuthSessionEntity savedNew = captor.getAllValues().get(1);
        assertThat(savedNew.getStatus()).isEqualTo(AuthSessionEntity.ACTIVE);
        assertThat(savedNew.getFamilyId()).isEqualTo(FAMILY_ID);
        assertThat(savedNew.getId()).isNotEqualTo("old-session-id");

        // revokeFamily and audit must never be called
        verify(sessions, never()).revokeFamily(any());
        verify(authAudit, never()).recordRefreshTokenReuse(any(), anyString(), anyString());

        assertThat(result).isNotNull();
    }

    @Test
    void refresh_rotatedToken_reuseDetected_revokesFamily() {
        // Arrange: presented token is already ROTATED — theft signal
        AuthSessionEntity rotatedSession = sessionFixture(AuthSessionEntity.ROTATED,
                OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));
        stubJwtPrologue();
        when(sessions.findByRefreshTokenHash(any())).thenReturn(Optional.of(rotatedSession));

        // Act + Assert
        assertThatThrownBy(() -> service.refresh(RAW_TOKEN))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Refresh token reuse detected - session revoked");

        verify(sessions).revokeFamily(FAMILY_ID);
        verify(authAudit).recordRefreshTokenReuse(7L, EMAIL, FAMILY_ID);
    }

    @Test
    void refresh_revokedToken_reuseDetected() {
        // Arrange: presented token is REVOKED — also a theft signal
        AuthSessionEntity revokedSession = sessionFixture(AuthSessionEntity.REVOKED,
                OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));
        stubJwtPrologue();
        when(sessions.findByRefreshTokenHash(any())).thenReturn(Optional.of(revokedSession));

        // Act + Assert
        assertThatThrownBy(() -> service.refresh(RAW_TOKEN))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Refresh token reuse detected - session revoked");

        verify(sessions).revokeFamily(FAMILY_ID);
        verify(authAudit).recordRefreshTokenReuse(7L, EMAIL, FAMILY_ID);
    }

    @Test
    void refresh_unknownHash_401_noRevoke() {
        // Arrange: hash not found in DB
        stubJwtPrologue();
        when(sessions.findByRefreshTokenHash(any())).thenReturn(Optional.empty());

        // Act + Assert
        assertThatThrownBy(() -> service.refresh(RAW_TOKEN))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid refresh token");

        verify(sessions, never()).revokeFamily(any());
        verify(authAudit, never()).recordRefreshTokenReuse(any(), anyString(), anyString());
    }

    @Test
    void refresh_expiredActive_401_noRevoke() {
        // Arrange: ACTIVE but expired
        AuthSessionEntity expiredSession = sessionFixture(AuthSessionEntity.ACTIVE,
                OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
        stubJwtPrologue();
        when(sessions.findByRefreshTokenHash(any())).thenReturn(Optional.of(expiredSession));

        // Act + Assert
        assertThatThrownBy(() -> service.refresh(RAW_TOKEN))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Refresh token expired");

        verify(sessions, never()).revokeFamily(any());
        verify(authAudit, never()).recordRefreshTokenReuse(any(), anyString(), anyString());
    }

    @Test
    void logout_revokesFamily() {
        // Arrange
        AuthSessionEntity session = sessionFixture(AuthSessionEntity.ACTIVE,
                OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));
        when(sessions.findByRefreshTokenHash(any())).thenReturn(Optional.of(session));

        // Act
        service.logout(RAW_TOKEN);

        // Assert: revokeFamily called with the session's familyId
        verify(sessions).revokeFamily(FAMILY_ID);
    }

    @Test
    void login_startsNewFamily() {
        // Arrange
        AppUserEntity user = mockUser();
        when(user.getPasswordHash()).thenReturn("hashed-password");
        when(users.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret", "hashed-password")).thenReturn(true);
        stubTokenGeneration(user);

        // Act
        service.login(new IdentityAuthService.LoginRequest(EMAIL, "secret"));

        // Assert: saved session has ACTIVE status and a non-null familyId (new UUID)
        ArgumentCaptor<AuthSessionEntity> captor = ArgumentCaptor.forClass(AuthSessionEntity.class);
        verify(sessions).save(captor.capture());
        AuthSessionEntity saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(AuthSessionEntity.ACTIVE);
        assertThat(saved.getFamilyId()).isNotNull().isNotBlank();
    }
}
