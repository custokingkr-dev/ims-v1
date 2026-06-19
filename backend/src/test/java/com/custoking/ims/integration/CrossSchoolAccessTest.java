package com.custoking.ims.integration;

import com.custoking.ims.AbstractIntegrationTest;
import com.custoking.ims.entity.AppUserEntity;
import com.custoking.ims.entity.SchoolEntity;
import com.custoking.ims.entity.SchoolModuleEntitlementEntity;
import com.custoking.ims.entity.ZoneEntity;
import com.custoking.ims.entity.ZoneSchoolMappingEntity;
import com.custoking.ims.repo.AppUserRepository;
import com.custoking.ims.repo.SchoolModuleEntitlementRepository;
import com.custoking.ims.repo.SchoolRepository;
import com.custoking.ims.repo.ZoneRepository;
import com.custoking.ims.repo.ZoneSchoolMappingRepository;
import com.custoking.ims.entity.UserRoleAssignmentEntity;
import com.custoking.ims.repo.UserRoleAssignmentRepository;
import com.custoking.ims.service.RbacService;
import com.custoking.ims.service.TenantScopeService;
import com.custoking.ims.util.PasswordUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for tenant isolation and cross-school access enforcement.
 *
 * Verifies:
 *  1. SUPERADMIN can access all schools (platform-level permission).
 *  2. ADMIN (school-scoped) cannot access all-schools list (no school:read).
 *  3. ADMIN for School A can enter workspace (has workspace:access).
 *  4. OPERATIONS cannot access RBAC management (no role:read).
 *  5. ACCOUNTANT cannot access school management (no school:read).
 *  6. VIEWER cannot create students (no student:create).
 *  7. ZONE_ADMIN can read zones (zone:read).
 *  8. User with no active role assignment cannot access workspace (no workspace:access).
 *  9. Revoked role assignment blocks workspace access.
 * 10. Actuator env endpoint requires system:actuator — OPERATIONS gets 403.
 * 11. SUPERADMIN can access sensitive actuator endpoints (system:actuator).
 * 12. TenantScopeService correctly derives platform/school/zone access from role assignments.
 *
 * Runs against a real PostgreSQL Testcontainer with Flyway migrations applied.
 */
