package com.custoking.ims.identityservice.security;

import com.custoking.ims.identityservice.application.AuthenticatedUserSnapshot;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JwtServiceTest {

    private static final String SECRET = "test-jwt-secret-at-least-32-characters-long";
    private final JwtService jwtService = new JwtService(SECRET, 900_000L, 604_800_000L);

    private AuthenticatedUserSnapshot user(Long id, String role, Long branchId, Long zoneId) {
        return new AuthenticatedUserSnapshot(id, "Full Name", "user@example.com", role, branchId, "Branch", zoneId, "Zone");
    }

    @Test
    void accessTokenCarriesEnrichmentClaims() {
        String token = jwtService.generateAccessToken(user(42L, "ADMIN", 7L, 3L));
        Claims claims = jwtService.claims(token);

        assertEquals("user@example.com", claims.getSubject());
        assertEquals("ADMIN", claims.get("role"));
        assertEquals(42L, ((Number) claims.get("uid")).longValue());
        assertEquals(7L, ((Number) claims.get("sid")).longValue());
        assertEquals(3L, ((Number) claims.get("zid")).longValue());
        assertEquals(3, ((Number) claims.get("ver")).intValue());
    }

    @Test
    void superadminTokenOmitsSchoolAndZoneButKeepsVersion() {
        String token = jwtService.generateAccessToken(user(1L, "SUPERADMIN", null, null));
        Claims claims = jwtService.claims(token);

        assertEquals(3, ((Number) claims.get("ver")).intValue());
        assertEquals(1L, ((Number) claims.get("uid")).longValue());
        assertNull(claims.get("sid"));
        assertNull(claims.get("zid"));
    }

    @Test
    void accessTokenCarriesPermissionsAndVer3() {
        JwtService jwt = new JwtService("0123456789012345678901234567890123456789", 900000, 604800000);
        AuthenticatedUserSnapshot user = new AuthenticatedUserSnapshot(
                7L, "Ann", "ann@x", "ADMIN", 10L, "Br", null, null);
        String token = jwt.generateAccessToken(user, java.util.List.of("firefighting:approve", "firefighting:read"));
        Claims claims = jwt.claims(token);
        assertEquals(3, ((Number) claims.get("ver")).intValue());
        assertEquals(java.util.List.of("firefighting:approve", "firefighting:read"),
                claims.get("perms", java.util.List.class));
        assertEquals(7, ((Number) claims.get("uid")).intValue());
    }

    @Test
    void accessTokenCarriesOpsSchoolsClaimForOperator() {
        JwtService jwt = new JwtService("0123456789012345678901234567890123456789", 900000, 604800000);
        AuthenticatedUserSnapshot user = new AuthenticatedUserSnapshot(
                7L, "Ann", "ann@x", "OPERATIONS", null, null, null, null);
        String token = jwt.generateAccessToken(user, java.util.List.of("firefighting:read"), java.util.List.of(10L, 20L));
        Claims claims = jwt.claims(token);
        assertEquals(3, ((Number) claims.get("ver")).intValue());
        assertEquals(java.util.List.of(10, 20), claims.get("ops_schools", java.util.List.class));
    }

    @Test
    void accessTokenTwoArgOverloadDelegatesWithEmptyOpsSchools() {
        JwtService jwt = new JwtService("0123456789012345678901234567890123456789", 900000, 604800000);
        AuthenticatedUserSnapshot user = new AuthenticatedUserSnapshot(
                7L, "Ann", "ann@x", "ADMIN", 10L, "Br", null, null);
        String token = jwt.generateAccessToken(user, java.util.List.of("firefighting:approve"));
        Claims claims = jwt.claims(token);
        assertEquals(java.util.List.of(), claims.get("ops_schools", java.util.List.class));
    }

    @Test
    void refreshTokenIsUnchanged() {
        String token = jwtService.generateRefreshToken(user(42L, "ADMIN", 7L, 3L));
        Claims claims = jwtService.claims(token);

        assertEquals("refresh", claims.get("type"));
        assertNull(claims.get("uid"));
        assertNull(claims.get("sid"));
        assertNull(claims.get("ver"));
    }

    @Test
    void rejectsWrongPurposeIssuerAudienceAndAlgorithmAndSupportsExplicitRotation() {
        var user=user(42L,"ADMIN",7L,3L);
        String previous="old-signing-key-at-least-32-characters-AAAA";
        var rotated=new JwtService(SECRET,900000,604800000,previous,"ims-identity","ims-api");
        var old=new JwtService(previous,900000,604800000);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(()->rotated.claims(old.generateAccessToken(user)));
        org.junit.jupiter.api.Assertions.assertThrows(io.jsonwebtoken.JwtException.class,()->jwtService.claims(old.generateAccessToken(user)));
        for(String[] values:new String[][]{{"wrong","ims-api","access"},{"ims-identity","wrong","access"},{"ims-identity","ims-api","recovery"}}) {
            String token=io.jsonwebtoken.Jwts.builder().issuer(values[0]).audience().add(values[1]).and().claim("type",values[2])
                    .subject("user@example.com").id("id").issuedAt(new java.util.Date()).expiration(new java.util.Date(System.currentTimeMillis()+60000))
                    .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)),io.jsonwebtoken.Jwts.SIG.HS256).compact();
            org.junit.jupiter.api.Assertions.assertThrows(io.jsonwebtoken.JwtException.class,()->jwtService.claims(token));
        }
        String longSecret="A".repeat(80);
        var service=new JwtService(longSecret,900000,604800000);
        String token=io.jsonwebtoken.Jwts.builder().issuer("ims-identity").audience().add("ims-api").and().claim("type","access")
                .subject("user@example.com").id("id").issuedAt(new java.util.Date()).expiration(new java.util.Date(System.currentTimeMillis()+60000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(longSecret.getBytes()),io.jsonwebtoken.Jwts.SIG.HS512).compact();
        org.junit.jupiter.api.Assertions.assertThrows(io.jsonwebtoken.JwtException.class,()->service.claims(token));
    }
}
