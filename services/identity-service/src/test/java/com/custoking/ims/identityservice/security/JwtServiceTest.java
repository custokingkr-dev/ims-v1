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
        assertEquals(2, ((Number) claims.get("ver")).intValue());
    }

    @Test
    void superadminTokenOmitsSchoolAndZoneButKeepsVersion() {
        String token = jwtService.generateAccessToken(user(1L, "SUPERADMIN", null, null));
        Claims claims = jwtService.claims(token);

        assertEquals(2, ((Number) claims.get("ver")).intValue());
        assertEquals(1L, ((Number) claims.get("uid")).longValue());
        assertNull(claims.get("sid"));
        assertNull(claims.get("zid"));
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
}
