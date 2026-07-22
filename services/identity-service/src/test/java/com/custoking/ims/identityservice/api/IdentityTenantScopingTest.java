package com.custoking.ims.identityservice.api;

import com.custoking.ims.identityservice.infrastructure.TenantSchoolClient;
import com.custoking.ims.identityservice.persistence.RbacCommandRepository;
import com.custoking.ims.identityservice.persistence.RbacReadRepository;
import com.custoking.ims.identityservice.persistence.UserDirectoryReadRepository;
import com.custoking.ims.identityservice.persistence.UserDirectoryReadRepository.UserDirectoryRow;
import com.custoking.ims.identityservice.security.TenantContext;
import com.custoking.ims.identityservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IdentityTenantScopingTest {

    private final UserDirectoryReadRepository users = mock(UserDirectoryReadRepository.class);
    private final RbacReadRepository rbac = mock(RbacReadRepository.class);

    private final MockMvc usersMvc = MockMvcBuilders
            .standaloneSetup(new UserDirectoryController(users, "tok"))
            .addFilters(new TenantContextFilter())
            .build();

    private final MockMvc rbacMvc = MockMvcBuilders
            .standaloneSetup(new RbacReadController(rbac, mock(RbacCommandRepository.class), mock(TenantSchoolClient.class), "tok"))
            .addFilters(new TenantContextFilter())
            .build();

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    // --- UserDirectoryController ---

    @Test
    void crossTenantBranchId_isForbidden() throws Exception {
        usersMvc.perform(get("/api/v1/users?branchId=99")
                        .header("X-Identity-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "user:read"))
                .andExpect(status().isForbidden());
        verify(users, never()).users(any(), anyLong(), any(), any(), anyInt());
    }

    @Test
    void omittedBranchId_scopesToAuthenticatedSchool() throws Exception {
        when(users.users(any(), eq(10L), any(), any(), anyInt())).thenReturn(List.of());

        usersMvc.perform(get("/api/v1/users")
                        .header("X-Identity-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "user:read"))
                .andExpect(status().isOk());
        verify(users).users(any(), eq(10L), any(), any(), anyInt());
    }

    @Test
    void superadmin_canTargetAnyBranchId() throws Exception {
        when(users.users(any(), eq(99L), any(), any(), anyInt())).thenReturn(List.of());

        usersMvc.perform(get("/api/v1/users?branchId=99")
                        .header("X-Identity-Service-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());
        verify(users).users(any(), eq(99L), any(), any(), anyInt());
    }

    // --- RbacReadController.userPermissions ---

    @Test
    void crossTenantSchoolId_userPermissions_isForbidden() throws Exception {
        rbacMvc.perform(get("/api/v1/rbac/users/9/permissions?schoolId=99")
                        .header("X-Identity-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "permission:read"))
                .andExpect(status().isForbidden());
        verify(rbac, never()).effectivePermissions(anyLong(), anyLong(), any());
    }

    @Test
    void ownSchoolId_userPermissions_allowed() throws Exception {
        when(rbac.effectivePermissions(eq(9L), eq(10L), isNull())).thenReturn(List.of("STUDENTS_READ"));

        rbacMvc.perform(get("/api/v1/rbac/users/9/permissions?schoolId=10")
                        .header("X-Identity-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "permission:read"))
                .andExpect(status().isOk());
        verify(rbac).effectivePermissions(eq(9L), eq(10L), isNull());
    }

    @Test
    void superadmin_canQueryAnySchoolPermissions() throws Exception {
        when(rbac.effectivePermissions(eq(9L), eq(99L), isNull())).thenReturn(List.of());

        rbacMvc.perform(get("/api/v1/rbac/users/9/permissions?schoolId=99")
                        .header("X-Identity-Service-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());
        verify(rbac).effectivePermissions(eq(9L), eq(99L), isNull());
    }

    // --- FIX 1: zoneId cross-zone leak in userPermissions ---

    @Test
    void crossZoneId_userPermissions_isForbidden() throws Exception {
        // ADMIN authenticated with zone=null passing zoneId=99 → 403
        rbacMvc.perform(get("/api/v1/rbac/users/9/permissions?zoneId=99")
                        .header("X-Identity-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "permission:read"))
                .andExpect(status().isForbidden());
        verify(rbac, never()).effectivePermissions(anyLong(), any(), any());
    }

    @Test
    void superadmin_canPassAnyZoneId_userPermissions() throws Exception {
        when(rbac.effectivePermissions(eq(9L), isNull(), eq(99L))).thenReturn(List.of());

        rbacMvc.perform(get("/api/v1/rbac/users/9/permissions?zoneId=99")
                        .header("X-Identity-Service-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());
        verify(rbac).effectivePermissions(eq(9L), isNull(), eq(99L));
    }

    // --- FIX 2: GET /users/{id} cross-tenant PII read ---

    @Test
    void getUserById_crossTenantBranchId_isForbidden() throws Exception {
        // User belongs to school 99; ADMIN authenticated for school 10 → 403
        UserDirectoryRow foreignUser = new UserDirectoryRow(
                7L, "Other", "other@school.com", "ADMIN", 99L, "School99",
                null, null, null, null, null, true);
        when(users.user(7L)).thenReturn(foreignUser);

        usersMvc.perform(get("/api/v1/users/7")
                        .header("X-Identity-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "user:read"))
                .andExpect(status().isForbidden());
        verify(users).user(7L);
    }

    @Test
    void getUserById_sameSchool_isAllowed() throws Exception {
        UserDirectoryRow ownUser = new UserDirectoryRow(
                8L, "Own", "own@school.com", "TEACHER", 10L, "School10",
                null, null, null, null, null, true);
        when(users.user(8L)).thenReturn(ownUser);

        usersMvc.perform(get("/api/v1/users/8")
                        .header("X-Identity-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "user:read"))
                .andExpect(status().isOk());
        verify(users).user(8L);
    }

    @Test
    void superadmin_getUserById_anySchool_isAllowed() throws Exception {
        UserDirectoryRow anyUser = new UserDirectoryRow(
                9L, "Remote", "r@school.com", "ADMIN", 99L, "School99",
                null, null, null, null, null, true);
        when(users.user(9L)).thenReturn(anyUser);

        usersMvc.perform(get("/api/v1/users/9")
                        .header("X-Identity-Service-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());
        verify(users).user(9L);
    }
}
