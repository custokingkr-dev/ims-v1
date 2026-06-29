package com.custoking.ims.identityservice.api;

import com.custoking.ims.identityservice.persistence.RbacCommandRepository;
import com.custoking.ims.identityservice.persistence.RbacReadRepository;
import com.custoking.ims.identityservice.persistence.UserDirectoryReadRepository;
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
            .standaloneSetup(new RbacReadController(rbac, mock(RbacCommandRepository.class), "tok"))
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
                        .header("X-Authenticated-School-Id", "10"))
                .andExpect(status().isForbidden());
        verify(users, never()).users(any(), anyLong(), any(), any(), anyInt());
    }

    @Test
    void omittedBranchId_scopesToAuthenticatedSchool() throws Exception {
        when(users.users(any(), eq(10L), any(), any(), anyInt())).thenReturn(List.of());

        usersMvc.perform(get("/api/v1/users")
                        .header("X-Identity-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10"))
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
                        .header("X-Authenticated-School-Id", "10"))
                .andExpect(status().isForbidden());
        verify(rbac, never()).effectivePermissions(anyLong(), anyLong(), any());
    }

    @Test
    void ownSchoolId_userPermissions_allowed() throws Exception {
        when(rbac.effectivePermissions(eq(9L), eq(10L), isNull())).thenReturn(List.of("STUDENTS_READ"));

        rbacMvc.perform(get("/api/v1/rbac/users/9/permissions?schoolId=10")
                        .header("X-Identity-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10"))
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
}
