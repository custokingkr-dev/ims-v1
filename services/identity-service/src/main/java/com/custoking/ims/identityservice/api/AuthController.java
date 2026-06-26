package com.custoking.ims.identityservice.api;

import com.custoking.ims.identityservice.application.IdentityAuthService;
import com.custoking.ims.identityservice.application.IdentityAuthService.AuthResponse;
import com.custoking.ims.identityservice.application.IdentityAuthService.IntrospectionResponse;
import com.custoking.ims.identityservice.application.IdentityAuthService.LoginRequest;
import com.custoking.ims.identityservice.application.IdentityAuthService.LoginResult;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String COOKIE_NAME = "refresh_token";

    private final IdentityAuthService authService;
    private final boolean cookieSecure;
    private final String cookieSameSite;
    private final Duration cookieMaxAge;
    private final String introspectionToken;

    public AuthController(
            IdentityAuthService authService,
            @Value("${app.cookie-secure:false}") boolean cookieSecure,
            @Value("${app.cookie-same-site:Strict}") String cookieSameSite,
            @Value("${app.refresh-token-expiration-ms:604800000}") long refreshTokenExpirationMs,
            @Value("${identity.introspection-token:}") String introspectionToken) {
        this.authService = authService;
        this.cookieSecure = cookieSecure;
        this.cookieSameSite = normalizeSameSite(cookieSameSite);
        this.cookieMaxAge = Duration.ofMillis(refreshTokenExpirationMs);
        this.introspectionToken = introspectionToken == null ? "" : introspectionToken.trim();
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        LoginResult result = authService.login(request);
        setRefreshCookie(response, result.refreshToken());
        return result.authResponse();
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(
            @CookieValue(name = COOKIE_NAME, required = false) String refreshToken,
            HttpServletResponse response) {
        if (!StringUtils.hasText(refreshToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No refresh token");
        }
        LoginResult result = authService.refresh(refreshToken);
        setRefreshCookie(response, result.refreshToken());
        return result.authResponse();
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            @CookieValue(name = COOKIE_NAME, required = false) String refreshToken,
            HttpServletResponse response) {
        authService.logout(refreshToken);
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("", Duration.ZERO).toString());
    }

    @PostMapping("/introspect")
    public IntrospectionResponse introspect(
            @RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @Valid @RequestBody IntrospectionRequest request) {
        requireToken(token, "identity:introspect");
        return authService.introspect(request.token());
    }

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing internal route scope");
        }
        if (!StringUtils.hasText(introspectionToken) || !introspectionToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid identity service token");
        }
    }

    private void setRefreshCookie(HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie(token, cookieMaxAge).toString());
    }

    private ResponseCookie buildCookie(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .sameSite(cookieSameSite)
                .secure(cookieSecure)
                .path("/api/v1/auth")
                .maxAge(maxAge)
                .build();
    }

    private String normalizeSameSite(String value) {
        if ("None".equalsIgnoreCase(value)) return "None";
        if ("Lax".equalsIgnoreCase(value)) return "Lax";
        return "Strict";
    }

    public record IntrospectionRequest(@NotBlank String token) {
    }
}

