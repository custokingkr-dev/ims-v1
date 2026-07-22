package com.custoking.ims.schoolcoreservice.api.compat;

import com.custoking.ims.schoolcoreservice.persistence.FeeReadRepository;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FeePublicCompatibilityControllerTest {

    private final FeeReadRepository fees = mock(FeeReadRepository.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new FeePublicCompatibilityController(fees, "tok"))
            .addFilters(new TenantContextFilter())
            .build();

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    // ---- feesReceiptsPdfAliasDelegatesToPaymentIdLookup (pre-existing, no schoolId - unchanged) ----

    @Test
    void feesReceiptsPdfAliasDelegatesToPaymentIdLookup() {
        when(fees.receiptPdfByPaymentId("PMT-1")).thenReturn(new byte[]{1, 2, 3});
        // No TenantContext needed - this endpoint has no schoolId guard
        TenantContext.set(new TenantContext(1L, "s@x", "SUPERADMIN", null, null));
        var controller = new FeePublicCompatibilityController(fees, "tok");
        ResponseEntity<byte[]> res = controller.receiptByPaymentIdPdf("tok", "PMT-1");
        assertThat(res.getBody()).containsExactly(1, 2, 3);
    }

    // ---- feeReport GET /api/v1/fees/report ----

    @Test
    void feeReport_crossTenantSchoolId_isForbidden() throws Exception {
        mvc.perform(get("/api/v1/fees/report")
                        .header("X-Fee-Service-Token", "tok")
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
    void feeReport_ownSchool_scopedToAuthenticatedSchool() throws Exception {
        when(fees.feeReport(eq("c1"), eq("s1"), any(), eq(10L))).thenReturn(Map.of("content", "data"));
        mvc.perform(get("/api/v1/fees/report")
                        .header("X-Fee-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "fee:read")
                        .param("classId", "c1")
                        .param("sectionId", "s1")
                        .param("schoolId", "10"))
                .andExpect(status().isOk());
        verify(fees).feeReport(eq("c1"), eq("s1"), any(), eq(10L));
    }

    @Test
    void feeReport_superadmin_canTargetAnySchool() throws Exception {
        when(fees.feeReport(eq("c1"), eq("s1"), any(), eq(99L))).thenReturn(Map.of("content", "data"));
        mvc.perform(get("/api/v1/fees/report")
                        .header("X-Fee-Service-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .param("classId", "c1")
                        .param("sectionId", "s1")
                        .param("schoolId", "99"))
                .andExpect(status().isOk());
        verify(fees).feeReport(eq("c1"), eq("s1"), any(), eq(99L));
    }

    // ---- feeOverdue GET /api/v1/fees/overdue ----

    @Test
    void feeOverdue_crossTenantSchoolId_isForbidden() throws Exception {
        mvc.perform(get("/api/v1/fees/overdue")
                        .header("X-Fee-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "fee:read")
                        .param("classId", "c1")
                        .param("sectionId", "s1")
                        .param("schoolId", "99"))
                .andExpect(status().isForbidden());
        verify(fees, never()).feeOverdue(any(), any(), any(), anyLong());
    }

    // ---- sendReminders POST /api/v1/fees/send-reminders ----

    @Test
    void sendReminders_crossTenantSchoolId_isForbidden() throws Exception {
        mvc.perform(post("/api/v1/fees/send-reminders")
                        .header("X-Fee-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "fee:collect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classId\":\"c1\",\"sectionId\":\"s1\",\"schoolId\":99}"))
                .andExpect(status().isForbidden());
        verify(fees, never()).feeReminderRequests(any(), any(), any(), anyLong(), any());
    }

    @Test
    void sendReminders_ownSchool_scopedToAuthenticatedSchool() throws Exception {
        when(fees.feeReminderRequests(any(), any(), any(), eq(10L), any()))
                .thenReturn(Map.of("queued", 0));
        mvc.perform(post("/api/v1/fees/send-reminders")
                        .header("X-Fee-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "fee:collect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classId\":\"c1\",\"sectionId\":\"s1\",\"schoolId\":10}"))
                .andExpect(status().isOk());
        verify(fees).feeReminderRequests(eq("c1"), eq("s1"), any(), eq(10L), any());
    }

    // ---- dashboardFeeReminders POST /api/v1/dashboard/finance/fee-defaulters/reminders ----

    @Test
    void dashboardFeeReminders_crossTenantSchoolId_isForbidden() throws Exception {
        mvc.perform(post("/api/v1/dashboard/finance/fee-defaulters/reminders")
                        .header("X-Fee-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "fee:collect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":99}"))
                .andExpect(status().isForbidden());
        verify(fees, never()).feeReminderRequests(any(), any(), any(), anyLong(), any());
    }

    @Test
    void dashboardFeeReminders_superadmin_delegatesWithSchoolId() throws Exception {
        when(fees.feeReminderRequests(any(), any(), any(), eq(1L), any()))
                .thenReturn(Map.of("queued", 3));
        mvc.perform(post("/api/v1/dashboard/finance/fee-defaulters/reminders")
                        .header("X-Fee-Service-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":1}"))
                .andExpect(status().isOk());
        verify(fees).feeReminderRequests(any(), any(), any(), eq(1L), any());
    }

    @Test
    void dashboardFeeRemindersRejectInvalidToken() throws Exception {
        mvc.perform(post("/api/v1/dashboard/finance/fee-defaulters/reminders")
                        .header("X-Fee-Service-Token", "bad")
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":1}"))
                .andExpect(status().isUnauthorized());
        verify(fees, never()).feeReminderRequests(any(), any(), any(), any(), any());
    }
}
