package com.custoking.ims.controller;

import com.custoking.ims.entity.AppUserEntity;
import com.custoking.ims.security.AppUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that access-control annotations on controllers work as expected.
 * Uses Spring Security's test support to inject users directly — no real
 * JWT tokens needed.
 *
 * Each mock principal is constructed with the in-memory permission set that
 * RbacService loads from the DB at auth time. This lets the @PreAuthorize
 * SpEL expressions evaluate without a live DB query during the test.
 *
 * Permission sets mirror the V112/V118 seed data exactly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ControllerSecurityTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("custoking_test")
                    .withUsername("test")
                    .withPassword("test");

    @Autowired MockMvc mockMvc;

    private AppUserDetails adminUser;
    private AppUserDetails operationsUser;
    private AppUserDetails superadminUser;
    private AppUserDetails accountantUser;

    @BeforeEach
    void setup() {
        adminUser       = userDetails(1L, "ADMIN",      10L);
        operationsUser  = userDetails(2L, "OPERATIONS", 10L);
        superadminUser  = userDetails(3L, "SUPERADMIN", null);
        accountantUser  = userDetails(4L, "ACCOUNTANT", 10L);
    }

    // ── Unauthenticated gets 401 ──────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/v1/attendance/daily-summary?date=2026-01-01",
        "/api/v1/ff/requests",
        "/api/v1/supply/orders",
        "/api/v1/fee-structure",
        "/api/v1/students",
    })
    void unauthenticated_protectedGet_returns401(String url) throws Exception {
        mockMvc.perform(get(url)).andExpect(status().isUnauthorized());
    }

    // ── OPERATIONS can access attendance (attendance:read) ────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/v1/attendance/daily-summary?date=2026-01-01",
        "/api/v1/attendance/section-info?date=2026-01-01&classId=1&sectionId=1A",
    })
    void operations_canRead_attendance(String url) throws Exception {
        mockMvc.perform(get(url).with(user(operationsUser)))
               .andExpect(result -> assertNotEquals(403, result.getResponse().getStatus()));
    }

    // ── OPERATIONS can read firefighting (firefighting:read) ──────────────────

    @Test
    void operations_canRead_firefighting() throws Exception {
        mockMvc.perform(get("/api/v1/ff/requests").with(user(operationsUser)))
               .andExpect(result -> assertNotEquals(403, result.getResponse().getStatus()));
    }

    // ── OPERATIONS can read supply orders (order:read) ────────────────────────

    @Test
    void operations_canRead_orders() throws Exception {
        mockMvc.perform(get("/api/v1/supply/orders").with(user(operationsUser)))
               .andExpect(result -> assertNotEquals(403, result.getResponse().getStatus()));
    }

    // ── OPERATIONS cannot write fee structure (no fee_structure:manage) ───────

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/fee-structure/item", "/api/v1/fee-structure/band"})
    void operations_cannotWrite_feeStructure(String url) throws Exception {
        mockMvc.perform(post(url)
                .with(user(operationsUser))
                .contentType("application/json")
                .content("{}"))
               .andExpect(status().isForbidden());
    }

    // ── OPERATIONS cannot collect fees / create payments ─────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/fee-assignments", "/api/v1/payments"})
    void operations_cannotCreate_feePayments(String url) throws Exception {
        mockMvc.perform(post(url)
                .with(user(operationsUser))
                .contentType("application/json")
                .content("{}"))
               .andExpect(status().isForbidden());
    }

    // ── OPERATIONS cannot manage users (no user:manage) ───────────────────────

    @Test
    void operations_cannotAccess_userManagement() throws Exception {
        mockMvc.perform(get("/api/v1/users").with(user(operationsUser)))
               .andExpect(status().isForbidden());
    }

    // ── OPERATIONS cannot access schools (no school:read) ────────────────────

    @Test
    void operations_cannotAccess_schools() throws Exception {
        mockMvc.perform(get("/api/v1/schools").with(user(operationsUser)))
               .andExpect(status().isForbidden());
    }

    // ── ACCOUNTANT cannot approve supply orders (no order:approve) ────────────

    @Test
    void accountant_cannotApprove_orders() throws Exception {
        mockMvc.perform(post("/api/v1/approvals/1/approve")
                .with(user(accountantUser)))
               .andExpect(status().isForbidden());
    }

    // ── SUPERADMIN can approve orders (order:approve) ────────────────────────

    @Test
    void superadmin_canApprove_orders() throws Exception {
        mockMvc.perform(post("/api/v1/approvals/999999/approve")
                .with(user(superadminUser)))
               .andExpect(result -> assertNotEquals(403, result.getResponse().getStatus()));
    }

    // ── ADMIN cannot fulfill firefighting (no firefighting:fulfill) ───────────

    @Test
    void admin_cannotFulfill_firefighting() throws Exception {
        mockMvc.perform(patch("/api/v1/ff/requests/FF-001/fulfill")
                .with(user(adminUser)))
               .andExpect(status().isForbidden());
    }

    // ── ADMIN can approve firefighting (firefighting:approve) ────────────────

    @Test
    void admin_canApprove_firefighting() throws Exception {
        mockMvc.perform(post("/api/v1/ff/requests/FF-001/approve-bursar")
                .with(user(adminUser))
                .contentType("application/json")
                .content("{}"))
               .andExpect(result -> assertNotEquals(403, result.getResponse().getStatus()));
    }

    // ── SUPERADMIN can list schools (school:read) ─────────────────────────────

    @Test
    void superadmin_canAccess_schoolManagement() throws Exception {
        mockMvc.perform(get("/api/v1/schools").with(user(superadminUser)))
               .andExpect(result -> assertNotEquals(403, result.getResponse().getStatus()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static AppUserDetails userDetails(long id, String role, Long branchId) {
        AppUserEntity entity = new AppUserEntity();
        entity.setId(id);
        entity.setEmail(role.toLowerCase() + "@test.com");
        entity.setFullName(role + " User");
        entity.setRole(role);
        entity.setPasswordHash("$2a$12$fake");
        entity.setBranchId(branchId);
        return new AppUserDetails(entity, permissionsForRole(role));
    }

    /**
     * In-memory permission sets that mirror the V112/V118 seed data.
     * Keep in sync with Flyway migrations — do not add permissions here
     * that are not in the DB seed.
     */
    private static Set<String> permissionsForRole(String role) {
        return switch (role) {
            case "SUPERADMIN" -> Set.of(
                    "student:read", "student:create", "student:update", "student:delete", "student:import",
                    "fee_structure:read", "fee_structure:manage", "fee:collect", "fee:read", "fee:reverse", "fee:assign",
                    "attendance:read", "attendance:manage",
                    "order:read", "order:create", "order:update", "order:approve", "order:fulfill", "order:reject",
                    "firefighting:read", "firefighting:create", "firefighting:update",
                    "firefighting:approve", "firefighting:fulfill",
                    "payment:create", "payment:read", "payment:reconcile",
                    "invoice:create", "invoice:read", "invoice:cancel",
                    "staff:read", "staff:manage",
                    "audit:read", "user:manage",
                    "school:read", "school:create", "school:update", "school:admin_manage", "school:suspend",
                    "zone:read", "zone:manage", "zone:assign_school",
                    "plan:read", "plan:manage", "timetable:read", "timetable:manage",
                    "workflow:read", "workflow:act",
                    "customer:read", "customer:create", "report:read",
                    "notification:read", "notification:send",
                    "role:read", "role:create", "role:update", "permission:read"
            );
            case "ADMIN" -> Set.of(
                    "student:read", "student:create", "student:update", "student:import",
                    "fee:read", "fee:collect", "fee_structure:read", "fee_structure:manage", "fee:reverse", "fee:assign",
                    "attendance:read", "attendance:manage",
                    "order:read", "order:create", "order:update", "order:reject",
                    "firefighting:read", "firefighting:create", "firefighting:update", "firefighting:approve",
                    "payment:read", "payment:create",
                    "invoice:read", "invoice:cancel",
                    "staff:read", "staff:manage", "audit:read",
                    "plan:read", "plan:manage", "timetable:read", "timetable:manage",
                    "workflow:read", "workflow:act",
                    "customer:read", "customer:create", "report:read",
                    "notification:read", "notification:send"
            );
            case "OPERATIONS" -> Set.of(
                    "student:read", "student:create", "student:update", "student:import",
                    "attendance:read", "attendance:manage",
                    "order:read", "order:create",
                    "firefighting:read", "firefighting:create",
                    "staff:read", "plan:read", "timetable:read",
                    "workflow:read",
                    "customer:read"
            );
            case "ACCOUNTANT" -> Set.of(
                    "student:read",
                    "fee:read", "fee:collect", "fee_structure:read", "fee_structure:manage",
                    "order:read", "firefighting:read",
                    "payment:read", "payment:create", "payment:reconcile",
                    "invoice:read", "invoice:create", "invoice:cancel",
                    "audit:read", "workflow:read", "workflow:act",
                    "customer:read", "customer:create", "report:read",
                    "notification:read"
            );
            case "TEACHER" -> Set.of(
                    "student:read", "student:update",
                    "attendance:read", "attendance:manage",
                    "timetable:read", "timetable:manage", "staff:read"
            );
            case "VIEWER" -> Set.of(
                    "student:read", "fee:read", "fee_structure:read",
                    "attendance:read", "order:read", "firefighting:read",
                    "payment:read", "invoice:read", "staff:read",
                    "plan:read", "timetable:read", "workflow:read", "report:read"
            );
            default -> Set.of();
        };
    }
}
