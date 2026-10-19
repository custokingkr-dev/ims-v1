package com.custoking.ims.identityservice.security;

import com.custoking.ims.identityservice.application.AuthenticatedUserSnapshot;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JwtServiceOperatorScopesTest {
    private final JwtService jwt = new JwtService("0123456789012345678901234567890123456789", 900000, 604800000);
    private final AuthenticatedUserSnapshot operator = new AuthenticatedUserSnapshot(7L, "Ann", "ann@x", "OPERATIONS", null, null, null, null);

    @Test
    void itemSpecificScopesTravelInTheirOwnClaimAndLeaveOpsSchoolsSchoolWideOnly() {
        Claims claims = jwt.claims(jwt.generateAccessToken(operator, List.of("workflow:read"), List.of(10L), List.of("4:NOTEBOOKS", "4:TIES")));
        assertEquals(List.of(10), claims.get("ops_schools", List.class));
        assertEquals(List.of("4:NOTEBOOKS", "4:TIES"), claims.get("ops_scopes", List.class));
    }

    @Test
    void theOlderOverloadsEmitAnEmptyScopesClaim() {
        assertEquals(List.of(), jwt.claims(jwt.generateAccessToken(operator, List.of("workflow:read"), List.of(10L))).get("ops_scopes", List.class));
        assertEquals(List.of(), jwt.claims(jwt.generateAccessToken(operator)).get("ops_scopes", List.class));
    }
}
