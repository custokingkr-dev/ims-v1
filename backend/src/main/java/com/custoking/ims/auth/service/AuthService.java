package com.custoking.ims.auth.service;

import com.custoking.ims.audit.AuditLogService;
import com.custoking.ims.dto.AuthResponse;
import com.custoking.ims.dto.LoginRequest;
import com.custoking.ims.dto.LoginResult;
import com.custoking.ims.entity.AppUserEntity;
import com.custoking.ims.entity.AuthSessionEntity;
import com.custoking.ims.repo.AppUserRepository;
import com.custoking.ims.repo.AuthSessionRepository;
import com.custoking.ims.security.AppUserDetails;
import com.custoking.ims.security.AppUserDetailsService;
import com.custoking.ims.security.JwtService;
import com.custoking.ims.service.RbacService;
import com.custoking.ims.util.PasswordUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
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
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AppUserRepository userRepository;
    private final JwtService jwtService;
    private final AppUserDetailsService userDetailsService;
    private final PasswordUtil passwordUtil;
    private final AuditLogService auditLogService;
    private final RbacService rbacService;
    private final AuthSessionRepository authSessionRepository;

    public AuthService(AppUserRepository userRepository,
                       JwtService jwtService,
                       AppUserDetailsService userDetailsService,
                       PasswordUtil passwordUtil,
                       AuditLogService auditLogService,
                       RbacService rbacService,
                       AuthSessionRepository authSessionRepository) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
        this.passwordUtil = passwordUtil;
        this.auditLogService = auditLogService;
        this.rbacService = rbacService;
        this.authSessionRepository = authSessionRepository;
    }

    public LoginResult login(LoginRequest request) {
        String ip = resolveClientIp();
        AppUserEntity user = userRepository.findByEmailIgnoreCase(request.email())
                .orElseGet(() -> {
                    auditLogService.loginFailure(request.email(), ip);
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
                });
        if (!passwordUtil.verify(request.password(), user.getPasswordHash())) {
            auditLogService.loginFailure(request.email(), ip);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
        auditLogService.loginSuccess(user.getId(), user.getEmail(), ip);
        log.info("user.login userId={} email={} ip={}", user.getId(), user.getEmail(), ip);
        AppUserDetails details = new AppUserDetails(user);
        log.info("user.login.success userId={} role={}", user.getId(), user.getRole());
        expireOldSessions();
        return issueSession(user, details, null);
    }

    public LoginResult refresh(String rawRefreshToken) {
        String email;
        try {
            email = jwtService.extractUsername(rawRefreshToken);
            if (!jwtService.isRefreshToken(rawRefreshToken)) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }
        try {
            if (!jwtService.isTokenValid(rawRefreshToken, email)) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }
        AuthSessionEntity session = authSessionRepository.findByRefreshTokenHash(tokenDigest(rawRefreshToken))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token was already used or revoked"));
        if (session.getExpiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            authSessionRepository.delete(session);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token expired");
        }
        AppUserDetails details = (AppUserDetails) userDetailsService.loadUserByUsername(email);
        AppUserEntity user = details.getUser();
        if (!Objects.equals(session.getUser().getId(), user.getId())) {
            authSessionRepository.delete(session);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }
        return issueSession(user, details, session);
    }

    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        authSessionRepository.findByRefreshTokenHash(tokenDigest(rawRefreshToken))
                .ifPresent(authSessionRepository::delete);
    }

    private LoginResult issueSession(AppUserEntity user, AppUserDetails details, AuthSessionEntity existingSession) {
        String accessToken = jwtService.generateToken(details);
        String refreshToken = jwtService.generateRefreshToken(details);
        List<String> roleNames = rbacService.getUserRoleNames(user.getId());
        List<String> permissions = new ArrayList<>(rbacService.getUserPermissions(user.getId()));
        AuthSessionEntity session = existingSession == null ? new AuthSessionEntity() : existingSession;
        if (session.getId() == null) {
            session.setId(UUID.randomUUID().toString());
            session.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        }
        session.setUser(user);
        session.setAccessTokenHash(tokenDigest(accessToken));
        session.setRefreshTokenHash(tokenDigest(refreshToken));
        session.setExpiresAt(OffsetDateTime.ofInstant(jwtService.extractExpiration(refreshToken).toInstant(), ZoneOffset.UTC));
        authSessionRepository.save(session);
        return new LoginResult(
                refreshToken,
                new AuthResponse(
                        accessToken,
                        user.getId(), user.getFullName(), user.getEmail(),
                        user.getRole(), user.getBranchId(), user.getBranchName(),
                        user.getZoneId(), user.getZoneName(),
                        roleNames, permissions));
    }

    private void expireOldSessions() {
        authSessionRepository.deleteByExpiresAtBefore(OffsetDateTime.now(ZoneOffset.UTC));
    }

    private String tokenDigest(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

    private String resolveClientIp() {
        try {
            var attrs = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            HttpServletRequest req = attrs.getRequest();
            String forwarded = req.getHeader("X-Forwarded-For");
            return forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : req.getRemoteAddr();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
