package com.custoking.ims.auth.controller;

import com.custoking.ims.dto.AuthResponse;
import com.custoking.ims.dto.LoginRequest;
import com.custoking.ims.dto.LoginResult;
import com.custoking.ims.auth.service.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String COOKIE_NAME = "refresh_token";

    private final AuthService authService;
    private final boolean cookieSecure;
    private final String cookieSameSite;
    private final Duration cookieMaxAge;

    public AuthController(AuthService authService,
                          @Value("${app.cookie-secure:false}") boolean cookieSecure,
                          @Value("${app.cookie-same-site:Strict}") String cookieSameSite,
                          @Value("${app.refresh-token-expiration-ms:604800000}") long refreshTokenExpirationMs) {
        this.authService = authService;
        this.cookieSecure = cookieSecure;
        this.cookieSameSite = normalizeSameSite(cookieSameSite);
        this.cookieMaxAge = Duration.ofMillis(refreshTokenExpirationMs);
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
        if (refreshToken == null || refreshToken.isBlank()) {
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
}
