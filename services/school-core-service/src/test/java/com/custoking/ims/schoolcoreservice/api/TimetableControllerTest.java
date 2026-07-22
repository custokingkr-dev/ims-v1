package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.TimetableRepository;
import com.custoking.ims.schoolcoreservice.persistence.YearLockedException;
import com.custoking.ims.schoolcoreservice.security.ModuleEntitlementGuard;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TimetableControllerTest {

    private final TimetableRepository timetable = mock(TimetableRepository.class);
    private final ModuleEntitlementGuard moduleGuard = mock(ModuleEntitlementGuard.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new TimetableController(timetable, moduleGuard, "tok"))
            .addFilters(new TenantContextFilter())
            .build();

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    private static final String BULK_URL = "/api/v1/timetable/entries/bulk";

    @Test
    void bulkUpsert_schoolAdmin_upsertsAllEntriesAndReturnsGrid() throws Exception {
        Map<String, Object> grid = Map.of("sectionId", "sec-1", "entries", List.of("e1", "e2", "e3"));
        when(timetable.upsertEntries(eq(10L), eq("sec-1"), anyList())).thenReturn(grid);

        mvc.perform(put(BULK_URL)
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "timetable:manage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sectionId":"sec-1","entries":[
                                  {"day":"Mon","periodId":1,"subjectName":"Math"},
                                  {"day":"Tue","periodId":1,"subjectName":"Math"},
                                  {"day":"Wed","periodId":1,"subjectName":"Math","teacherId":5}
                                ]}
                                """))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(timetable).upsertEntries(eq(10L), eq("sec-1"), captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue()).hasSize(3);
    }

    @Test
    void bulkUpsert_nonSchoolAdmin_isForbidden() throws Exception {
        mvc.perform(put(BULK_URL)
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "TEACHER")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "timetable:manage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sectionId":"sec-1","entries":[
                                  {"day":"Mon","periodId":1,"subjectName":"Math"}
                                ]}
                                """))
                .andExpect(status().isForbidden());
        verify(timetable, never()).upsertEntries(anyLong(), anyString(), anyList());
    }

    @Test
    void bulkUpsert_schoolAdminWithoutErp_isForbidden() throws Exception {
        doThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN, "ERP module is not enabled for this school"))
                .when(moduleGuard).requireErpEnabled(10L);

        mvc.perform(put(BULK_URL)
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "timetable:manage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sectionId":"sec-1","entries":[
                                  {"day":"Mon","periodId":1,"subjectName":"Math"}
                                ]}
                                """))
                .andExpect(status().isForbidden());

        verify(timetable, never()).upsertEntries(anyLong(), anyString(), anyList());
    }

    @Test
    void bulkUpsert_lockedYear_returns409() throws Exception {
        when(timetable.upsertEntries(eq(10L), eq("sec-1"), anyList()))
                .thenThrow(new YearLockedException("No active academic year configured"));

        mvc.perform(put(BULK_URL)
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "timetable:manage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sectionId":"sec-1","entries":[
                                  {"day":"Mon","periodId":1,"subjectName":"Math"}
                                ]}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void bulkUpsert_rowMissingSubjectName_returns400() throws Exception {
        mvc.perform(put(BULK_URL)
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "timetable:manage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sectionId":"sec-1","entries":[
                                  {"day":"Mon","periodId":1}
                                ]}
                                """))
                .andExpect(status().isBadRequest());
        verify(timetable, never()).upsertEntries(anyLong(), anyString(), anyList());
    }

    @Test
    void bulkUpsert_rowMissingPeriodId_returns400() throws Exception {
        mvc.perform(put(BULK_URL)
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "timetable:manage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sectionId":"sec-1","entries":[
                                  {"day":"Mon","subjectName":"Math"}
                                ]}
                                """))
                .andExpect(status().isBadRequest());
        verify(timetable, never()).upsertEntries(anyLong(), anyString(), anyList());
    }

    @Test
    void bulkUpsert_schoolAdminWithoutManagePermission_isForbidden() throws Exception {
        mvc.perform(put(BULK_URL)
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "timetable:read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sectionId":"sec-1","entries":[
                                  {"day":"Mon","periodId":1,"subjectName":"Math"}
                                ]}
                                """))
                .andExpect(status().isForbidden());
        verify(timetable, never()).upsertEntries(anyLong(), anyString(), anyList());
    }
}
