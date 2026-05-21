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
import java.util.ArrayList;
import java.util.List;
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
    private final AuthSessionRepository sessionRepository;

    public AuthService(AppUserRepository userRepository,
                       JwtService jwtService,
                       AppUserDetailsService userDetailsService,
                       PasswordUtil passwordUtil,
                       AuditLogService auditLogService,
                       RbacService rbacService,
                       AuthSessionRepository sessionRepository) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
        this.passwordUtil = passwordUtil;
        this.auditLogService = auditLogService;
        this.rbacService = rbacService;
        this.sessionRepository = sessionRepository;
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
        if (user.isDisabled()) {
            auditLogService.loginFailure(request.email(), ip);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account is disabled");
        }
        auditLogService.loginSuccess(user.getId(), user.getEmail(), ip);
        log.info("user.login userId={} email={} ip={}", user.getId(), user.getEmail(), ip);

        AppUserDetails details = new AppUserDetails(user);
        String refreshToken = jwtService.generateRefreshToken(details);
        String accessToken = jwtService.generateToken(details);

        createSession(user, accessToken, refreshToken);

        List<String> roleNames = rbacService.getUserRoleNames(user.getId());
        List<String> permissions = new ArrayList<>(rbacService.getUserPermissions(user.getId()));
        log.info("user.login.success userId={} role={}", user.getId(), user.getRole());
        return new LoginResult(
                refreshToken,
                new AuthResponse(
                        accessToken,
                        user.getId(), user.getFullName(), user.getEmail(),
                        user.getRole(), user.getBranchId(), user.getBranchName(),
                        user.getZoneId(), user.getZoneName(),
                        roleNames, permissions));
    }

    public LoginResult refresh(String rawRefreshToken) {
        String refreshHash = sha256(rawRefreshToken);
        AuthSessionEntity session = sessionRepository.findByRefreshToken(refreshHash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));

        if (session.getExpiresAt().isBefore(OffsetDateTime.now())) {
            sessionRepository.delete(session);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token expired");
        }

        String email;
        try {
            email = jwtService.extractUsername(rawRefreshToken);
        } catch (Exception e) {
            sessionRepository.delete(session);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }
        if (!jwtService.isTokenValid(rawRefreshToken, email)) {
            sessionRepository.delete(session);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }

        // Rotate: delete old session, issue new tokens, create new session.
        sessionRepository.delete(session);
        AppUserDetails details = (AppUserDetails) userDetailsService.loadUserByUsername(email);
        AppUserEntity user = details.getUser();

        String newRefreshToken = jwtService.generateRefreshToken(details);
        String newAccessToken = jwtService.generateToken(details);
        createSession(user, newAccessToken, newRefreshToken);

        List<String> roleNames = rbacService.getUserRoleNames(user.getId());
        List<String> permissions = new ArrayList<>(rbacService.getUserPermissions(user.getId()));
        return new LoginResult(
                newRefreshToken,
                new AuthResponse(
                        newAccessToken,
                        user.getId(), user.getFullName(), user.getEmail(),
                        user.getRole(), user.getBranchId(), user.getBranchName(),
                        user.getZoneId(), user.getZoneName(),
                        roleNames, permissions));
    }

    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) return;
        String refreshHash = sha256(rawRefreshToken);
        sessionRepository.findByRefreshToken(refreshHash).ifPresent(sessionRepository::delete);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void createSession(AppUserEntity user, String accessToken, String refreshToken) {
        AuthSessionEntity session = new AuthSessionEntity();
        session.setId(UUID.randomUUID().toString());
        session.setUser(user);
        session.setAccessToken(sha256(accessToken));
        session.setRefreshToken(sha256(refreshToken));
        session.setCreatedAt(OffsetDateTime.now());
        session.setExpiresAt(OffsetDateTime.now().plusDays(7));
        sessionRepository.save(session);
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

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
