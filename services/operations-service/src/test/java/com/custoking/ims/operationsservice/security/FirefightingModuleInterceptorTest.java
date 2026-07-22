package com.custoking.ims.operationsservice.security;

import com.custoking.ims.operationsservice.infrastructure.ModuleEntitlementClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FirefightingModuleInterceptorTest {

    private final ModuleEntitlementClient client = mock(ModuleEntitlementClient.class);
    private final FirefightingModuleInterceptor interceptor = new FirefightingModuleInterceptor(client);

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void nonSuperAdminSchoolWithoutFirefightingIsForbidden() {
        TenantContext.set(new TenantContext(1L, "a@b.com", "ADMIN", 10L, null));
        when(client.activeModules(10L)).thenReturn(Set.of("STUDENTS"));

        assertThatThrownBy(() -> interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void nonSuperAdminSchoolWithFirefightingIsAllowed() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@b.com", "ADMIN", 10L, null));
        when(client.activeModules(10L)).thenReturn(Set.of("FIREFIGHTING"));

        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object());

        assertThat(allowed).isTrue();
    }

    @Test
    void superAdminIsAlwaysAllowedAndClientNotConsulted() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@b.com", "SUPERADMIN", 10L, null));

        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object());

        assertThat(allowed).isTrue();
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void nullSchoolIdIsAllowed() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@b.com", "ADMIN", null, null));

        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object());

        assertThat(allowed).isTrue();
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void clientThrowingFailsClosed() {
        TenantContext.set(new TenantContext(1L, "a@b.com", "ADMIN", 10L, null));
        when(client.activeModules(10L)).thenThrow(new RuntimeException("lookup failed"));

        assertThatThrownBy(() -> interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(503));
    }
}
