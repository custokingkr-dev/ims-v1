package com.custoking.ims.attendanceservice.api;

import com.custoking.ims.attendanceservice.persistence.AttendanceReadRepository;
import com.custoking.ims.attendanceservice.security.TenantContext;
import com.custoking.ims.attendanceservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
    void submitSection_crossTenant_isForbidden() throws Exception {
        mvc.perform(post("/api/v1/attendance/submit-section")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":99,\"classId\":\"cls1\",\"sectionId\":\"sec1\",\"date\":\"2026-01-01\"}")
                        .header("X-Attendance-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10"))
                .andExpect(status().isForbidden());
        verify(repo, never()).submitAttendanceSection(any());
    }

    @Test
    void saveSectionRegister_crossTenant_isForbidden() throws Exception {
        mvc.perform(put("/api/v1/attendance/section-register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":99,\"classId\":\"cls1\",\"sectionId\":\"sec1\",\"date\":\"2026-01-01\",\"records\":[]}")
                        .header("X-Attendance-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10"))
                .andExpect(status().isForbidden());
        verify(repo, never()).saveSectionRegister(any());
    }
}