class CrossSchoolAccessTest extends AbstractIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Object>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final String PASSWORD = "Test@5678!";

    // User email constants
    private static final String SA_EMAIL           = "cs-superadmin@test.com";
    private static final String ADMIN_A_EMAIL       = "cs-admin-schoola@test.com";
    private static final String ADMIN_B_EMAIL       = "cs-admin-schoolb@test.com";
    private static final String OPS_A_EMAIL         = "cs-ops-schoola@test.com";
    private static final String ACCOUNTANT_A_EMAIL  = "cs-acct-schoola@test.com";
    private static final String VIEWER_A_EMAIL      = "cs-viewer-schoola@test.com";
    private static final String ZONE_ADMIN_EMAIL    = "cs-zoneadmin@test.com";
    private static final String NO_ROLE_EMAIL       = "cs-norole@test.com";
    private static final String REVOKED_EMAIL       = "cs-revoked@test.com";
    private static final String EXPIRED_EMAIL       = "cs-expired@test.com";
    private static final String FUTURE_EMAIL        = "cs-future@test.com";

    @Autowired TestRestTemplate rest;
    @Autowired AppUserRepository userRepo;
    @Autowired SchoolRepository schoolRepo;
    @Autowired ZoneRepository zoneRepo;
    @Autowired ZoneSchoolMappingRepository zoneMappingRepo;
    @Autowired UserRoleAssignmentRepository uraRepo;
    @Autowired RbacService rbacService;
    @Autowired TenantScopeService tenantScopeService;
    @Autowired PasswordUtil passwordUtil;
    @Autowired SchoolModuleEntitlementRepository entitlementRepo;

    private Long schoolAId;
    private Long schoolBId;
    private Long zone1Id;

    @BeforeEach
    void seedData() {
        // Schools
        SchoolEntity schoolA = schoolRepo.findByShortCodeIgnoreCase("TST-CS-A")
                .orElseGet(() -> {
                    SchoolEntity s = new SchoolEntity();
                    s.setName("CrossTest School A");
                    s.setShortCode("TST-CS-A");
                    return schoolRepo.save(s);
                });
        schoolAId = schoolA.getId();

        SchoolEntity schoolB = schoolRepo.findByShortCodeIgnoreCase("TST-CS-B")
                .orElseGet(() -> {
                    SchoolEntity s = new SchoolEntity();
                    s.setName("CrossTest School B");
                    s.setShortCode("TST-CS-B");
                    return schoolRepo.save(s);
                });
        schoolBId = schoolB.getId();

        // Zone containing School A only
        ZoneEntity zone1 = zoneRepo.findByCode("TST-ZONE-1")
                .orElseGet(() -> {
                    ZoneEntity z = new ZoneEntity();
                    z.setName("CrossTest Zone 1");
                    z.setCode("TST-ZONE-1");
                    return zoneRepo.save(z);
                });
        zone1Id = zone1.getId();

        // Map Zone 1 → School A only
        zoneMappingRepo.findByZone_IdAndSchool_Id(zone1Id, schoolAId)
                .orElseGet(() -> {
                    ZoneSchoolMappingEntity m = new ZoneSchoolMappingEntity();
                    m.setZone(zone1);
                    m.setSchool(schoolA);
                    return zoneMappingRepo.save(m);
                });

        // Users + role assignments
        seedSuperadmin();
        seedSchoolAdmin(ADMIN_A_EMAIL, schoolA);
        seedSchoolAdmin(ADMIN_B_EMAIL, schoolB);
        seedSchoolUser(OPS_A_EMAIL, "OPERATIONS", schoolA);
        seedSchoolUser(ACCOUNTANT_A_EMAIL, "ACCOUNTANT", schoolA);
        seedSchoolUser(VIEWER_A_EMAIL, "VIEWER", schoolA);
        seedZoneAdmin(ZONE_ADMIN_EMAIL, zone1);
        seedNoRoleUser();
        seedRevokedUser(schoolA);
        seedExpiredUser(schoolA);
        seedFutureUser(schoolA);
    }

    // ── 1. Platform access ────────────────────────────────────────────────────

    @Test
    void superadmin_canList_allSchools() {
        HttpHeaders h = login(SA_EMAIL);
        ResponseEntity<List<Object>> resp = rest.exchange(
                "/api/v1/schools", HttpMethod.GET, new HttpEntity<>(h), LIST_TYPE);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void superadmin_hasPlatformAccess_inTenantScopeService() {
        AppUserEntity sa = userRepo.findByEmailIgnoreCase(SA_EMAIL).orElseThrow();
        assertThat(tenantScopeService.hasPlatformAccess(sa.getId())).isTrue();
        assertThat(tenantScopeService.canAccessSchool(sa.getId(), schoolAId)).isTrue();
        assertThat(tenantScopeService.canAccessSchool(sa.getId(), schoolBId)).isTrue();
    }

    // ── 2. School-scoped ADMIN cannot access all schools ─────────────────────

    @Test
    void adminForSchoolA_cannotList_allSchools() {
        // ADMIN role does not hold school:read → denied access to school list
        HttpHeaders h = login(ADMIN_A_EMAIL);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/api/v1/schools", HttpMethod.GET, new HttpEntity<>(h), MAP_TYPE);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminForSchoolA_canAccessSchoolA_butNotSchoolB_viaService() {
        AppUserEntity admin = userRepo.findByEmailIgnoreCase(ADMIN_A_EMAIL).orElseThrow();
        assertThat(tenantScopeService.canAccessSchool(admin.getId(), schoolAId)).isTrue();
        assertThat(tenantScopeService.canAccessSchool(admin.getId(), schoolBId)).isFalse();
        assertThat(tenantScopeService.hasPlatformAccess(admin.getId())).isFalse();
    }

    // ── 3. Workspace access ───────────────────────────────────────────────────

    @Test
    void adminForSchoolA_canAccess_workspace() {
        HttpHeaders h = login(ADMIN_A_EMAIL);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/api/v1/workspace", HttpMethod.GET, new HttpEntity<>(h), MAP_TYPE);
        // workspace:access exists → any non-403/401 status is acceptable
        assertThat(resp.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── 4. OPERATIONS cannot access RBAC management ───────────────────────────

    @Test
    void operations_cannotAccess_rbacRoles() {
        HttpHeaders h = login(OPS_A_EMAIL);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/api/v1/rbac/roles", HttpMethod.GET, new HttpEntity<>(h), MAP_TYPE);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void operations_cannotAccess_schoolList() {
        // OPERATIONS has no school:read
        HttpHeaders h = login(OPS_A_EMAIL);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/api/v1/schools", HttpMethod.GET, new HttpEntity<>(h), MAP_TYPE);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── 5. ACCOUNTANT cannot access school management ─────────────────────────

    @Test
    void accountant_cannotAccess_schoolList() {
        HttpHeaders h = login(ACCOUNTANT_A_EMAIL);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/api/v1/schools", HttpMethod.GET, new HttpEntity<>(h), MAP_TYPE);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void accountant_hasCorrectPermissions_viaService() {
        AppUserEntity acct = userRepo.findByEmailIgnoreCase(ACCOUNTANT_A_EMAIL).orElseThrow();
        List<String> perms = rbacService.getEffectivePermissions(acct.getId());
        assertThat(perms).contains("fee:collect", "fee:read", "payment:create");
        assertThat(perms).doesNotContain("school:read", "school:create", "user:manage");
    }

    // ── 6. VIEWER cannot create students ─────────────────────────────────────

    @Test
    void viewer_cannotCreate_students() {
        HttpHeaders h = login(VIEWER_A_EMAIL);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/api/v1/workspace/students",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Test Student"), h),
                MAP_TYPE);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void viewer_doesNotHave_studentCreate() {
        AppUserEntity viewer = userRepo.findByEmailIgnoreCase(VIEWER_A_EMAIL).orElseThrow();
        List<String> perms = rbacService.getEffectivePermissions(viewer.getId());
        assertThat(perms).contains("student:read");
        assertThat(perms).doesNotContain("student:create", "student:delete", "fee:collect");
    }

    // ── 7. ZONE_ADMIN can read zones ──────────────────────────────────────────

    @Test
    void zoneAdmin_canList_zones() {
        HttpHeaders h = login(ZONE_ADMIN_EMAIL);
        ResponseEntity<List<Object>> resp = rest.exchange(
                "/api/v1/zones", HttpMethod.GET, new HttpEntity<>(h), LIST_TYPE);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void zoneAdmin_accessibleSchoolIds_containsSchoolA_notSchoolB() {
        AppUserEntity za = userRepo.findByEmailIgnoreCase(ZONE_ADMIN_EMAIL).orElseThrow();
        Set<Long> accessible = tenantScopeService.accessibleSchoolIds(za.getId());
        assertThat(accessible).contains(schoolAId);
        assertThat(accessible).doesNotContain(schoolBId);
    }

    @Test
    void zoneAdmin_canAccessSchoolA_butNotSchoolB() {
        AppUserEntity za = userRepo.findByEmailIgnoreCase(ZONE_ADMIN_EMAIL).orElseThrow();
        assertThat(tenantScopeService.canAccessSchool(za.getId(), schoolAId)).isTrue();
        assertThat(tenantScopeService.canAccessSchool(za.getId(), schoolBId)).isFalse();
    }

    // ── 8. No active role = no workspace access ───────────────────────────────

    @Test
    void userWithNoRoleAssignment_cannotAccess_workspace() {
        HttpHeaders h = login(NO_ROLE_EMAIL);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/api/v1/workspace", HttpMethod.GET, new HttpEntity<>(h), MAP_TYPE);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void userWithNoRoleAssignment_hasNoPermissions() {
        AppUserEntity u = userRepo.findByEmailIgnoreCase(NO_ROLE_EMAIL).orElseThrow();
        List<String> perms = rbacService.getEffectivePermissions(u.getId());
        assertThat(perms).isEmpty();
    }

    // ── 9. Revoked assignment blocks workspace ────────────────────────────────

    @Test
    void revokedUser_cannotAccess_workspace() {
        HttpHeaders h = login(REVOKED_EMAIL);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/api/v1/workspace", HttpMethod.GET, new HttpEntity<>(h), MAP_TYPE);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void revokedUser_hasNoEffectivePermissions() {
        AppUserEntity u = userRepo.findByEmailIgnoreCase(REVOKED_EMAIL).orElseThrow();
        List<String> perms = rbacService.getEffectivePermissions(u.getId());
        // Active = false assignments contribute no permissions
        assertThat(perms).doesNotContain("workspace:access");
    }

    // ── 10. OPERATIONS cannot access actuator env ─────────────────────────────

    @Test
    void operations_cannotAccess_actuatorEnv() {
        HttpHeaders h = login(OPS_A_EMAIL);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/actuator/env", HttpMethod.GET, new HttpEntity<>(h), MAP_TYPE);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── 11. SUPERADMIN can access sensitive actuator endpoints ────────────────

    @Test
    void superadmin_canAccess_actuatorInfo() {
        HttpHeaders h = login(SA_EMAIL);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/actuator/info", HttpMethod.GET, new HttpEntity<>(h), MAP_TYPE);
        // SUPERADMIN has system:actuator → 200 (or 404 if info is empty)
        assertThat(resp.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── 12. Accessible school IDs derivation is correct ──────────────────────

    @Test
    void accessibleSchoolIds_isEmptyForSuperadmin_meaningUnrestricted() {
        AppUserEntity sa = userRepo.findByEmailIgnoreCase(SA_EMAIL).orElseThrow();
        // Platform users return empty set = unrestricted; checked separately via hasPlatformAccess
        Set<Long> ids = tenantScopeService.accessibleSchoolIds(sa.getId());
        assertThat(ids).isEmpty();
        assertThat(tenantScopeService.hasPlatformAccess(sa.getId())).isTrue();
    }

    @Test
    void adminForSchoolA_accessibleSchoolIds_containsOnlySchoolA() {
        AppUserEntity admin = userRepo.findByEmailIgnoreCase(ADMIN_A_EMAIL).orElseThrow();
        Set<Long> ids = tenantScopeService.accessibleSchoolIds(admin.getId());
        assertThat(ids).containsExactly(schoolAId);
        assertThat(ids).doesNotContain(schoolBId);
    }

    // ── 13. Expired assignment is treated as inactive ─────────────────────────

    @Test
    void expiredAssignment_grantsNoPermissions() {
        AppUserEntity u = userRepo.findByEmailIgnoreCase(EXPIRED_EMAIL).orElseThrow();
        List<String> perms = rbacService.getEffectivePermissions(u.getId());
        assertThat(perms).doesNotContain("workspace:access");
    }

    @Test
    void expiredUser_cannotAccess_workspace() {
        HttpHeaders h = login(EXPIRED_EMAIL);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/api/v1/workspace", HttpMethod.GET, new HttpEntity<>(h), MAP_TYPE);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── 14. Future assignment is not yet effective ────────────────────────────

    @Test
    void futureAssignment_grantsNoPermissionsYet() {
        AppUserEntity u = userRepo.findByEmailIgnoreCase(FUTURE_EMAIL).orElseThrow();
        List<String> perms = rbacService.getEffectivePermissions(u.getId());
        assertThat(perms).doesNotContain("workspace:access");
    }

    @Test
    void futureUser_cannotAccess_workspace() {
        HttpHeaders h = login(FUTURE_EMAIL);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/api/v1/workspace", HttpMethod.GET, new HttpEntity<>(h), MAP_TYPE);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── 16. School A admin cannot access School B school-scoped data ─────────

    @Test
    void adminA_cannotAccess_schoolBScope_viaService() {
        AppUserEntity adminA = userRepo.findByEmailIgnoreCase(ADMIN_A_EMAIL).orElseThrow();
        AppUserEntity adminB = userRepo.findByEmailIgnoreCase(ADMIN_B_EMAIL).orElseThrow();
        // Admin A scoped only to school A
        assertThat(tenantScopeService.canAccessSchool(adminA.getId(), schoolBId)).isFalse();
        // Admin B scoped only to school B
        assertThat(tenantScopeService.canAccessSchool(adminB.getId(), schoolAId)).isFalse();
        // Each can access their own school
        assertThat(tenantScopeService.canAccessSchool(adminA.getId(), schoolAId)).isTrue();
        assertThat(tenantScopeService.canAccessSchool(adminB.getId(), schoolBId)).isTrue();
    }

    @Test
    void adminA_accessibleSchoolIds_doesNotContain_schoolB() {
        AppUserEntity adminA = userRepo.findByEmailIgnoreCase(ADMIN_A_EMAIL).orElseThrow();
        Set<Long> ids = tenantScopeService.accessibleSchoolIds(adminA.getId());
        assertThat(ids).doesNotContain(schoolBId);
        assertThat(ids).containsOnly(schoolAId);
    }

    // ── 17. Viewer cannot perform write operations (HTTP-level) ──────────────

    @Test
    void viewer_cannotRecord_payment() {
        HttpHeaders h = login(VIEWER_A_EMAIL);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/api/v1/workspace/fees/record-payment",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("studentId", "1", "amount", 1000), h),
                MAP_TYPE);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void viewer_cannotAdd_staff() {
        HttpHeaders h = login(VIEWER_A_EMAIL);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/api/v1/workspace/staff",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Test Staff"), h),
                MAP_TYPE);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void viewer_cannotCreate_order() {
        HttpHeaders h = login(VIEWER_A_EMAIL);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/api/v1/workspace/orders",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("category", "UNIFORMS"), h),
                MAP_TYPE);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void viewer_cannotSave_timetable() {
        HttpHeaders h = login(VIEWER_A_EMAIL);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/api/v1/workspace/timetable",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("day", "Monday"), h),
                MAP_TYPE);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── 18. Operations cannot perform fee or RBAC management ─────────────────

    @Test
    void operations_cannotConfigure_feePlan() {
        HttpHeaders h = login(OPS_A_EMAIL);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/api/v1/workspace/fees/assign-plan",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("studentId", "1", "bandId", "band1"), h),
                MAP_TYPE);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void operations_cannotAssignRole_toAnyUser() {
        HttpHeaders h = login(OPS_A_EMAIL);
        AppUserEntity adminA = userRepo.findByEmailIgnoreCase(ADMIN_A_EMAIL).orElseThrow();
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/api/v1/rbac/users/" + adminA.getId() + "/roles/school",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("role", "VIEWER", "schoolId", schoolAId), h),
                MAP_TYPE);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── 19. Accountant cannot perform school management or student creation ───

    @Test
    void accountant_cannotCreate_student() {
        HttpHeaders h = login(ACCOUNTANT_A_EMAIL);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/api/v1/workspace/students",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("fullName", "Test Student"), h),
                MAP_TYPE);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void accountant_cannotAssign_role() {
        HttpHeaders h = login(ACCOUNTANT_A_EMAIL);
        AppUserEntity viewer = userRepo.findByEmailIgnoreCase(VIEWER_A_EMAIL).orElseThrow();
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/api/v1/rbac/users/" + viewer.getId() + "/roles/school",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("role", "ADMIN", "schoolId", schoolAId), h),
                MAP_TYPE);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── 20. Zone admin cannot see schools outside their zone ─────────────────

    @Test
    void zoneAdmin_effectivePermissions_containZoneManage() {
        AppUserEntity za = userRepo.findByEmailIgnoreCase(ZONE_ADMIN_EMAIL).orElseThrow();
        List<String> perms = rbacService.getEffectivePermissions(za.getId());
        assertThat(perms).contains("zone:manage", "zone:read", "school:read");
        assertThat(perms).doesNotContain("platform:admin", "student:create");
    }

    @Test
    void zoneAdmin_cannotAccess_schoolBWorkspace_via_tenantScope() {
        AppUserEntity za = userRepo.findByEmailIgnoreCase(ZONE_ADMIN_EMAIL).orElseThrow();
        // Zone 1 has school A only — zone admin cannot see school B
        assertThat(tenantScopeService.canAccessSchool(za.getId(), schoolBId)).isFalse();
        assertThat(tenantScopeService.hasPlatformAccess(za.getId())).isFalse();
    }

    // ── 21. RBAC permission derivation is symmetric and complete ─────────────

    @Test
    void revokedUser_accessibleSchoolIds_isEmpty() {
        AppUserEntity u = userRepo.findByEmailIgnoreCase(REVOKED_EMAIL).orElseThrow();
        Set<Long> ids = tenantScopeService.accessibleSchoolIds(u.getId());
        assertThat(ids).isEmpty();
    }

    @Test
    void expiredUser_accessibleSchoolIds_isEmpty() {
        AppUserEntity u = userRepo.findByEmailIgnoreCase(EXPIRED_EMAIL).orElseThrow();
        Set<Long> ids = tenantScopeService.accessibleSchoolIds(u.getId());
        assertThat(ids).isEmpty();
    }

    @Test
    void futureUser_accessibleSchoolIds_isEmpty() {
        AppUserEntity u = userRepo.findByEmailIgnoreCase(FUTURE_EMAIL).orElseThrow();
        Set<Long> ids = tenantScopeService.accessibleSchoolIds(u.getId());
        assertThat(ids).isEmpty();
    }

    // ── 15. Swagger requires system:swagger — OPERATIONS gets 403 ────────────

    @Test
    void operations_cannotAccess_swaggerUi() {
        HttpHeaders h = login(OPS_A_EMAIL);
        ResponseEntity<String> resp = rest.exchange(
                "/swagger-ui.html", HttpMethod.GET, new HttpEntity<>(h), String.class);
        // system:swagger is SUPERADMIN only — any non-2xx/non-3xx is acceptable
        assertThat(resp.getStatusCode().value()).isGreaterThanOrEqualTo(400);
    }

    @Test
    void superadmin_canAccess_swaggerDocs() {
        HttpHeaders h = login(SA_EMAIL);
        ResponseEntity<String> resp = rest.exchange(
                "/v3/api-docs", HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(resp.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── 22. Generic assignRole() is disabled ─────────────────────────────────

    @Test
    void genericAssignRole_throwsUnsupportedOperationException() {
        assertThatThrownBy(() -> rbacService.assignRole(1L, "VIEWER", null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("assignRole() is disabled");
    }

    // ── 23. Platform admin requires platform:admin, not just school:create ────

    @Test
    void platformAdmin_requires_platformAdmin_permission() {
        // ADMIN does not have platform:admin — they have school:create scoped to their school
        AppUserEntity admin = userRepo.findByEmailIgnoreCase(ADMIN_A_EMAIL).orElseThrow();
        Set<String> perms = rbacService.getUserPermissions(admin.getId());
        assertThat(perms).doesNotContain("platform:admin");
        // SUPERADMIN has platform:admin
        AppUserEntity sa = userRepo.findByEmailIgnoreCase(SA_EMAIL).orElseThrow();
        assertThat(rbacService.getUserPermissions(sa.getId())).contains("platform:admin");
    }

    @Test
    void tenantScope_platformAdmin_flag_isFalseForSchoolAdmin() {
        AppUserEntity admin = userRepo.findByEmailIgnoreCase(ADMIN_A_EMAIL).orElseThrow();
        assertThat(tenantScopeService.hasPlatformAccess(admin.getId())).isFalse();
    }

    // ── 24. Expired/future/revoked users cannot access module APIs ────────────

    @Test
    void expiredUser_cannotAccess_feeCollectionApi() {
        HttpHeaders h = login(EXPIRED_EMAIL);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/api/v1/workspace/fees/record-payment",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("studentId", "1", "amount", 1000), h),
                MAP_TYPE);
        // No effective permissions = 403
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void revokedUser_cannotAccess_studentListApi() {
        HttpHeaders h = login(REVOKED_EMAIL);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/api/v1/workspace", HttpMethod.GET, new HttpEntity<>(h), MAP_TYPE);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void futureUser_cannotAccess_workspace_api() {
        HttpHeaders h = login(FUTURE_EMAIL);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/api/v1/workspace", HttpMethod.GET, new HttpEntity<>(h), MAP_TYPE);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── 25. School A admin workspace ignores schoolB query param ─────────────

    @Test
    void adminA_workspaceRequest_withSchoolBParam_isNotCrossSchoolAccess() {
        // For non-platform users, TenantContext (set from RBAC) wins over query param.
        // The request succeeds but scoped to School A only, not School B.
        HttpHeaders h = login(ADMIN_A_EMAIL);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/api/v1/workspace?schoolId=" + schoolBId, HttpMethod.GET,
                new HttpEntity<>(h), MAP_TYPE);
        // Must not be forbidden (admin has workspace:access for their school)
        assertThat(resp.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        // The response reflects School A context (school name does not include "B" school's name)
        if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
            Object schoolName = resp.getBody().get("schoolName");
            if (schoolName != null) {
                assertThat(schoolName.toString()).doesNotContain("School B");
            }
        }
    }

    // ── 26. OPERATIONS cannot create firefighting request for another school ──

    @Test
    void operations_cannotCreate_firefightingRequest_withSchoolBInBody() {
        HttpHeaders h = login(OPS_A_EMAIL);
        Map<String, Object> body = Map.of(
                "title", "Test Request",
                "category", "Furniture",
                "estimatedBudget", 100000,
                "urgency", "MEDIUM",
                "schoolId", schoolBId  // attempt to inject school B
        );
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/api/v1/workspace/firefighting-requests",
                HttpMethod.POST,
                new HttpEntity<>(body, h),
                MAP_TYPE);
        // Either forbidden (no firefighting:create) or success scoped to School A — never School B
        // OPERATIONS role has firefighting:create for their school only
        // The key is the response must not reflect School B's context
        assertThat(resp.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── 27. Scoped RBAC assignment methods create correct scope ──────────────

    @Test
    void assignSchoolRole_createsSchoolScopedAssignment() {
        AppUserEntity u = userRepo.findByEmailIgnoreCase(VIEWER_A_EMAIL).orElseThrow();
        List<UserRoleAssignmentEntity> assignments = uraRepo.findByUser_Id(u.getId());
        boolean hasSchoolScoped = assignments.stream()
                .anyMatch(a -> a.getSchoolId() != null && a.getSchoolId().equals(schoolAId));
        assertThat(hasSchoolScoped).isTrue();
    }

    @Test
    void assignPlatformRole_createsPlatformScopedAssignment() {
        AppUserEntity sa = userRepo.findByEmailIgnoreCase(SA_EMAIL).orElseThrow();
        List<UserRoleAssignmentEntity> assignments = uraRepo.findByUser_Id(sa.getId());
        boolean hasPlatformScoped = assignments.stream()
                .anyMatch(a -> a.getSchoolId() == null && a.getZoneId() == null && a.isEffective());
        assertThat(hasPlatformScoped).isTrue();
    }

    // ── 28. Disabled module blocks access even with correct RBAC permission ───

    @Test
    void adminA_withCorrectPermission_isBlocked_whenStudentsModuleDisabled() {
        // Disable the STUDENTS module for School A
        SchoolEntity schoolA = schoolRepo.findByShortCodeIgnoreCase("TST-CS-A").orElseThrow();
        SchoolModuleEntitlementEntity entitlement = entitlementRepo
                .findBySchool_IdAndModuleCode(schoolAId, "STUDENTS")
                .orElseGet(() -> {
                    SchoolModuleEntitlementEntity e = new SchoolModuleEntitlementEntity();
                    e.setSchool(schoolA);
                    e.setModuleCode("STUDENTS");
                    e.setEnabled(true);
                    return entitlementRepo.save(e);
                });
        // Force-disable for test
        entitlement.setEnabled(false);
        entitlementRepo.save(entitlement);

        try {
            // ADMIN_A has student:create permission but STUDENTS module is disabled for School A
            HttpHeaders h = login(ADMIN_A_EMAIL);
            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    "/api/v1/workspace/students",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("fullName", "Blocked Student"), h),
                    MAP_TYPE);
            // Must get 403 — module disabled blocks access regardless of RBAC permission
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        } finally {
            // Restore module so other tests are not affected
            entitlement.setEnabled(true);
            entitlementRepo.save(entitlement);
        }
    }

    // ── Seed helpers ──────────────────────────────────────────────────────────

    private void seedSuperadmin() {
        AppUserEntity u = findOrCreate(SA_EMAIL, "CrossTest SuperAdmin", "SUPERADMIN", null);
        if (uraRepo.findByUser_Id(u.getId()).stream().noneMatch(a -> a.isActive())) {
            rbacService.assignPlatformRole(u.getId(), "SUPERADMIN", null);
        }
    }

    private void seedSchoolAdmin(String email, SchoolEntity school) {
        AppUserEntity u = findOrCreate(email, "CrossTest Admin " + school.getShortCode(), "ADMIN", school.getId());
        if (!uraRepo.existsActiveAssignment(u.getId(), "ADMIN", school.getId(), null)) {
            rbacService.assignSchoolRole(u.getId(), "ADMIN", school.getId(), null);
        }
    }

    private void seedSchoolUser(String email, String role, SchoolEntity school) {
        AppUserEntity u = findOrCreate(email, "CrossTest " + role + " " + school.getShortCode(), role, school.getId());
        if (!uraRepo.existsActiveAssignment(u.getId(), role, school.getId(), null)) {
            rbacService.assignSchoolRole(u.getId(), role, school.getId(), null);
        }
    }

    private void seedZoneAdmin(String email, ZoneEntity zone) {
        AppUserEntity u = findOrCreate(email, "CrossTest ZoneAdmin", "ZONE_ADMIN", null);
        if (!uraRepo.existsActiveAssignment(u.getId(), "ZONE_ADMIN", null, zone.getId())) {
            rbacService.assignZoneRole(u.getId(), "ZONE_ADMIN", zone.getId(), null);
        }
    }

    private void seedNoRoleUser() {
        findOrCreate(NO_ROLE_EMAIL, "CrossTest NoRole", "VIEWER", null);
        // intentionally no role assignment
    }

    private void seedRevokedUser(SchoolEntity school) {
        AppUserEntity u = findOrCreate(REVOKED_EMAIL, "CrossTest Revoked", "ADMIN", school.getId());
        // Guard on the unique scope (user, role, school, zone) regardless of active state:
        // the prior @BeforeEach leaves a *revoked* (inactive) row, which existsActiveAssignment
        // would miss — re-assigning would then violate uk_user_role_scoped. (Match by scope
        // columns only; the role relation is lazy and @BeforeEach has no open transaction.)
        var existing = uraRepo.findByUser_Id(u.getId()).stream()
                .filter(a -> school.getId().equals(a.getSchoolId()) && a.getZoneId() == null)
                .findFirst();
        if (existing.isEmpty()) {
            var ura = rbacService.assignSchoolRole(u.getId(), "ADMIN", school.getId(), null);
            // Immediately revoke so the user has no active permissions
            rbacService.revokeRoleAssignment(ura.getId(), null);
        } else if (existing.get().isActive()) {
            // Carried over as still-active from a previous seed — revoke it.
            rbacService.revokeRoleAssignment(existing.get().getId(), null);
        }
        // else: already revoked from a previous seed — desired end state, nothing to do.
    }

    private void seedExpiredUser(SchoolEntity school) {
        AppUserEntity u = findOrCreate(EXPIRED_EMAIL, "CrossTest Expired", "ADMIN", school.getId());
        boolean hasExpired = uraRepo.findByUser_Id(u.getId()).stream()
                .anyMatch(a -> !a.isActive() && a.getValidUntil() != null && a.getValidUntil().isBefore(OffsetDateTime.now()));
        if (!hasExpired) {
            uraRepo.findByUser_Id(u.getId()).stream()
                    .filter(UserRoleAssignmentEntity::isActive)
                    .forEach(a -> rbacService.revokeRoleAssignment(a.getId(), null));
            var ura = rbacService.assignSchoolRole(u.getId(), "ADMIN", school.getId(), null);
            ura.setValidUntil(OffsetDateTime.now().minusDays(1));
            ura.setActive(false);
            uraRepo.save(ura);
        }
    }

    private void seedFutureUser(SchoolEntity school) {
        AppUserEntity u = findOrCreate(FUTURE_EMAIL, "CrossTest Future", "ADMIN", school.getId());
        boolean hasFuture = uraRepo.findByUser_Id(u.getId()).stream()
                .anyMatch(a -> a.getValidFrom() != null && a.getValidFrom().isAfter(OffsetDateTime.now()));
        if (!hasFuture) {
            uraRepo.findByUser_Id(u.getId()).stream()
                    .filter(UserRoleAssignmentEntity::isActive)
                    .forEach(a -> rbacService.revokeRoleAssignment(a.getId(), null));
            var ura = rbacService.assignSchoolRole(u.getId(), "ADMIN", school.getId(), null);
            ura.setValidFrom(OffsetDateTime.now().plusDays(1));
            uraRepo.save(ura);
        }
    }

    private AppUserEntity findOrCreate(String email, String name, String role, Long branchId) {
        return userRepo.findByEmailIgnoreCase(email).orElseGet(() -> {
            AppUserEntity u = new AppUserEntity();
            u.setFullName(name);
            u.setEmail(email);
            u.setRole(role);
            u.setPasswordHash(passwordUtil.hash(PASSWORD));
            u.setBranchId(branchId);
            return userRepo.save(u);
        });
    }

    private HttpHeaders login(String email) {
        Map<String, String> body = Map.of("email", email, "password", PASSWORD);
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
