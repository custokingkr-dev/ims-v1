package com.custoking.ims.identityservice.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Passkey step-up was removed: the filter only builds the tenant context and never blocks a request itself. */
class TenantContextFilterTest {

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void sensitiveSuperadminWritesReachTheControllerWithoutAnyStepUpEvidence() throws Exception {
        String[][] calls = {
                {"POST", "/api/v1/rbac/roles"},
                {"PUT", "/api/v1/rbac/roles/3/permissions"},
                {"POST", "/api/v1/rbac/assign-school-role"},
                {"DELETE", "/api/v1/users/9"},
                {"GET", "/api/v1/users/export"}};
        for (String[] call : calls) {
            var request = new MockHttpServletRequest(call[0], call[1]);
            request.addHeader("X-Authenticated-User-Id", "1");
            request.addHeader("X-Authenticated-Role", "SUPERADMIN");
            request.addHeader("X-Authenticated-Permissions", "USERS_READ, ROLES_WRITE");
            var response = new MockHttpServletResponse();
            AtomicReference<TenantContext> seen = new AtomicReference<>();

            new TenantContextFilter().doFilter(request, response, (req, res) -> seen.set(TenantContext.get()));

            assertThat(response.getStatus()).as(call[0] + " " + call[1]).isEqualTo(200);
            assertThat(response.getContentAsString()).doesNotContain("STEP_UP_REQUIRED");
            assertThat(seen.get()).as("chain invoked for " + call[0] + " " + call[1]).isNotNull();
            assertThat(seen.get().userId()).isEqualTo(1L);
            assertThat(seen.get().isSuperAdmin()).isTrue();
            assertThat(seen.get().permissions()).containsExactlyInAnyOrder("USERS_READ", "ROLES_WRITE");
            assertThat(TenantContext.get().isAuthenticated()).as("context cleared after the request").isFalse();
        }
    }
}
