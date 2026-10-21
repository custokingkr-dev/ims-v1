package com.custoking.ims.identityservice.persistence;

import com.custoking.ims.identityservice.application.IdentityAuthService;
import com.custoking.ims.identityservice.application.OperatorDirectory;
import com.custoking.ims.identityservice.application.OperatorSelection.Outcome;
import com.custoking.ims.identityservice.infrastructure.TenantSchoolClient;
import com.custoking.ims.identityservice.persistence.OperatorDirectoryRepository.OperatorRow;
import com.custoking.ims.identityservice.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Operators scoped to a school and a catalogue item, against the real migrated schema: the new assignment, that the
 * existing school-wide meaning is untouched, the directory lookups that drive auto-assignment, and the constraints.
 */
@SpringBootTest(properties = {
    "app.jwt-secret=integration-test-jwt-secret-AAAABBBBCCCC1234",
    "identity.introspection-token=it-token",
    "identity.tenant-school.base-url=http://localhost:19999",
    "identity.tenant-school.token=it-ts-token"
})
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
class OperatorCatalogScopeIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.flyway.url", PG::getJdbcUrl);
        r.add("spring.flyway.user", PG::getUsername);
        r.add("spring.flyway.password", PG::getPassword);
    }

    /** Each test works in its own school so the shared database never leaks between tests. */
    private static final AtomicLong SCHOOLS = new AtomicLong(1000);
    private static final AtomicLong EMAILS = new AtomicLong();

    @Autowired RbacCommandRepository rbac;
    @Autowired RbacReadRepository reads;
    @Autowired OperatorDirectory directory;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired IdentityAuthService auth;
    @Autowired JwtService jwt;

    private static long newSchool() { return SCHOOLS.incrementAndGet(); }

    // ---- assignment ----------------------------------------------------------------------------------------------

    @Test
    void anItemSpecificScopeIsItsOwnAssignmentAndNeverWidensTheSchoolWideSet() throws Exception {
        long school = newSchool();
        long operator = insertUser("OPERATIONS");
        Map<String, Object> view = rbac.assignOperatorScope(operator, school, " notebooks ", 99L);

        assertThat(view.get("catalogCategory")).isEqualTo("NOTEBOOKS");
        assertThat(view.get("schoolId")).isEqualTo(school);
        assertThat(reads.operatorSchoolIds(operator)).as("school-wide set must not include item-specific schools").isEmpty();
        assertThat(reads.operatorScopeKeys(operator)).containsExactly(school + ":NOTEBOOKS");
        assertThat(auditNote(operator)).isEqualTo("catalogCategory=NOTEBOOKS");
    }

    @Test
    void aUserCanHoldSchoolWideAndSeveralItemScopesAtTheSameSchool() throws Exception {
        long school = newSchool();
        long operator = insertUser("OPERATIONS");
        rbac.syncOperatorSchools(operator, List.of(school), 99L);
        rbac.assignOperatorScope(operator, school, "NOTEBOOKS", 99L);
        rbac.assignOperatorScope(operator, school, "TIES", 99L);

        assertThat(reads.operatorSchoolIds(operator)).containsExactly(school);
        assertThat(reads.operatorScopeKeys(operator)).containsExactly(school + ":NOTEBOOKS", school + ":TIES");
    }

    @Test
    void theSchoolSyncManagesSchoolWideRowsOnlyAndLeavesItemScopesAlone() throws Exception {
        long school = newSchool();
        long other = newSchool();
        long operator = insertUser("OPERATIONS");
        rbac.assignOperatorScope(operator, school, "NOTEBOOKS", 99L);
        rbac.syncOperatorSchools(operator, List.of(other), 99L);
        assertThat(reads.operatorSchoolIds(operator)).containsExactly(other);
        assertThat(reads.operatorScopeKeys(operator)).containsExactly(school + ":NOTEBOOKS");

        rbac.syncOperatorSchools(operator, List.of(), 99L);
        assertThat(reads.operatorSchoolIds(operator)).isEmpty();
        assertThat(reads.operatorScopeKeys(operator)).as("emptying the school set must not revoke an item scope").containsExactly(school + ":NOTEBOOKS");
    }

    @Test
    void revokingTheAssignmentRemovesTheScopeAndAuditsTheItem() throws Exception {
        long school = newSchool();
        long operator = insertUser("OPERATIONS");
        long assignmentId = ((Number) rbac.assignOperatorScope(operator, school, "NOTEBOOKS", 99L).get("id")).longValue();
        rbac.revokeAssignment(operator, assignmentId, Map.of("revokedBy", 99L));
        assertThat(reads.operatorScopeKeys(operator)).isEmpty();
        assertThat(directory.eligible(operator, school, "NOTEBOOKS")).isFalse();
        assertThat(auditNotes(operator, "ROLE_REVOKED")).containsExactly("catalogCategory=NOTEBOOKS");
    }

    @Test
    void assignmentIsRefusedForNonOperatorsUnknownUsersBadInputAndDuplicates() throws Exception {
        long school = newSchool();
        long admin = insertUser("ADMIN");
        long operator = insertUser("OPERATIONS");

        assertStatus(() -> rbac.assignOperatorScope(admin, school, "NOTEBOOKS", 99L), HttpStatus.BAD_REQUEST, "not an operator");
        assertStatus(() -> rbac.assignOperatorScope(9_999_999L, school, "NOTEBOOKS", 99L), HttpStatus.NOT_FOUND, "user not found");
        assertStatus(() -> rbac.assignOperatorScope(operator, school, "bad code", 99L), HttpStatus.BAD_REQUEST, "catalogue category");
        assertStatus(() -> rbac.assignOperatorScope(operator, school, null, 99L), HttpStatus.BAD_REQUEST, "catalogCategory is required");
        assertStatus(() -> rbac.assignOperatorScope(operator, null, "NOTEBOOKS", 99L), HttpStatus.BAD_REQUEST, "schoolId is required");
        assertStatus(() -> rbac.assignOperatorScope(operator, 0L, "NOTEBOOKS", 99L), HttpStatus.BAD_REQUEST, "schoolId is required");

        rbac.assignOperatorScope(operator, school, "NOTEBOOKS", 99L);
        assertStatus(() -> rbac.assignOperatorScope(operator, school, "NOTEBOOKS", 99L), HttpStatus.CONFLICT, "already exists");
        assertThat(reads.operatorScopeKeys(admin)).isEmpty();
    }

    // ---- directory ------------------------------------------------------------------------------------------------

    @Test
    void autoAssignmentPicksTheItemSpecificOperatorElseTheSchoolWideOneElseNobody() throws Exception {
        long school = newSchool();
        assertThat(directory.resolve(school, "NOTEBOOKS").outcome()).isEqualTo(Outcome.NONE);

        long wide = insertUser("OPERATIONS");
        rbac.syncOperatorSchools(wide, List.of(school), 99L);
        var fallback = directory.resolve(school, "NOTEBOOKS");
        assertThat(fallback.outcome()).isEqualTo(Outcome.SCHOOL_WIDE);
        assertThat(fallback.userId()).isEqualTo(wide);

        long item = insertUser("OPERATIONS");
        rbac.assignOperatorScope(item, school, "NOTEBOOKS", 99L);
        var picked = directory.resolve(school, "notebooks");
        assertThat(picked.outcome()).isEqualTo(Outcome.ITEM_SPECIFIC);
        assertThat(picked.userId()).isEqualTo(item);
        assertThat(directory.resolve(school, "TIES").userId()).as("another item falls back to the school-wide operator").isEqualTo(wide);

        long second = insertUser("OPERATIONS");
        rbac.assignOperatorScope(second, school, "NOTEBOOKS", 99L);
        var ambiguous = directory.resolve(school, "NOTEBOOKS");
        assertThat(ambiguous.outcome()).isEqualTo(Outcome.AMBIGUOUS);
        assertThat(ambiguous.userId()).isNull();
        assertThat(ambiguous.candidates()).containsExactlyInAnyOrder(item, second);
    }

    @Test
    void severalSchoolWideOperatorsAreAmbiguousWhenNoItemOperatorExists() throws Exception {
        long school = newSchool();
        long a = insertUser("OPERATIONS");
        long b = insertUser("OPERATIONS");
        rbac.syncOperatorSchools(a, List.of(school), 99L);
        rbac.syncOperatorSchools(b, List.of(school), 99L);
        assertThat(directory.resolve(school, "NOTEBOOKS").outcome()).isEqualTo(Outcome.AMBIGUOUS);
    }

    @Test
    void anOperatorIsEligibleForTheirOwnItemOrAnyItemWhenSchoolWideButNotOtherwise() throws Exception {
        long school = newSchool();
        long otherSchool = newSchool();
        long item = insertUser("OPERATIONS");
        long wide = insertUser("OPERATIONS");
        long nobody = insertUser("OPERATIONS");
        rbac.assignOperatorScope(item, school, "NOTEBOOKS", 99L);
        rbac.syncOperatorSchools(wide, List.of(school), 99L);

        assertThat(directory.eligible(item, school, "NOTEBOOKS")).isTrue();
        assertThat(directory.eligible(item, school, "TIES")).as("scoped to notebooks only").isFalse();
        assertThat(directory.eligible(item, otherSchool, "NOTEBOOKS")).isFalse();
        assertThat(directory.eligible(wide, school, "TIES")).isTrue();
        assertThat(directory.eligible(wide, otherSchool, "TIES")).isFalse();
        assertThat(directory.eligible(nobody, school, "NOTEBOOKS")).isFalse();
        assertThat(directory.eligible(0, school, "NOTEBOOKS")).isFalse();
    }

    @Test
    void disabledUsersAndExpiredAssignmentsAreNeverChosenOrEligible() throws Exception {
        long school = newSchool();
        long gone = insertUser("OPERATIONS");
        long expired = insertUser("OPERATIONS");
        long live = insertUser("OPERATIONS");
        rbac.assignOperatorScope(gone, school, "NOTEBOOKS", 99L);
        rbac.assignOperatorScope(expired, school, "NOTEBOOKS", 99L);
        rbac.assignOperatorScope(live, school, "NOTEBOOKS", 99L);
        exec("UPDATE identity.app_users SET deleted_at = now() WHERE id = " + gone);
        exec("UPDATE identity.user_role_assignments SET valid_until = now() - interval '1 day' WHERE user_id = " + expired);

        var result = directory.resolve(school, "NOTEBOOKS");
        assertThat(result.outcome()).isEqualTo(Outcome.ITEM_SPECIFIC);
        assertThat(result.userId()).isEqualTo(live);
        assertThat(directory.eligible(gone, school, "NOTEBOOKS")).isFalse();
        assertThat(directory.eligible(expired, school, "NOTEBOOKS")).isFalse();
    }

    @Test
    void theSuperadminListShowsNamesAndFiltersBySchoolAndItem() throws Exception {
        long school = newSchool();
        long other = newSchool();
        long item = insertUser("OPERATIONS");
        long wide = insertUser("OPERATIONS");
        rbac.assignOperatorScope(item, school, "NOTEBOOKS", 99L);
        rbac.syncOperatorSchools(wide, List.of(school, other), 99L);

        List<OperatorRow> inSchool = directory.list(school, null);
        assertThat(inSchool).extracting(OperatorRow::userId).containsExactlyInAnyOrder(item, wide);
        assertThat(inSchool).filteredOn(r -> r.userId() == wide).singleElement().satisfies(r -> assertThat(r.catalogCategory()).isNull());
        assertThat(inSchool).filteredOn(r -> r.userId() == item).singleElement().satisfies(r -> {
            assertThat(r.catalogCategory()).isEqualTo("NOTEBOOKS");
            assertThat(r.fullName()).startsWith("IT ");
        });
        assertThat(directory.list(school, "notebooks")).extracting(OperatorRow::userId).containsExactly(item);
        assertThat(directory.list(other, "NOTEBOOKS")).isEmpty();
    }

    /**
     * The tracker shows who is assigned a step and who wrote a note (names route for operations-service). Identity answers
     * only for active users who currently hold an active OPERATIONS or SUPERADMIN assignment; any other id is left out.
     */
    @Test
    void namesEndpointReturnsOnlyStaffNames() throws Exception {
        long school = newSchool();
        long wide = insertUser("OPERATIONS");
        long item = insertUser("OPERATIONS");
        long superadmin = insertUser("SUPERADMIN");
        long schoolAdmin = insertUser("ADMIN");
        long deleted = insertUser("OPERATIONS");
        long revoked = insertUser("OPERATIONS");
        long expired = insertUser("OPERATIONS");
        long notYet = insertUser("OPERATIONS");
        long roleColumnOnly = insertUser("OPERATIONS");
        rbac.syncOperatorSchools(wide, List.of(school), 99L);
        rbac.assignOperatorScope(item, school, "NOTEBOOKS", 99L);
        assign(superadmin, "SUPERADMIN", null);
        assign(schoolAdmin, "ADMIN", school);
        rbac.syncOperatorSchools(deleted, List.of(school), 99L);
        exec("UPDATE identity.app_users SET deleted_at = now() WHERE id = " + deleted);
        long revokedAssignment = ((Number) rbac.assignOperatorScope(revoked, school, "NOTEBOOKS", 99L).get("id")).longValue();
        rbac.revokeAssignment(revoked, revokedAssignment, Map.of("revokedBy", 99L));
        rbac.syncOperatorSchools(expired, List.of(school), 99L);
        exec("UPDATE identity.user_role_assignments SET valid_until = now() - interval '1 day' WHERE user_id = " + expired);
        rbac.syncOperatorSchools(notYet, List.of(school), 99L);
        exec("UPDATE identity.user_role_assignments SET valid_from = now() + interval '1 day' WHERE user_id = " + notYet);
        exec("UPDATE identity.app_users SET full_name = 'Wide Operator' WHERE id = " + wide);
        exec("UPDATE identity.app_users SET full_name = 'Item Operator' WHERE id = " + item);
        exec("UPDATE identity.app_users SET full_name = 'Super Admin' WHERE id = " + superadmin);

        List<Long> asked = List.of(wide, item, superadmin, schoolAdmin, deleted, revoked, expired, notYet, roleColumnOnly, 9_999_999L, wide);
        assertThat(directory.names(asked)).containsExactly(
                new OperatorDirectoryRepository.StaffName(wide, "Wide Operator"),
                new OperatorDirectoryRepository.StaffName(item, "Item Operator"),
                new OperatorDirectoryRepository.StaffName(superadmin, "Super Admin"));
        assertThat(directory.names(List.of())).isEmpty();
        assertThatThrownBy(() -> directory.names(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> directory.names(java.util.stream.LongStream.rangeClosed(1, 201).boxed().toList()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("200");
        assertThat(directory.names(java.util.stream.LongStream.rangeClosed(1, 200).boxed().toList())).isNotNull();
    }

    /** A role assignment as an administrator would add it, with no catalogue item. */
    private void assign(long userId, String role, Long schoolId) {
        jdbc.sql("INSERT INTO identity.user_role_assignments (user_id, role_id, school_id) "
                        + "SELECT :user, id, CAST(:school AS bigint) FROM identity.roles WHERE name = :role")
                .param("user", userId).param("school", schoolId).param("role", role).update();
    }

    // ---- provisioning ---------------------------------------------------------------------------------------------

    @Test
    void provisioningAnOperatorWithACatalogItemCreatesTheScopedAssignment() throws Exception {
        long school = newSchool();
        TenantSchoolClient client = mock(TenantSchoolClient.class);
        when(client.school(anyLong())).thenReturn(new TenantSchoolClient.TenantSchoolRef(school, "Scope School"));
        IdentityUserProvisioningRepository provisioning = new IdentityUserProvisioningRepository(jdbc, passwordEncoder, client);

        Map<String, Object> created = provisioning.provisionSchoolUser(school, "OPERATIONS", Map.of(
                "fullName", "Scoped Operator", "email", "scoped-" + EMAILS.incrementAndGet() + "@example.com",
                "temporaryPassword", "Str0ng!Passw0rd#2026", "catalogCategory", "notebooks", "assignedBy", 99L));

        long userId = ((Number) created.get("userId")).longValue();
        assertThat(created.get("catalogCategory")).isEqualTo("NOTEBOOKS");
        assertThat(reads.operatorSchoolIds(userId)).isEmpty();
        assertThat(reads.operatorScopeKeys(userId)).containsExactly(school + ":NOTEBOOKS");
        assertThat(directory.resolve(school, "NOTEBOOKS").userId()).isEqualTo(userId);

        assertThatThrownBy(() -> provisioning.provisionSchoolUser(school, "ADMIN", Map.of(
                "fullName", "Bad Admin", "email", "bad-" + EMAILS.incrementAndGet() + "@example.com",
                "temporaryPassword", "Str0ng!Passw0rd#2026", "catalogCategory", "NOTEBOOKS")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("operators only");
    }

    @Test
    void provisioningWithoutACatalogItemStaysSchoolWide() throws Exception {
        long school = newSchool();
        TenantSchoolClient client = mock(TenantSchoolClient.class);
        when(client.school(anyLong())).thenReturn(new TenantSchoolClient.TenantSchoolRef(school, "Wide School"));
        IdentityUserProvisioningRepository provisioning = new IdentityUserProvisioningRepository(jdbc, passwordEncoder, client);
        Map<String, Object> created = provisioning.provisionSchoolUser(school, "OPERATIONS", Map.of(
                "fullName", "Wide Operator", "email", "wide-" + EMAILS.incrementAndGet() + "@example.com",
                "temporaryPassword", "Str0ng!Passw0rd#2026", "assignedBy", 99L));
        long userId = ((Number) created.get("userId")).longValue();
        assertThat(created).doesNotContainKey("catalogCategory");
        assertThat(reads.operatorSchoolIds(userId)).containsExactly(school);
        assertThat(reads.operatorScopeKeys(userId)).isEmpty();
    }

    // ---- constraints ----------------------------------------------------------------------------------------------

    @Test
    void theDatabaseRejectsMalformedScopesAndDuplicateActiveItemScopes() throws Exception {
        long school = newSchool();
        long operator = insertUser("OPERATIONS");
        rbac.assignOperatorScope(operator, school, "NOTEBOOKS", 99L);
        long roleId = jdbc.sql("SELECT id FROM identity.roles WHERE name = 'OPERATIONS'").query(Long.class).single();
        String insert = "INSERT INTO identity.user_role_assignments (user_id, role_id, school_id, zone_id, catalog_category) VALUES (%d, %d, %s, %s, %s)";

        assertThatThrownBy(() -> exec(String.format(insert, operator, roleId, school, "NULL", "'lower'"))).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> exec(String.format(insert, operator, roleId, school, "NULL", "'X'"))).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> exec(String.format(insert, operator, roleId, "NULL", "NULL", "'NOTEBOOKS'"))).as("a category needs a school").isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> exec(String.format(insert, operator, roleId, school, "5", "'NOTEBOOKS'"))).as("a category cannot be a zone assignment").isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> exec(String.format(insert, operator, roleId, school, "NULL", "'NOTEBOOKS'"))).as("duplicate active item scope").isInstanceOf(SQLException.class);
        exec(String.format(insert, operator, roleId, school, "NULL", "'TIES'"));
    }

    // ---- what an operator's login carries (owner decision D2: item-scoped operators get the tracker only) ------------

    /** The fixed permission set of an operator who holds item scopes. */
    private static final List<String> ITEM_ONLY_PERMISSIONS =
            List.of("workspace:access", "notebook-tracker:read", "notebook-tracker:act", "order:read", "order:update");

    @Test
    void itemOnlyOperatorPermissionsAreTrackerOnly() throws Exception {
        long school = newSchool();
        LoginUser operator = insertLoginUser("OPERATIONS", school);
        rbac.assignOperatorScope(operator.id(), school, "NOTEBOOKS", 99L);

        var principal = introspect(login(operator));
        assertThat(principal.permissions()).as("no students, fees, attendance or other workflows")
                .containsExactlyInAnyOrderElementsOf(ITEM_ONLY_PERMISSIONS);
    }

    @Test
    void itemOnlyOperatorHasNoSchoolContext() throws Exception {
        long school = newSchool();
        LoginUser operator = insertLoginUser("OPERATIONS", school);
        rbac.assignOperatorScope(operator.id(), school, "NOTEBOOKS", 99L);

        var login = login(operator);
        assertThat(login.authResponse().branchId()).isNull();
        assertThat(jwt.claims(login.authResponse().accessToken()).get("sid")).as("JWT school id").isNull();
        var principal = introspect(login);
        assertThat(principal.branchId()).as("the gateway forwards this as the school id").isNull();
        assertThat(principal.branchName()).isNull();
        assertThat(principal.operatorScopes()).containsExactly(school + ":NOTEBOOKS");
    }

    @Test
    void operatorWithSchoolWideAndItemKeepsSchoolContext() throws Exception {
        long a = newSchool();
        long b = newSchool();
        LoginUser operator = insertLoginUser("OPERATIONS", a);
        rbac.syncOperatorSchools(operator.id(), List.of(a), 99L);
        rbac.assignOperatorScope(operator.id(), b, "NOTEBOOKS", 99L);

        var login = login(operator);
        var principal = introspect(login);
        assertThat(principal.branchId()).isEqualTo(a);
        assertThat(((Number) jwt.claims(login.authResponse().accessToken()).get("sid")).longValue()).isEqualTo(a);
        assertThat(principal.permissions()).as("the full OPERATIONS set, which carries the tracker codes")
                .containsExactlyInAnyOrderElementsOf(reads.permissionCodesForRole("OPERATIONS"))
                .contains("notebook-tracker:read", "notebook-tracker:act")
                .containsAll(ITEM_ONLY_PERMISSIONS);
    }

    @Test
    void theBranchIsDroppedOnlyForAnItemScopedOperatorOutsideTheirSchoolWideSchools() throws Exception {
        long wide = newSchool();
        long item = newSchool();
        long stale = newSchool();
        LoginUser scoped = insertLoginUser("OPERATIONS", stale);
        rbac.syncOperatorSchools(scoped.id(), List.of(wide), 99L);
        rbac.assignOperatorScope(scoped.id(), item, "NOTEBOOKS", 99L);
        var scopedLogin = login(scoped);
        assertThat(introspect(scopedLogin).branchId()).as("a stale branch is not a school-wide school").isNull();
        assertThat(jwt.claims(scopedLogin.authResponse().accessToken()).get("sid")).isNull();

        LoginUser unscoped = insertLoginUser("OPERATIONS", stale);
        rbac.syncOperatorSchools(unscoped.id(), List.of(wide), 99L);
        var unscopedLogin = login(unscoped);
        assertThat(introspect(unscopedLogin).branchId()).as("operators without item scopes are unaffected").isEqualTo(stale);
        assertThat(((Number) jwt.claims(unscopedLogin.authResponse().accessToken()).get("sid")).longValue()).isEqualTo(stale);
        assertThat(introspect(unscopedLogin).permissions()).containsExactlyInAnyOrderElementsOf(reads.permissionCodesForRole("OPERATIONS"));
    }

    @Test
    void effectivePermissionsIgnoreItemRows() throws Exception {
        long school = newSchool();
        long operator = insertUser("OPERATIONS");
        rbac.assignOperatorScope(operator, school, "NOTEBOOKS", 99L);
        assertThat(reads.effectivePermissions(operator, school, null)).as("an item scope is not a school-wide role").isEmpty();
        assertThat(reads.effectivePermissions(operator, null, null)).isEmpty();

        rbac.syncOperatorSchools(operator, List.of(school), 99L);
        assertThat(reads.effectivePermissions(operator, school, null))
                .containsExactlyInAnyOrderElementsOf(reads.permissionCodesForRole("OPERATIONS"));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static final String PASSWORD = "Str0ng!Passw0rd#2026";

    private record LoginUser(long id, String email) { }

    /** A user who can sign in, with {@code app_users.branch_id} set as provisioning sets it. */
    private LoginUser insertLoginUser(String role, long branchId) {
        String email = "login-" + EMAILS.incrementAndGet() + "-" + System.nanoTime() + "@example.com";
        long id = jdbc.sql("""
                INSERT INTO identity.app_users (full_name, email, password_hash, role, branch_id, branch_name, created_at)
                VALUES (:name, :email, :hash, :role, :branch, :branchName, now()) RETURNING id
                """).param("name", "IT " + role).param("email", email).param("hash", passwordEncoder.encode(PASSWORD))
                .param("role", role).param("branch", branchId).param("branchName", "School " + branchId)
                .query(Long.class).single();
        return new LoginUser(id, email);
    }

    private IdentityAuthService.LoginResult login(LoginUser user) {
        return auth.login(new IdentityAuthService.LoginRequest(user.email(), PASSWORD));
    }

    private IdentityAuthService.AuthResponse introspect(IdentityAuthService.LoginResult login) {
        var response = auth.introspect(login.authResponse().accessToken());
        assertThat(response.active()).isTrue();
        return response.principal();
    }

    private static void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus status, String message) {
        assertThatThrownBy(call).isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(status))
                .hasMessageContaining(message);
    }

    private static long insertUser(String role) throws Exception {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO identity.app_users (full_name, email, password_hash, role, created_at) VALUES (?, ?, 'bcrypt-placeholder', ?, now()) RETURNING id")) {
            ps.setString(1, "IT " + role);
            ps.setString(2, "scope-" + EMAILS.incrementAndGet() + "-" + System.nanoTime() + "@example.com");
            ps.setString(3, role);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static String auditNote(long userId) throws Exception {
        List<String> notes = auditNotes(userId, "ROLE_ASSIGNED");
        return notes.isEmpty() ? null : notes.get(0);
    }

    private static List<String> auditNotes(long userId, String event) throws Exception {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("SELECT new_value FROM identity.rbac_audit_log WHERE target_user_id = ? AND event_type = ? ORDER BY id")) {
            ps.setLong(1, userId);
            ps.setString(2, event);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new java.util.ArrayList<>();
                while (rs.next()) out.add(rs.getString(1));
                return out;
            }
        }
    }

    private static void exec(String sql) throws Exception {
        try (Connection c = conn(); var st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static Connection conn() throws Exception {
        Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
        c.setAutoCommit(true);
        return c;
    }
}
