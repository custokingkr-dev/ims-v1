package com.custoking.ims.identityservice.application;

import com.custoking.ims.identityservice.persistence.AppUserEntity;
import com.custoking.ims.identityservice.persistence.AppUserRepository;
import com.custoking.ims.identityservice.persistence.AuthAuditRepository;
import com.custoking.ims.identityservice.persistence.AuthSessionEntity;
import com.custoking.ims.identityservice.persistence.AuthSessionRepository;
import com.custoking.ims.identityservice.persistence.RbacLookupRepository;
import com.custoking.ims.identityservice.security.JwtService;
import io.jsonwebtoken.Claims;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional
public class IdentityAuthService {

    private final AppUserRepository users;
    private final AuthSessionRepository sessions;
    private final RbacLookupRepository rbac;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthAuditRepository authAudit;

    public IdentityAuthService(AppUserRepository users,
                               AuthSessionRepository sessions,
                               RbacLookupRepository rbac,
                               PasswordEncoder passwordEncoder,
                               JwtService jwtService,
                               AuthAuditRepository authAudit) {
        this.users = users;
        this.sessions = sessions;
        this.rbac = rbac;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authAudit = authAudit;
    }

    public LoginResult login(LoginRequest request) {
        AppUserEntity user = users.findByEmailIgnoreCase(request.email())
                .orElseThrow(() -> unauthorized("Invalid email or password"));
        if (user.isDisabled() || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw unauthorized("Invalid email or password");
        }
        sessions.deleteByExpiresAtBefore(OffsetDateTime.now(ZoneOffset.UTC));
        return issueSession(user, UUID.randomUUID().toString());
    }

    public LoginResult refresh(String rawRefreshToken) {
        String email;
        try {
            email = jwtService.extractUsername(rawRefreshToken);
            if (!jwtService.isRefreshToken(rawRefreshToken) || !jwtService.isTokenValid(rawRefreshToken, email)) {
                throw unauthorized("Invalid refresh token");
            }
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw unauthorized("Invalid refresh token");
        }
        AuthSessionEntity session = sessions.findByRefreshTokenHash(tokenDigest(rawRefreshToken))
                .orElseThrow(() -> unauthorized("Invalid refresh token"));

        // Reuse detection: a replay of a retired/revoked token is a theft signal.
        if (!AuthSessionEntity.ACTIVE.equals(session.getStatus())) {
            sessions.revokeFamily(session.getFamilyId());
            authAudit.recordRefreshTokenReuse(session.getUser().getId(), email, session.getFamilyId());
            throw unauthorized("Refresh token reuse detected - session revoked");
        }
        if (session.getExpiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw unauthorized("Refresh token expired");
        }
        AppUserEntity user = users.findByEmailIgnoreCase(email)
                .orElseThrow(() -> unauthorized("Invalid refresh token"));
        if (!Objects.equals(session.getUser().getId(), user.getId())) {
            throw unauthorized("Invalid refresh token");
        }
        // Rotate: retire the presented token, issue a new one in the same family.
        session.setStatus(AuthSessionEntity.ROTATED);
        session.setRotatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        sessions.save(session);
        return issueSession(user, session.getFamilyId());
    }

    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        sessions.findByRefreshTokenHash(tokenDigest(rawRefreshToken))
                .ifPresent(s -> sessions.revokeFamily(s.getFamilyId()));
    }

    @Transactional(readOnly = true)
    public IntrospectionResponse introspect(String token) {
        try {
            Claims claims = jwtService.claims(token);
            String email = claims.getSubject();
            if (!jwtService.isTokenValid(token, email)) {
                return IntrospectionResponse.inactive();
            }
            AppUserEntity user = users.findByEmailIgnoreCase(email).orElse(null);
            if (user == null || user.isDisabled()) {
                return IntrospectionResponse.inactive();
            }
            AuthResponse auth = responseFor(user, token);
            return new IntrospectionResponse(true, auth);
        } catch (RuntimeException ex) {
            return IntrospectionResponse.inactive();
        }
    }

    private LoginResult issueSession(AppUserEntity user, String familyId) {
        AuthenticatedUserSnapshot snapshot = snapshot(user);
        String accessToken = jwtService.generateAccessToken(snapshot);
        String refreshToken = jwtService.generateRefreshToken(snapshot);
        AuthSessionEntity session = new AuthSessionEntity();
        session.setId(UUID.randomUUID().toString());
        session.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        session.setFamilyId(familyId);
        session.setStatus(AuthSessionEntity.ACTIVE);
        session.setUser(user);
        session.setAccessTokenHash(tokenDigest(accessToken));
        session.setRefreshTokenHash(tokenDigest(refreshToken));
        session.setExpiresAt(OffsetDateTime.ofInstant(jwtService.extractExpiration(refreshToken).toInstant(), ZoneOffset.UTC));
        sessions.save(session);
        return new LoginResult(refreshToken, responseFor(user, accessToken));
    }

    private AuthResponse responseFor(AppUserEntity user, String accessToken) {
        List<String> roles = rbac.roleNames(user.getId());
        List<String> permissions = new ArrayList<>(rbac.permissionCodes(user.getId()));
        permissions.sort(String::compareTo);
        return new AuthResponse(
                accessToken,
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole(),
                user.getBranchId(),
                user.getBranchName(),
                user.getZoneId(),
                user.getZoneName(),
                roles,
                permissions);
    }

    private AuthenticatedUserSnapshot snapshot(AppUserEntity user) {
        return new AuthenticatedUserSnapshot(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole(),
                user.getBranchId(),
                user.getBranchName(),
                user.getZoneId(),
                user.getZoneName());
    }

    private ResponseStatusException unauthorized(String message) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
    }

    private String tokenDigest(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

    public record LoginRequest(String email, String password) {
    }

    public record LoginResult(String refreshToken, AuthResponse authResponse) {
    }

    public record AuthResponse(
            String accessToken,
            Long userId,
            String fullName,
            String email,
            String role,
            Long branchId,
            String branchName,
            Long zoneId,
            String zoneName,
            List<String> roles,
            List<String> permissions) {
    }

    public record IntrospectionResponse(boolean active, AuthResponse principal) {
        static IntrospectionResponse inactive() {
            return new IntrospectionResponse(false, null);
        }
    }
}
