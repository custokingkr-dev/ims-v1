package com.custoking.ims.identityservice.security;

import com.custoking.ims.identityservice.application.AuthenticatedUserSnapshot;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;
    private final SecretKey previousKey;
    private final String issuer;
    private final String accessAudience;
    private final long expirationMs;
    private final long refreshExpirationMs;

    public JwtService(
            String secret, long expirationMs, long refreshExpirationMs) {
        this(secret, expirationMs, refreshExpirationMs, "", "ims-identity", "ims-api");
    }

    @Autowired
    public JwtService(
            @Value("${app.jwt-secret}") String secret,
            @Value("${app.jwt-expiration-ms:900000}") long expirationMs,
            @Value("${app.refresh-token-expiration-ms:604800000}") long refreshExpirationMs,
            @Value("${app.jwt-previous-secret:}") String previousSecret,
            @Value("${app.jwt-issuer:ims-identity}") String issuer,
            @Value("${app.jwt-audience:ims-api}") String accessAudience) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("APP_JWT_SECRET must be at least 32 characters");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        if (previousSecret != null && !previousSecret.isBlank() && previousSecret.length() < 32) {
            throw new IllegalStateException("Previous JWT key must be at least 32 characters");
        }
        this.previousKey = previousSecret == null || previousSecret.isBlank() ? null
                : Keys.hmacShaKeyFor(previousSecret.getBytes(StandardCharsets.UTF_8));
        if (issuer == null || issuer.isBlank() || accessAudience == null || accessAudience.isBlank()) {
            throw new IllegalStateException("JWT issuer and audience are required");
        }
        this.issuer = issuer;
        this.accessAudience = accessAudience;
        this.expirationMs = expirationMs;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    public String generateAccessToken(AuthenticatedUserSnapshot user) {
        return generateAccessToken(user, java.util.List.of());
    }

    public String generateAccessToken(AuthenticatedUserSnapshot user, java.util.List<String> permissions) {
        return generateAccessToken(user, permissions, java.util.List.of());
    }

    public String generateAccessToken(AuthenticatedUserSnapshot user, java.util.List<String> permissions,
                                       java.util.List<Long> opsSchools) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", user.role());
        claims.put("type", "access");
        claims.put("uid", user.id());
        if (user.branchId() != null) {
            claims.put("sid", user.branchId());
        }
        if (user.zoneId() != null) {
            claims.put("zid", user.zoneId());
        }
        claims.put("perms", permissions == null ? java.util.List.of() : permissions);
        claims.put("ver", 3);
        claims.put("ops_schools", opsSchools == null ? java.util.List.of() : opsSchools);
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
        io.jsonwebtoken.Jws<Claims> parsed;
        try { parsed = parse(token, key); }
        catch (io.jsonwebtoken.security.SignatureException ex) {
            if (previousKey == null) throw ex;
            parsed = parse(token, previousKey);
        }
        if (!"HS256".equals(parsed.getHeader().getAlgorithm())) throw new JwtException("Unsupported JWT algorithm");
        Claims claims = parsed.getPayload();
        String purpose = claims.get("type", String.class);
        String audience = "access".equals(purpose) ? accessAudience
                : "refresh".equals(purpose) ? "ims-identity-refresh" : null;
        if (audience == null || claims.getAudience() == null || !claims.getAudience().equals(java.util.Set.of(audience))) {
            throw new JwtException("Invalid JWT purpose or audience");
        }
        if (claims.getSubject() == null || claims.getId() == null || claims.getIssuedAt() == null
                || claims.getExpiration() == null) throw new JwtException("Missing JWT lifecycle claims");
        return claims;
    }

    private io.jsonwebtoken.Jws<Claims> parse(String token, SecretKey verificationKey) {
        return Jwts.parser().verifyWith(verificationKey).requireIssuer(issuer).build().parseSignedClaims(token);
    }

    private String token(String subject, Map<String, Object> claims, long ttlMs) {
        return Jwts.builder()
                .claims(claims)
                .id(UUID.randomUUID().toString())
                .subject(subject)
                .issuer(issuer)
                .audience().add("refresh".equals(claims.get("type")) ? "ims-identity-refresh" : accessAudience).and()
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ttlMs))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}
