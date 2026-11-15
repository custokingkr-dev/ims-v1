package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.FeeReadRepository;
import com.custoking.ims.schoolcoreservice.security.ModuleEntitlementGuard;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TDD: tenant-scope enforcement on FeeReadController.
 * RED: written before TenantScope wiring in FeeReadController.
 * GREEN: after TenantScope.resolveSchoolId() calls are added.
 */
class FeeTenantScopingTest {

    private final FeeReadRepository fees = mock(FeeReadRepository.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new FeeReadController(fees, "fee-tok"))
            .addFilters(new TenantContextFilter())
            .build();

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    // ---- feeReport (GET /reports/collection) ----

    @Test
    void feeReport_crossTenantSchoolId_isForbidden() throws Exception {
        mvc.perform(get("/api/v1/fees/reports/collection")
                        .header("X-Fee-Service-Token", "fee-tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "fee:read")
                        .param("classId", "c1")
                        .param("sectionId", "s1")
                        .param("schoolId", "99"))
                .andExpect(status().isForbidden());
        verify(fees, never()).feeReport(any(), any(), any(), anyLong());
    }

    @Test
    void feeReport_omittedSchoolId_scopesToAuthenticatedSchool() throws Exception {
        when(fees.feeReport(any(), any(), any(), eq(10L))).thenReturn(Map.of("total", 0));
        mvc.perform(get("/api/v1/fees/reports/collection")
                        .header("X-Fee-Service-Token", "fee-tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "fee:read")
                        .param("classId", "c1")
                        .param("sectionId", "s1"))
                .andExpect(status().isOk());
        verify(fees).feeReport(eq("c1"), eq("s1"), any(), eq(10L));
    }

    @Test
    void feeReport_superadmin_canTargetAnySchool() throws Exception {
        when(fees.feeReport(any(), any(), any(), eq(99L))).thenReturn(Map.of("total", 0));
        mvc.perform(get("/api/v1/fees/reports/collection")
                        .header("X-Fee-Service-Token", "fee-tok")
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .param("classId", "c1")
                        .param("sectionId", "s1")
                        .param("schoolId", "99"))
                .andExpect(status().isOk());
        verify(fees).feeReport(eq("c1"), eq("s1"), any(), eq(99L));
    }

    // ---- feeOverdue (GET /reports/overdue) ----

    @Test
    void feeOverdue_crossTenantSchoolId_isForbidden() throws Exception {
        mvc.perform(get("/api/v1/fees/reports/overdue")
                        .header("X-Fee-Service-Token", "fee-tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "fee:read")
                        .param("classId", "c1")
                        .param("sectionId", "s1")
                        .param("schoolId", "99"))
                .andExpect(status().isForbidden());
        verify(fees, never()).feeOverdue(any(), any(), any(), anyLong());
    }

    // ---- feesModule (GET /dashboard/module) ----

    @Test
    void feesModule_crossTenantSchoolId_isForbidden() throws Exception {
        mvc.perform(get("/api/v1/fees/dashboard/module")
                        .header("X-Fee-Service-Token", "fee-tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "fee:read")
                        .param("schoolId", "99"))
                .andExpect(status().isForbidden());
        verify(fees, never()).feesModule(any(), anyLong());
    }

    // ---- feeOverdueCount (GET /dashboard/overdue-count) ----

    @Test
    void feeOverdueCount_crossTenantSchoolId_isForbidden() throws Exception {
        mvc.perform(get("/api/v1/fees/dashboard/overdue-count")
                        .header("X-Fee-Service-Token", "fee-tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "fee:read")
                        .param("schoolId", "99"))
                .andExpect(status().isForbidden());
        verify(fees, never()).feeOverdueCount(any(), anyLong());
    }

    // ---- feeReminderRequests (POST /reminders/fee) — Recipe B ----
    // Parent messaging is not live, so a caller who passes every guard is refused with 409
    // FEE_REMINDERS_UNAVAILABLE. The guards still run first: a caller who may not ask sees 401/403.

    @Test
    void feeReminderRequests_crossTenantSchoolId_isForbidden() throws Exception {
        mvc.perform(post("/api/v1/fees/reminders/fee")
                        .header("X-Fee-Service-Token", "fee-tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "fee:collect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classId\":\"c1\",\"sectionId\":\"s1\",\"schoolId\":99}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(fees);
    }

    @Test
    void feeReminderRequests_invalidToken_isUnauthorized() throws Exception {
        mvc.perform(post("/api/v1/fees/reminders/fee")
                        .header("X-Fee-Service-Token", "wrong")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "fee:collect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classId\":\"c1\",\"sectionId\":\"s1\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(fees);
    }

    @Test
    void feeReminderRequests_withoutCollectOrNotifyPermission_isForbidden() throws Exception {
        mvc.perform(post("/api/v1/fees/reminders/fee")
                        .header("X-Fee-Service-Token", "fee-tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "fee:read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classId\":\"c1\",\"sectionId\":\"s1\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(fees);
    }

    @Test
    void feeReminderRequests_feesModuleOff_isForbiddenForTheResolvedSchool() throws Exception {
        ModuleEntitlementGuard modules = mock(ModuleEntitlementGuard.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "FEES module is not enabled for this school"))
                .when(modules).requireModuleEnabled(10L, "FEES");
        MockMvc guarded = MockMvcBuilders
                .standaloneSetup(new FeeReadController(fees, modules, "fee-tok"))
                .addFilters(new TenantContextFilter())
                .build();

        guarded.perform(post("/api/v1/fees/reminders/fee")
                        .header("X-Fee-Service-Token", "fee-tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "fee:collect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classId\":\"c1\",\"sectionId\":\"s1\"}"))
                .andExpect(status().isForbidden());
        verify(modules).requireModuleEnabled(10L, "FEES");
        verifyNoInteractions(fees);
    }

    @Test
    void feeReminderRequests_ownSchool_isRefusedBecauseMessagingIsOff() throws Exception {
        ModuleEntitlementGuard modules = mock(ModuleEntitlementGuard.class);
        MockMvc guarded = MockMvcBuilders
                .standaloneSetup(new FeeReadController(fees, modules, "fee-tok"))
                .addFilters(new TenantContextFilter())
                .build();

        guarded.perform(post("/api/v1/fees/reminders/fee")
                        .header("X-Fee-Service-Token", "fee-tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "notification:send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classId\":\"c1\",\"sectionId\":\"s1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FEE_REMINDERS_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Fee reminders can't be sent yet: parent messaging isn't switched on."))
                .andExpect(jsonPath("$.queued").doesNotExist());
        // The omitted schoolId resolved to the caller's school before the module check.
        verify(modules).requireModuleEnabled(10L, "FEES");
        verifyNoInteractions(fees);
    }

    @Test
    void feeReminderRequests_superadmin_isRefusedForAnySchool() throws Exception {
        mvc.perform(post("/api/v1/fees/reminders/fee")
                        .header("X-Fee-Service-Token", "fee-tok")
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classId\":\"c1\",\"sectionId\":\"s1\",\"schoolId\":99}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FEE_REMINDERS_UNAVAILABLE"));
        verifyNoInteractions(fees);
    }
}
