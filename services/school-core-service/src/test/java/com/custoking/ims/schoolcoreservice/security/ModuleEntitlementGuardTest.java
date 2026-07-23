package com.custoking.ims.schoolcoreservice.security;

import com.custoking.ims.schoolcoreservice.persistence.ModuleEntitlementReadRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ModuleEntitlementGuardTest {

    private final ModuleEntitlementReadRepository modules = mock(ModuleEntitlementReadRepository.class);
    private final ModuleEntitlementGuard guard = new ModuleEntitlementGuard(modules);

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void operationsWithoutHomeSchoolCanUseOrdersWhenAssignedToAnySchool() {
        TenantContext.set(new TenantContext(4L, "op@x", "OPERATIONS", null, null, Set.of(10L, 20L)));

        assertDoesNotThrow(() -> guard.requireModuleEnabled(null, "ORDERS"));
    }

    @Test
    void operationsWithoutAssignedSchoolsIsForbidden() {
        TenantContext.set(new TenantContext(4L, "op@x", "OPERATIONS", null, null, Set.of()));

        assertThrows(ResponseStatusException.class, () -> guard.requireModuleEnabled(null, "ORDERS"));
    }
}
