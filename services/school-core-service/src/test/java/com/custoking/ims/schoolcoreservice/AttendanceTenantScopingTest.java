package com.custoking.ims.schoolcoreservice;

import com.custoking.ims.schoolcoreservice.api.AttendanceReadController;
import com.custoking.ims.schoolcoreservice.persistence.AttendanceReadRepository;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AttendanceTenantScopingTest {

    private final AttendanceReadRepository repo = mock(AttendanceReadRepository.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new AttendanceReadController(repo, "tok"))
            .addFilters(new TenantContextFilter())
            .build();

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void crossTenantSchoolId_isForbidden() throws Exception {
        mvc.perform(get("/api/v1/attendance/records?schoolId=99")
                        .header("X-Attendance-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "attendance:read"))
                .andExpect(status().isForbidden());
        verify(repo, never()).records(any(), anyLong(), any(), anyInt());
    }

    @Test
    void omittedSchoolId_scopesToAuthenticatedSchool() throws Exception {
        when(repo.records(any(), eq(10L), any(), anyInt())).thenReturn(List.of());
        mvc.perform(get("/api/v1/attendance/records")
                        .header("X-Attendance-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "attendance:read"))
                .andExpect(status().isOk());
        verify(repo).records(any(), eq(10L), any(), anyInt());
    }

    @Test
    void superadmin_canTargetAnySchool() throws Exception {
        when(repo.records(any(), eq(99L), any(), anyInt())).thenReturn(List.of());
        mvc.perform(get("/api/v1/attendance/records?schoolId=99")
                        .header("X-Attendance-Service-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());
        verify(repo).records(any(), eq(99L), any(), anyInt());
    }
}
