package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.ModuleEntitlementReadRepository;
import com.custoking.ims.schoolcoreservice.persistence.SchoolRepository;
import com.custoking.ims.schoolcoreservice.persistence.SchoolStructureReadRepository;
import com.custoking.ims.schoolcoreservice.persistence.ZoneCommandRepository;
import com.custoking.ims.schoolcoreservice.persistence.ZoneRepository;
import com.custoking.ims.schoolcoreservice.security.ModuleEntitlementGuard;
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

class TenantSchoolTenantScopingTest {

    private final SchoolRepository schools = mock(SchoolRepository.class);
    private final ZoneRepository zones = mock(ZoneRepository.class);
    private final ModuleEntitlementReadRepository modules = mock(ModuleEntitlementReadRepository.class);
    private final ModuleEntitlementGuard moduleGuard = mock(ModuleEntitlementGuard.class);
    private final SchoolStructureReadRepository structure = mock(SchoolStructureReadRepository.class);
    private final ZoneCommandRepository zoneCommands = mock(ZoneCommandRepository.class);

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new TenantSchoolController(schools, zones, modules, moduleGuard, structure, zoneCommands, "tok"))
            .addFilters(new TenantContextFilter())
            .build();

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    // --- Full school list is superadmin-only ---

    @Test
    void nonSuperadmin_getSchools_isForbidden() throws Exception {
        mvc.perform(get("/api/v1/schools")
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10"))
                .andExpect(status().isForbidden());
        verify(schools, never()).findAllByOrderByNameAsc();
    }

    @Test
    void superadmin_getSchools_isAllowed() throws Exception {
        when(schools.findAllByOrderByNameAsc()).thenReturn(List.of());

        mvc.perform(get("/api/v1/schools")
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());
        verify(schools).findAllByOrderByNameAsc();
    }

    // --- Cross-tenant sections read ---

    @Test
    void crossTenantSchoolId_getSections_isForbidden() throws Exception {
        mvc.perform(get("/api/v1/sections?schoolId=99")
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10"))
                .andExpect(status().isForbidden());
        verify(structure, never()).sections(anyLong(), any(), any());
    }

    @Test
    void ownSchoolId_getSections_isAllowed() throws Exception {
        when(structure.sections(eq(10L), any(), any())).thenReturn(List.of());

        mvc.perform(get("/api/v1/sections?schoolId=10")
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10"))
                .andExpect(status().isOk());
        verify(structure).sections(eq(10L), any(), any());
    }

    @Test
    void superadmin_getSectionsForAnySchool_isAllowed() throws Exception {
        when(structure.sections(eq(99L), any(), any())).thenReturn(List.of());

        mvc.perform(get("/api/v1/sections?schoolId=99")
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());
        verify(structure).sections(eq(99L), any(), any());
    }

    @Test
    void ownSchool_getAcademicYears_usesAuthenticatedSchoolScope() throws Exception {
        when(structure.academicYears(eq(10L), isNull(Boolean.class))).thenReturn(List.of());

        mvc.perform(get("/api/v1/academic-years")
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10"))
                .andExpect(status().isOk());
        verify(structure).academicYears(eq(10L), isNull(Boolean.class));
    }

    @Test
    void crossTenantSchoolId_getAcademicYears_isForbidden() throws Exception {
        mvc.perform(get("/api/v1/academic-years?schoolId=99")
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10"))
                .andExpect(status().isForbidden());
        verify(structure, never()).academicYears(any(), any());
    }

    @Test
    void superadmin_getAcademicYearsWithoutSchoolId_keepsGlobalScope() throws Exception {
        when(structure.academicYears(isNull(Long.class), isNull(Boolean.class))).thenReturn(List.of());

        mvc.perform(get("/api/v1/academic-years")
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());
        verify(structure).academicYears(isNull(Long.class), isNull(Boolean.class));
    }
}
