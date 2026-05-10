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

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that access-control annotations on controllers work as expected.
 * Uses Spring Security's test support to inject users directly — no real
 * JWT tokens needed.  Assertions against 403/401 are answered by the security
 * filter chain before any service code runs, so the DB content is irrelevant.
 *
 * Key assertions:
 * - OPERATIONS user gets 403 on endpoints that require higher permissions.
 * - OPERATIONS user gets non-403 on endpoints they are allowed to access.
 * - SUPERADMIN can access superadmin-only endpoints.
 * - Unauthenticated users get 401 on all protected endpoints.
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

    // ── OPERATIONS can access attendance (ATTENDANCE_READ) ────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/v1/attendance/daily-summary?date=2026-01-01",
        "/api/v1/attendance/section-info?date=2026-01-01&classId=1&sectionId=1A",
    })
    void operations_canRead_attendance(String url) throws Exception {
        // Security must pass OPERATIONS through; service may 500 on empty DB — that's OK.
        mockMvc.perform(get(url).with(user(operationsUser)))
               .andExpect(result -> assertNotEquals(403, result.getResponse().getStatus()));
    }

    // ── OPERATIONS can read firefighting ──────────────────────────────────────

    @Test
    void operations_canRead_firefighting() throws Exception {
        mockMvc.perform(get("/api/v1/ff/requests").with(user(operationsUser)))
               .andExpect(result -> assertNotEquals(403, result.getResponse().getStatus()));
    }

    // ── OPERATIONS can read supply orders ────────────────────────────────────

    @Test
    void operations_canRead_orders() throws Exception {
        mockMvc.perform(get("/api/v1/supply/orders").with(user(operationsUser)))
               .andExpect(result -> assertNotEquals(403, result.getResponse().getStatus()));
    }

    // ── OPERATIONS cannot access fee structure mutating endpoints ─────────────

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/fee-structure/item", "/api/v1/fee-structure/band"})
    void operations_cannotWrite_feeStructure(String url) throws Exception {
        mockMvc.perform(post(url)
                .with(user(operationsUser))
                .contentType("application/json")
                .content("{}"))
               .andExpect(status().isForbidden());
    }

    // ── OPERATIONS cannot collect fees / record payments ─────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/fee-assignments", "/api/v1/payments"})
    void operations_cannotCreate_feePayments(String url) throws Exception {
        mockMvc.perform(post(url)
                .with(user(operationsUser))
                .contentType("application/json")
                .content("{}"))
               .andExpect(status().isForbidden());
    }

    // ── OPERATIONS cannot manage users ────────────────────────────────────────

    @Test
    void operations_cannotAccess_userManagement() throws Exception {
        mockMvc.perform(get("/api/v1/users").with(user(operationsUser)))
               .andExpect(status().isForbidden());
    }

    // ── OPERATIONS cannot access schools endpoint ─────────────────────────────

    @Test
    void operations_cannotAccess_schools() throws Exception {
        mockMvc.perform(get("/api/v1/schools").with(user(operationsUser)))
               .andExpect(status().isForbidden());
    }

    // ── ACCOUNTANT cannot approve orders ─────────────────────────────────────

    @Test
    void accountant_cannotApprove_orders() throws Exception {
        mockMvc.perform(post("/api/v1/approvals/1/approve")
                .with(user(accountantUser)))
               .andExpect(status().isForbidden());
    }

    // ── Only SUPERADMIN can approve orders ───────────────────────────────────

    @Test
    void superadmin_canApprove_orders() throws Exception {
        // Service layer will 404 for id=999999, but security must allow it through.
        mockMvc.perform(post("/api/v1/approvals/999999/approve")
                .with(user(superadminUser)))
               .andExpect(result -> assertNotEquals(403, result.getResponse().getStatus()));
    }

    // ── ADMIN cannot fulfill firefighting (SUPERADMIN only) ──────────────────

    @Test
    void admin_cannotFulfill_firefighting() throws Exception {
        mockMvc.perform(patch("/api/v1/ff/requests/FF-001/fulfill")
                .with(user(adminUser)))
               .andExpect(status().isForbidden());
    }

    // ── ADMIN can approve firefighting requests ───────────────────────────────

    @Test
    void admin_canApprove_firefighting() throws Exception {
        mockMvc.perform(post("/api/v1/ff/requests/FF-001/approve-bursar")
                .with(user(adminUser))
                .contentType("application/json")
                .content("{}"))
               .andExpect(result -> assertNotEquals(403, result.getResponse().getStatus()));
    }

    // ── SUPERADMIN can access all school management ───────────────────────────

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
        return new AppUserDetails(entity);
    }
}
