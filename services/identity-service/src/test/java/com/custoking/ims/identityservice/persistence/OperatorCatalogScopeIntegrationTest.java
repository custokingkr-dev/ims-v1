package com.custoking.ims.identityservice.persistence;

import com.custoking.ims.identityservice.application.OperatorDirectory;
import com.custoking.ims.identityservice.application.OperatorSelection.Outcome;
import com.custoking.ims.identityservice.infrastructure.TenantSchoolClient;
import com.custoking.ims.identityservice.persistence.OperatorDirectoryRepository.OperatorRow;
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

    // ---- helpers ---------------------------------------------------------------------------------------------------

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
