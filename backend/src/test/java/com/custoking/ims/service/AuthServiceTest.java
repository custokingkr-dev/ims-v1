package com.custoking.ims.service;

import com.custoking.ims.auth.service.AuthService;
import com.custoking.ims.dto.LoginRequest;
import com.custoking.ims.dto.LoginResult;
import com.custoking.ims.entity.AppUserEntity;
import com.custoking.ims.entity.AuthSessionEntity;
import com.custoking.ims.repo.AppUserRepository;
import com.custoking.ims.repo.AuthSessionRepository;
import com.custoking.ims.security.AppUserDetails;
import com.custoking.ims.security.AppUserDetailsService;
import com.custoking.ims.security.JwtService;
import com.custoking.ims.audit.AuditLogService;
import com.custoking.ims.util.PasswordUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock AppUserRepository userRepository;
    @Mock JwtService jwtService;
    @Mock AppUserDetailsService userDetailsService;
    @Mock PasswordUtil passwordUtil;
    @Mock AuditLogService auditLogService;
    @Mock RbacService rbacService;
    @Mock AuthSessionRepository authSessionRepository;
    @InjectMocks AuthService authService;

    private AppUserEntity user;

    @BeforeEach
    void setUp() {
        user = new AppUserEntity();
        user.setId(1L);
        user.setEmail("admin@test.com");
        user.setPasswordHash("hashed-secret");
        user.setRole("ADMIN");
        user.setFullName("Admin User");
    }

    @Test
    void login_withValidCredentials_returnsLoginResult() {
        when(userRepository.findByEmailIgnoreCase("admin@test.com")).thenReturn(Optional.of(user));
        when(passwordUtil.verify("secret", "hashed-secret")).thenReturn(true);
        when(jwtService.generateToken(any(AppUserDetails.class))).thenReturn("access-jwt");
        when(jwtService.generateRefreshToken(any(AppUserDetails.class))).thenReturn("refresh-jwt");
        when(jwtService.extractExpiration("refresh-jwt"))
                .thenReturn(new Date(System.currentTimeMillis() + 604800000L));
        when(rbacService.getUserRoleNames(1L)).thenReturn(List.of("ADMIN"));
        when(rbacService.getUserPermissions(1L)).thenReturn(Set.of());

        LoginResult result = authService.login(new LoginRequest("admin@test.com", "secret"));

        assertThat(result.refreshToken()).isEqualTo("refresh-jwt");
        assertThat(result.authResponse().accessToken()).isEqualTo("access-jwt");
        assertThat(result.authResponse().email()).isEqualTo("admin@test.com");
        assertThat(result.authResponse().role()).isEqualTo("ADMIN");
        verify(authSessionRepository).save(any(AuthSessionEntity.class));
    }

    @Test
    void login_withWrongPassword_throwsUnauthorized() {
        when(userRepository.findByEmailIgnoreCase("admin@test.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest("admin@test.com", "wrong")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void login_withUnknownEmail_throwsUnauthorized() {
        when(userRepository.findByEmailIgnoreCase("nobody@test.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@test.com", "secret")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void refresh_withValidToken_returnsNewLoginResult() {
        when(jwtService.extractUsername("good-refresh")).thenReturn("admin@test.com");
        when(jwtService.isRefreshToken("good-refresh")).thenReturn(true);
        when(jwtService.isTokenValid("good-refresh", "admin@test.com")).thenReturn(true);
        when(userDetailsService.loadUserByUsername("admin@test.com"))
                .thenReturn(new AppUserDetails(user));
        when(jwtService.generateToken(any(AppUserDetails.class))).thenReturn("new-access-jwt");
        when(jwtService.generateRefreshToken(any(AppUserDetails.class))).thenReturn("new-refresh-jwt");
        when(jwtService.extractExpiration("new-refresh-jwt"))
                .thenReturn(new Date(System.currentTimeMillis() + 604800000L));
        AuthSessionEntity session = sessionFor(user, "good-refresh", OffsetDateTime.now(ZoneOffset.UTC).plusDays(1));
        when(authSessionRepository.findByRefreshTokenHash(sha256("good-refresh"))).thenReturn(Optional.of(session));
        when(rbacService.getUserRoleNames(1L)).thenReturn(List.of("ADMIN"));
        when(rbacService.getUserPermissions(1L)).thenReturn(Set.of());

        LoginResult result = authService.refresh("good-refresh");

        assertThat(result.authResponse().accessToken()).isEqualTo("new-access-jwt");
        assertThat(result.refreshToken()).isEqualTo("new-refresh-jwt");
        verify(authSessionRepository).save(session);
    }

    @Test
    void refresh_withExpiredToken_throwsUnauthorized() {
        when(jwtService.extractUsername("expired-refresh")).thenReturn("admin@test.com");
        when(jwtService.isRefreshToken("expired-refresh")).thenReturn(true);
        when(jwtService.isTokenValid("expired-refresh", "admin@test.com")).thenReturn(false);

        assertThatThrownBy(() -> authService.refresh("expired-refresh"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void refresh_withRotatedOrRevokedToken_throwsUnauthorized() {
        when(jwtService.extractUsername("old-refresh")).thenReturn("admin@test.com");
        when(jwtService.isRefreshToken("old-refresh")).thenReturn(true);
        when(jwtService.isTokenValid("old-refresh", "admin@test.com")).thenReturn(true);
        when(authSessionRepository.findByRefreshTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh("old-refresh"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void logout_deletesMatchingRefreshSession() {
        AuthSessionEntity session = sessionFor(user, "refresh-jwt", OffsetDateTime.now(ZoneOffset.UTC).plusDays(1));
        when(authSessionRepository.findByRefreshTokenHash(sha256("refresh-jwt"))).thenReturn(Optional.of(session));

        authService.logout("refresh-jwt");

        verify(authSessionRepository).delete(session);
    }

    private AuthSessionEntity sessionFor(AppUserEntity user, String refreshToken, OffsetDateTime expiresAt) {
        AuthSessionEntity session = new AuthSessionEntity();
        session.setId("session-id");
        session.setUser(user);
        session.setAccessTokenHash(sha256("access-token"));
        session.setRefreshTokenHash(sha256(refreshToken));
        session.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        session.setExpiresAt(expiresAt);
        return session;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
