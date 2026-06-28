package com.custoking.ims.feeservice.api;

import com.custoking.ims.feeservice.persistence.FeeReadRepository;
import com.custoking.ims.feeservice.security.TenantContext;
import com.custoking.ims.feeservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
                        .param("schoolId", "99"))
                .andExpect(status().isForbidden());
        verify(fees, never()).feeOverdueCount(any(), anyLong());
    }

    // ---- feeReminderRequests (POST /reminders/fee) — Recipe B ----

    @Test
    void feeReminderRequests_crossTenantSchoolId_isForbidden() throws Exception {
        mvc.perform(post("/api/v1/fees/reminders/fee")
                        .header("X-Fee-Service-Token", "fee-tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classId\":\"c1\",\"sectionId\":\"s1\",\"schoolId\":99}"))
                .andExpect(status().isForbidden());
        verify(fees, never()).feeReminderRequests(any(), any(), any(), anyLong(), any());
    }

    @Test
    void feeReminderRequests_omittedSchoolId_scopesToAuthenticatedSchool() throws Exception {
        when(fees.feeReminderRequests(any(), any(), any(), eq(10L), any()))
                .thenReturn(Map.of("queued", 0));
        mvc.perform(post("/api/v1/fees/reminders/fee")
                        .header("X-Fee-Service-Token", "fee-tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classId\":\"c1\",\"sectionId\":\"s1\"}"))
                .andExpect(status().isOk());
        verify(fees).feeReminderRequests(eq("c1"), eq("s1"), any(), eq(10L), any());
    }
}
