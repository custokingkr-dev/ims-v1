package com.custoking.ims.schoolcoreservice.security;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TenantContextScopesTest {

    private static TenantContext operator(Set<Long> schools, Set<String> scopes) {
        return new TenantContext(1L, "op@x", "OPERATIONS", null, null, schools, Set.of(), scopes);
    }

    @Test
    void anOperatorOperatesAnItemWhenSchoolWideOrScopedToExactlyThatItem() {
        TenantContext ctx = operator(Set.of(2L), Set.of("4:NOTEBOOKS"));
        assertTrue(ctx.operatesItem(2L, "ANYTHING"));
        assertTrue(ctx.operatesItem(4L, "NOTEBOOKS"));
        assertFalse(ctx.operatesItem(4L, "TIES"));
        assertFalse(ctx.operatesItem(5L, "NOTEBOOKS"));
        assertFalse(ctx.operatesItem(null, "NOTEBOOKS"));
        assertFalse(ctx.operatesItem(4L, null));
    }

    @Test
    void scopedSchoolsListsSchoolsOfAnyItemAndSkipsMalformedScopes() {
        assertEquals(Set.of(4L, 6L), operator(Set.of(), Set.of("4:NOTEBOOKS", "6:TIES", "bogus", ":X", "x:Y")).scopedSchools());
        assertTrue(operator(Set.of(2L), Set.of()).scopedSchools().isEmpty());
    }

    @Test
    void theCatalogOrderReadScopeAdmitsAScopedOperatorOnlyForItsItem() {
        TenantContext.set(operator(Set.of(), Set.of("4:NOTEBOOKS")));
        try {
            assertDoesNotThrow(() -> TenantScope.requireCatalogOrderReadScope(4L, "NOTEBOOKS"));
            assertThrows(ResponseStatusException.class, () -> TenantScope.requireCatalogOrderReadScope(4L, "TIES"));
            assertThrows(ResponseStatusException.class, () -> TenantScope.requireCatalogOrderReadScope(5L, "NOTEBOOKS"));
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void theItemScopeNeverWidensThePlatformScopeThatGuardsStudentsAndFees() {
        TenantContext.set(operator(Set.of(), Set.of("4:NOTEBOOKS")));
        try {
            assertThrows(ResponseStatusException.class, () -> TenantScope.resolvePlatformReadScope(4L));
            assertThrows(ResponseStatusException.class, () -> TenantScope.resolveOperationsWriteScope(4L));
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void superadminAndSchoolUsersAreUnaffected() {
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        try {
            assertDoesNotThrow(() -> TenantScope.requireCatalogOrderReadScope(4L, "NOTEBOOKS"));
        } finally {
            TenantContext.clear();
        }
        TenantContext.set(new TenantContext(2L, "a@x", "ADMIN", 4L, null));
        try {
            assertDoesNotThrow(() -> TenantScope.requireCatalogOrderReadScope(4L, "NOTEBOOKS"));
            assertThrows(ResponseStatusException.class, () -> TenantScope.requireCatalogOrderReadScope(5L, "NOTEBOOKS"));
        } finally {
            TenantContext.clear();
        }
    }
}
