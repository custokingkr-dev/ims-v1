package com.custoking.ims.integration;

import com.custoking.ims.AbstractIntegrationTest;
import com.custoking.ims.entity.AppUserEntity;
import com.custoking.ims.repo.AppUserRepository;
import com.custoking.ims.service.RbacService;
import com.custoking.ims.util.PasswordUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the RBAC system. Runs against a real PostgreSQL
 * container with Flyway migrations applied (V112 seeds roles + permissions).
 *
 * Key invariants verified:
 *  - SUPERADMIN has all permissions loaded from DB at login time
 *  - OPERATIONS cannot collect fees (no fee:collect)
 *  - ACCOUNTANT can collect fees but cannot manage schools
 *  - RbacController endpoints enforce role:read / role:update permissions
 *  - /rbac/permissions list is non-empty
 */
class RbacIntegrationTest extends AbstractIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Object>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    @Autowired TestRestTemplate rest;
    @Autowired AppUserRepository userRepo;
    @Autowired PasswordUtil passwordUtil;
    @Autowired RbacService rbacService;

    private static final String SUPERADMIN_EMAIL = "rbac-superadmin@test.com";
    private static final String OPERATIONS_EMAIL = "rbac-ops@test.com";
    private static final String ACCOUNTANT_EMAIL = "rbac-accountant@test.com";
    private static final String PASSWORD = "Test@1234";

    @BeforeEach
    void seedUsers() {
        seedUserWithRole(SUPERADMIN_EMAIL, "SUPERADMIN");
        seedUserWithRole(OPERATIONS_EMAIL, "OPERATIONS");
        seedUserWithRole(ACCOUNTANT_EMAIL, "ACCOUNTANT");
    }

    // ── Permission loading ────────────────────────────────────────────────────

    @Test
    void superadmin_hasAllCriticalPermissions_atLoginTime() {
        AppUserEntity user = userRepo.findByEmailIgnoreCase(SUPERADMIN_EMAIL).orElseThrow();
        List<String> perms = rbacService.getEffectivePermissions(user.getId());
        assertThat(perms).contains(
                "student:read", "student:create", "student:delete",
                "fee:collect", "fee:reverse",
                "school:read", "school:create", "school:suspend",
                "order:approve", "order:fulfill",
                "role:read", "role:create", "role:update", "permission:read",
                "user:manage"
        );
    }

    @Test
    void operations_doesNotHave_feeCollect() {
        AppUserEntity user = userRepo.findByEmailIgnoreCase(OPERATIONS_EMAIL).orElseThrow();
        List<String> perms = rbacService.getEffectivePermissions(user.getId());
        assertThat(perms).doesNotContain("fee:collect", "fee:reverse", "school:read", "user:manage");
        assertThat(perms).contains("student:read", "attendance:read", "order:read");
    }

    @Test
    void accountant_has_feeCollect_butNot_schoolManage() {
        AppUserEntity user = userRepo.findByEmailIgnoreCase(ACCOUNTANT_EMAIL).orElseThrow();
        List<String> perms = rbacService.getEffectivePermissions(user.getId());
        assertThat(perms).contains("fee:collect", "fee:read", "payment:create");
        assertThat(perms).doesNotContain("school:create", "school:update", "student:delete");
    }

    // ── RbacController endpoints ──────────────────────────────────────────────

    @Test
    void anonymous_cannotAccess_rbacEndpoints() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/api/v1/rbac/roles", HttpMethod.GET, null, MAP_TYPE);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void operations_cannotAccess_rbacRoles() {
        HttpHeaders headers = loginAndGetHeaders(OPERATIONS_EMAIL, PASSWORD);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/api/v1/rbac/roles", HttpMethod.GET, new HttpEntity<>(headers), MAP_TYPE);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void superadmin_canList_roles() {
        HttpHeaders headers = loginAndGetHeaders(SUPERADMIN_EMAIL, PASSWORD);
        ResponseEntity<List<Object>> resp = rest.exchange(
                "/api/v1/rbac/roles", HttpMethod.GET, new HttpEntity<>(headers), LIST_TYPE);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    void superadmin_canList_permissions() {
        HttpHeaders headers = loginAndGetHeaders(SUPERADMIN_EMAIL, PASSWORD);
        ResponseEntity<List<Object>> resp = rest.exchange(
                "/api/v1/rbac/permissions", HttpMethod.GET, new HttpEntity<>(headers), LIST_TYPE);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSizeGreaterThan(30);
    }

    @Test
    void superadmin_canGet_userPermissions() {
        AppUserEntity opsUser = userRepo.findByEmailIgnoreCase(OPERATIONS_EMAIL).orElseThrow();
        HttpHeaders headers = loginAndGetHeaders(SUPERADMIN_EMAIL, PASSWORD);
        ResponseEntity<List<Object>> resp = rest.exchange(
                "/api/v1/rbac/users/" + opsUser.getId() + "/permissions",
                HttpMethod.GET, new HttpEntity<>(headers), LIST_TYPE);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void seedUserWithRole(String email, String role) {
        if (userRepo.findByEmailIgnoreCase(email).isPresent()) return;
        AppUserEntity u = new AppUserEntity();
        u.setFullName(role + " Test User");
        u.setEmail(email);
        u.setRole(role);
        u.setPasswordHash(passwordUtil.hash(PASSWORD));
        userRepo.save(u);
        rbacService.assignRole(u.getId(), role, null);
    }

    private HttpHeaders loginAndGetHeaders(String email, String password) {
        Map<String, String> body = Map.of("email", email, "password", password);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()), MAP_TYPE);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) resp.getBody().get("accessToken");
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
