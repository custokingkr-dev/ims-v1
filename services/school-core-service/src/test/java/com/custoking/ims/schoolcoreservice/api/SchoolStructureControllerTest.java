package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.SchoolRepository;
import com.custoking.ims.schoolcoreservice.persistence.SchoolStructureReadRepository;
import com.custoking.ims.schoolcoreservice.persistence.StructureInUseException;
import com.custoking.ims.schoolcoreservice.persistence.ModuleEntitlementReadRepository;
import com.custoking.ims.schoolcoreservice.persistence.ZoneCommandRepository;
import com.custoking.ims.schoolcoreservice.persistence.ZoneRepository;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SchoolStructureControllerTest {

    private final SchoolStructureReadRepository structure = mock(SchoolStructureReadRepository.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new TenantSchoolController(
                    mock(SchoolRepository.class),
                    mock(ZoneRepository.class),
                    mock(ModuleEntitlementReadRepository.class),
                    structure,
                    mock(ZoneCommandRepository.class),
                    "tok"))
            .addFilters(new TenantContextFilter())
            .build();

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void superadmin_editsAnySchool() throws Exception {
        when(structure.updateStructure(7L, 5, 2)).thenReturn(Map.of("id", 7L));
        mvc.perform(put("/api/v1/schools/7/structure")
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classCount\":5,\"sectionCount\":2}"))
                .andExpect(status().isOk());
        verify(structure).updateStructure(7L, 5, 2);
    }

    @Test
    void schoolAdmin_editingOwnSchool_isAllowed() throws Exception {
        when(structure.updateStructure(10L, 6, 3)).thenReturn(Map.of("id", 10L));
        mvc.perform(put("/api/v1/schools/10/structure")
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classCount\":6,\"sectionCount\":3}"))
                .andExpect(status().isOk());
        verify(structure).updateStructure(10L, 6, 3);
    }

    @Test
    void schoolAdminRole_SCHOOL_ADMIN_editingOwnSchool_isAllowed() throws Exception {
        when(structure.updateStructure(10L, 6, 3)).thenReturn(Map.of("id", 10L));
        mvc.perform(put("/api/v1/schools/10/structure")
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "SCHOOL_ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classCount\":6,\"sectionCount\":3}"))
                .andExpect(status().isOk());
        verify(structure).updateStructure(10L, 6, 3);
    }

    @Test
    void schoolAdmin_editingAnotherSchool_isForbidden() throws Exception {
        mvc.perform(put("/api/v1/schools/99/structure")
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classCount\":6,\"sectionCount\":3}"))
                .andExpect(status().isForbidden());
        verify(structure, never()).updateStructure(anyLong(), anyInt(), anyInt());
    }

    @Test
    void nonAdminSchoolUser_isForbidden() throws Exception {
        mvc.perform(put("/api/v1/schools/10/structure")
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "TEACHER")
                        .header("X-Authenticated-School-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classCount\":6,\"sectionCount\":3}"))
                .andExpect(status().isForbidden());
        verify(structure, never()).updateStructure(anyLong(), anyInt(), anyInt());
    }

    @Test
    void classCountOutOfRange_returns400() throws Exception {
        mvc.perform(put("/api/v1/schools/7/structure")
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classCount\":13,\"sectionCount\":2}"))
                .andExpect(status().isBadRequest());
        verify(structure, never()).updateStructure(anyLong(), anyInt(), anyInt());
    }

    @Test
    void inUseShrink_returns409() throws Exception {
        when(structure.updateStructure(7L, 2, 2))
                .thenThrow(new StructureInUseException("Cannot reduce classes to 2: class '8' has 3 student(s)"));
        mvc.perform(put("/api/v1/schools/7/structure")
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classCount\":2,\"sectionCount\":2}"))
                .andExpect(status().isConflict());
    }

    @Test
    void sectionsEndpoint_defaultsToActiveOnly_whenActiveOmitted() throws Exception {
        when(structure.sections(99L, null, Boolean.TRUE)).thenReturn(java.util.List.of());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/sections?schoolId=99")
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());
        verify(structure).sections(99L, null, Boolean.TRUE);
    }

    @Test
    void sectionsEndpoint_honoursExplicitActiveFalse() throws Exception {
        when(structure.sections(99L, null, Boolean.FALSE)).thenReturn(java.util.List.of());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/sections?schoolId=99&active=false")
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());
        verify(structure).sections(99L, null, Boolean.FALSE);
    }
}
