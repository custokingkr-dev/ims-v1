package com.custoking.ims.identityservice.api;

import com.custoking.ims.identityservice.persistence.UserDirectoryReadRepository;
import com.custoking.ims.identityservice.security.TenantContext;
import com.custoking.ims.identityservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Broken-access-control regression coverage: user-mutation endpoints (password reset,
 * disable, enable) must require the SUPERADMIN role (not just a valid internal service
 * token), otherwise any authenticated caller could reset/disable/enable any user in any
 * school.
 */
class UserDirectoryAuthorizationTest {

    private static final String VALID_TOKEN = "identity-token";

    private final UserDirectoryReadRepository users = mock(UserDirectoryReadRepository.class);

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new UserDirectoryController(users, VALID_TOKEN))
            .addFilters(new TenantContextFilter())
            .build();

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    // ---- users ----

    @Test
    void users_authenticatedWithUserRead_isAllowed() throws Exception {
        when(users.users(any(), eq(10L), any(), any(), anyInt())).thenReturn(List.of());

        mvc.perform(get("/api/v1/users")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "user:read"))
                .andExpect(status().isOk());

        verify(users).users(any(), eq(10L), any(), any(), anyInt());
    }

    @Test
    void users_authenticatedWithoutUserRead_isForbidden() throws Exception {
        mvc.perform(get("/api/v1/users")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "role:read"))
                .andExpect(status().isForbidden());

        verify(users, never()).users(any(), any(), any(), any(), anyInt());
    }

    // ---- user by id ----

    private static UserDirectoryReadRepository.UserDirectoryRow row(Long branchId, String role) {
        return new UserDirectoryReadRepository.UserDirectoryRow(
                42L, "Someone", "someone@example.test", role, branchId, null, null, null, null, null, null, true);
    }

    @Test
    void user_schoolAdminReadingAPlatformLevelUser_isNotFound() throws Exception {
        // A user with no school (superadmin, zone admin) belongs to no tenant; only superadmin may see them.
        when(users.user(42L)).thenReturn(row(null, "SUPERADMIN"));

        mvc.perform(get("/api/v1/users/42")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "user:read"))
                .andExpect(status().isNotFound());
    }

    @Test
    void user_schoolAdminReadingOwnSchoolUser_isAllowed() throws Exception {
        when(users.user(42L)).thenReturn(row(10L, "ADMIN"));

        mvc.perform(get("/api/v1/users/42")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "user:read"))
                .andExpect(status().isOk());
    }

    @Test
    void user_superadminReadingAPlatformLevelUser_isAllowed() throws Exception {
        when(users.user(42L)).thenReturn(row(null, "ZONE_ADMIN"));

        mvc.perform(get("/api/v1/users/42")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());
    }

    // ---- updateUser ----

    @Test
    void updateUser_nonSuperadmin_isForbidden() throws Exception {
        mvc.perform(patch("/api/v1/users/9")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "user:update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"New Admin\",\"email\":\"new@example.com\"}"))
                .andExpect(status().isForbidden());
        verify(users, never()).updateProfile(anyLong(), any(), any(), any(), any());
    }

    @Test
    void updateUser_superadmin_isAllowed() throws Exception {
        mvc.perform(patch("/api/v1/users/9")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"New Admin\",\"email\":\"new@example.com\"}"))
                .andExpect(status().isOk());
        verify(users).updateProfile(eq(9L), eq("New Admin"), eq("new@example.com"), any(), any());
    }

    // ---- resetPassword ----

    @Test
    void resetPassword_nonSuperadmin_isForbidden() throws Exception {
        mvc.perform(post("/api/v1/users/9/password-reset")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "user:reset_password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"longenough\"}"))
                .andExpect(status().isForbidden());
        verify(users, never()).resetPassword(anyLong(), any(), any(), any());
    }

    @Test
    void resetPassword_superadmin_isAllowed() throws Exception {
        mvc.perform(post("/api/v1/users/9/password-reset")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"longenough\"}"))
                .andExpect(status().isNoContent());
        verify(users).resetPassword(eq(9L), eq("longenough"), any(), any());
    }

    // ---- disableUser ----

    @Test
    void disableUser_nonSuperadmin_isForbidden() throws Exception {
        mvc.perform(post("/api/v1/users/9/disable")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "user:disable"))
                .andExpect(status().isForbidden());
        verify(users, never()).disableUser(anyLong(), any(), any());
    }

    @Test
    void disableUser_superadmin_isAllowed() throws Exception {
        mvc.perform(post("/api/v1/users/9/disable")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isNoContent());
        verify(users).disableUser(eq(9L), any(), any());
    }

    // ---- enableUser ----

    @Test
    void enableUser_nonSuperadmin_isForbidden() throws Exception {
        mvc.perform(post("/api/v1/users/9/enable")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "user:update"))
                .andExpect(status().isForbidden());
        verify(users, never()).enableUser(anyLong(), any(), any());
    }

    @Test
    void enableUser_superadmin_isAllowed() throws Exception {
        mvc.perform(post("/api/v1/users/9/enable")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isNoContent());
        verify(users).enableUser(eq(9L), any(), any());
    }
}
