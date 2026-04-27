package com.custoking.ims.service;

import com.custoking.ims.audit.AuditLogService;
import com.custoking.ims.dto.AuthResponse;
import com.custoking.ims.dto.LoginRequest;
import com.custoking.ims.dto.LoginResult;
import com.custoking.ims.entity.AppUserEntity;
import com.custoking.ims.repo.AppUserRepository;
import com.custoking.ims.security.AppUserDetails;
import com.custoking.ims.security.AppUserDetailsService;
import com.custoking.ims.security.JwtService;
import com.custoking.ims.util.PasswordUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

    private final AppUserRepository userRepository;
    private final JwtService jwtService;
    private final AppUserDetailsService userDetailsService;
    private final PasswordUtil passwordUtil;
    private final AuditLogService auditLogService;

    public AuthService(AppUserRepository userRepository,
                       JwtService jwtService,
                       AppUserDetailsService userDetailsService,
                       PasswordUtil passwordUtil,
                       AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
        this.passwordUtil = passwordUtil;
        this.auditLogService = auditLogService;
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
        AppUserDetails details = new AppUserDetails(user);
        return new LoginResult(
                jwtService.generateRefreshToken(details),
                new AuthResponse(
                        jwtService.generateToken(details),
                        user.getId(), user.getFullName(), user.getEmail(),
                        user.getRole(), user.getBranchId(), user.getBranchName()));
    }

    public LoginResult refresh(String rawRefreshToken) {
        String email;
        try {
            email = jwtService.extractUsername(rawRefreshToken);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }
        if (!jwtService.isTokenValid(rawRefreshToken, email)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }
        AppUserDetails details = (AppUserDetails) userDetailsService.loadUserByUsername(email);
        AppUserEntity user = details.getUser();
        return new LoginResult(
                jwtService.generateRefreshToken(details),
                new AuthResponse(
                        jwtService.generateToken(details),
                        user.getId(), user.getFullName(), user.getEmail(),
                        user.getRole(), user.getBranchId(), user.getBranchName()));
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
