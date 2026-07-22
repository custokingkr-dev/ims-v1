package com.custoking.ims.identityservice.api;

import com.custoking.ims.identityservice.infrastructure.TenantSchoolClient;
import com.custoking.ims.identityservice.persistence.RbacCommandRepository;
import com.custoking.ims.identityservice.persistence.RbacReadRepository;
import com.custoking.ims.identityservice.security.TenantContext;
import com.custoking.ims.identityservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Broken-access-control regression coverage: RBAC mutation endpoints must require the
 * SUPERADMIN role (not just a valid internal service token), otherwise any authenticated
 * caller could self-grant platform/school/zone roles.
 */
class RbacAuthorizationTest {

    private static final String VALID_TOKEN = "identity-token";

    private final RbacReadRepository reads = mock(RbacReadRepository.class);
    private final RbacCommandRepository commands = mock(RbacCommandRepository.class);
    private final TenantSchoolClient schoolClient = mock(TenantSchoolClient.class);

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new RbacReadController(reads, commands, schoolClient, VALID_TOKEN))
            .addFilters(new TenantContextFilter())
            .build();

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    // ---- createRole ----

    @Test
    void createRole_nonSuperadmin_isForbidden() throws Exception {
        mvc.perform(post("/api/v1/rbac/roles")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "role:create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"AUDITOR\"}"))
                .andExpect(status().isForbidden());
        verify(commands, never()).createRole(anyMap());
    }

    @Test
    void createRole_superadmin_isAllowed() throws Exception {
        when(commands.createRole(anyMap())).thenReturn(Map.of("id", 1));
        mvc.perform(post("/api/v1/rbac/roles")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"AUDITOR\"}"))
                .andExpect(status().isCreated());
        verify(commands).createRole(anyMap());
    }

    // ---- assignPlatformRole ----

    @Test
    void assignPlatformRole_nonSuperadmin_isForbidden() throws Exception {
        mvc.perform(post("/api/v1/rbac/users/9/roles/platform")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "role:assign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"AUDITOR\"}"))
                .andExpect(status().isForbidden());
        verify(commands, never()).assignPlatformRole(anyLong(), anyMap());
    }

    @Test
    void assignPlatformRole_superadmin_isAllowed() throws Exception {
        when(commands.assignPlatformRole(anyLong(), anyMap())).thenReturn(Map.of("id", 20));
        mvc.perform(post("/api/v1/rbac/users/9/roles/platform")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"AUDITOR\"}"))
                .andExpect(status().isOk());
        verify(commands).assignPlatformRole(eq(9L), anyMap());
    }

    // ---- revokeAssignment ----

    @Test
    void revokeAssignment_nonSuperadmin_isForbidden() throws Exception {
        mvc.perform(delete("/api/v1/rbac/users/9/roles/5")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "role:revoke"))
                .andExpect(status().isForbidden());
        verify(commands, never()).revokeAssignment(anyLong(), anyLong(), anyMap());
    }

    @Test
    void revokeAssignment_superadmin_isAllowed() throws Exception {
        mvc.perform(delete("/api/v1/rbac/users/9/roles/5")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());
        verify(commands).revokeAssignment(eq(9L), eq(5L), anyMap());
    }

    // ---- userRoleAssignments (cross-school read) ----

    @Test
    void userRoleAssignments_nonSuperadmin_isForbidden() throws Exception {
        mvc.perform(get("/api/v1/rbac/user-role-assignments")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "role:read"))
                .andExpect(status().isForbidden());
        verify(reads, never()).userAssignments(any(), any(), anyInt());
    }

    @Test
    void userRoleAssignments_superadmin_isAllowed() throws Exception {
        when(reads.userAssignments(any(), any(), anyInt())).thenReturn(List.of());
        mvc.perform(get("/api/v1/rbac/user-role-assignments")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());
        verify(reads).userAssignments(any(), any(), anyInt());
    }

    // ---- users/{id}/roles (same unscoped cross-school assignments as user-role-assignments) ----

    @Test
    void userRoles_nonSuperadmin_isForbidden() throws Exception {
        mvc.perform(get("/api/v1/rbac/users/42/roles")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "role:read"))
                .andExpect(status().isForbidden());
        verify(reads, never()).userAssignments(any(), any(), anyInt());
    }

    @Test
    void userRoles_superadmin_isAllowed() throws Exception {
        when(reads.userAssignments(any(), any(), anyInt())).thenReturn(List.of());
        mvc.perform(get("/api/v1/rbac/users/42/roles")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());
        verify(reads).userAssignments(any(), any(), anyInt());
    }

    // ---- audit (cross-school read) ----

    @Test
    void audit_nonSuperadmin_isForbidden() throws Exception {
        mvc.perform(get("/api/v1/rbac/audit")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "audit:read"))
                .andExpect(status().isForbidden());
        verify(reads, never()).audit(any(), any(), anyInt());
    }

    @Test
    void audit_superadmin_isAllowed() throws Exception {
        when(reads.audit(any(), any(), anyInt())).thenReturn(List.of());
        mvc.perform(get("/api/v1/rbac/audit")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());
        verify(reads).audit(any(), any(), anyInt());
    }

    // ---- operator-schools (sync) ----

    @Test
    void syncOperatorSchools_nonSuperadmin_isForbidden() throws Exception {
        mvc.perform(post("/api/v1/rbac/users/9/operator-schools")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "role:assign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolIds\":[1,2]}"))
                .andExpect(status().isForbidden());
        verify(commands, never()).syncOperatorSchools(anyLong(), any(), any());
    }

    @Test
    void syncOperatorSchools_superadmin_isAllowed() throws Exception {
        when(commands.syncOperatorSchools(anyLong(), any(), any())).thenReturn(Map.of("userId", 9L, "schoolIds", List.of(1L, 2L)));
        mvc.perform(post("/api/v1/rbac/users/9/operator-schools")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolIds\":[1,2]}"))
                .andExpect(status().isOk());
        verify(commands).syncOperatorSchools(eq(9L), eq(List.of(1L, 2L)), any());
    }

    // ---- operator-schools (read) ----

    @Test
    void operatorSchools_nonSuperadmin_isForbidden() throws Exception {
        mvc.perform(get("/api/v1/rbac/users/9/operator-schools")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "role:read"))
                .andExpect(status().isForbidden());
        verify(reads, never()).operatorSchoolIds(anyLong());
    }

    @Test
    void operatorSchools_superadmin_isAllowed() throws Exception {
        when(reads.operatorSchoolIds(9L)).thenReturn(List.of(1L, 2L));
        when(schoolClient.school(1L)).thenReturn(new TenantSchoolClient.TenantSchoolRef(1L, "School One"));
        when(schoolClient.school(2L)).thenReturn(new TenantSchoolClient.TenantSchoolRef(2L, "School Two"));
        mvc.perform(get("/api/v1/rbac/users/9/operator-schools")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].schoolId").value(1))
                .andExpect(jsonPath("$[0].schoolName").value("School One"))
                .andExpect(jsonPath("$[1].schoolId").value(2));
        verify(reads).operatorSchoolIds(9L);
    }

    // ---- roles (untouched read) — proves the guard is narrow ----

    @Test
    void roles_nonSuperadminWithRoleRead_isAllowed() throws Exception {
        when(reads.roles()).thenReturn(List.of());
        mvc.perform(get("/api/v1/rbac/roles")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "role:read"))
                .andExpect(status().isOk());
        verify(reads).roles();
    }

    @Test
    void roles_authenticatedWithoutRoleRead_isForbidden() throws Exception {
        mvc.perform(get("/api/v1/rbac/roles")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "user:read"))
                .andExpect(status().isForbidden());
        verify(reads, never()).roles();
    }

    @Test
    void userPermissions_authenticatedWithoutPermissionReadOrRoleRead_isForbidden() throws Exception {
        mvc.perform(get("/api/v1/rbac/users/9/permissions?schoolId=10")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "user:read"))
                .andExpect(status().isForbidden());
        verify(reads, never()).effectivePermissions(anyLong(), any(), any());
    }
}
