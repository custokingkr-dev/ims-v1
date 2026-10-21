package com.custoking.ims.operationsservice.security;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TenantContextScopesTest {

    private static TenantContext operator(Set<Long> schools, Set<String> scopes) {
        return new TenantContext(1L, "op@x", "OPERATIONS", null, null, Set.of(), schools, scopes);
    }

    @Test
    void schoolWideSchoolsAreAlwaysIncluded() {
        assertEquals(Set.of(2L, 3L), operator(Set.of(2L, 3L), Set.of()).operatorSchoolsFor("NOTEBOOKS"));
    }

    @Test
    void onlyScopesForTheRequestedItemAddSchools() {
        TenantContext ctx = operator(Set.of(2L), Set.of("4:NOTEBOOKS", "5:TIES", "6:NOTEBOOKS"));
        assertEquals(Set.of(2L, 4L, 6L), ctx.operatorSchoolsFor("NOTEBOOKS"));
        assertEquals(Set.of(2L, 5L), ctx.operatorSchoolsFor("TIES"));
        assertEquals(Set.of(2L), ctx.operatorSchoolsFor("FLIERS"));
    }

    @Test
    void anItemNameThatMerelyEndsTheSameWayOrAMalformedScopeGrantsNothing() {
        TenantContext ctx = operator(Set.of(), Set.of("4:XNOTEBOOKS", "4:NOTEBOOKS_2", "NOTEBOOKS", ":NOTEBOOKS", "abc:NOTEBOOKS", "4:notebooks"));
        assertEquals(Set.of(), ctx.operatorSchoolsFor("NOTEBOOKS"));
    }

    @Test
    void theContextIsImmutableAndDefaultsToNoScopes() {
        assertTrue(new TenantContext(1L, "a@x", "ADMIN", 10L, null).operatorScopes().isEmpty());
        assertTrue(new TenantContext(1L, "a@x", "ADMIN", 10L, null, Set.of("x"), Set.of(1L)).operatorScopes().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> operator(Set.of(1L), Set.of("1:NOTEBOOKS")).operatorScopes().add("2:NOTEBOOKS"));
    }
}
