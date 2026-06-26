package com.custoking.ims.identityservice.security;

import com.custoking.ims.identityservice.application.AuthenticatedUserSnapshot;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMs;
    private final long refreshExpirationMs;

    public JwtService(
            @Value("${app.jwt-secret}") String secret,
            @Value("${app.jwt-expiration-ms:900000}") long expirationMs,
            @Value("${app.refresh-token-expiration-ms:604800000}") long refreshExpirationMs) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("APP_JWT_SECRET must be at least 32 characters");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    public String generateAccessToken(AuthenticatedUserSnapshot user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", user.role());
        return token(user.email(), claims, expirationMs);
    }

    public String generateRefreshToken(AuthenticatedUserSnapshot user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", user.role());
        claims.put("type", "refresh");
        return token(user.email(), claims, refreshExpirationMs);
    }

    public String extractUsername(String token) {
        return claims(token).getSubject();
    }

    public boolean isTokenValid(String token, String username) {
        return extractUsername(token).equals(username) && claims(token).getExpiration().after(new Date());
    }

    public boolean isRefreshToken(String token) {
        return "refresh".equals(claims(token).get("type"));
    }

    public Date extractExpiration(String token) {
        return claims(token).getExpiration();
    }

    public Claims claims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private String token(String subject, Map<String, Object> claims, long ttlMs) {
        return Jwts.builder()
                .claims(claims)
                .id(UUID.randomUUID().toString())
                .subject(subject)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ttlMs))
                .signWith(key)
                .compact();
    }
}
