package com.custoking.ims.identityservice.api;

import com.custoking.ims.identityservice.api.dto.AssignSchoolRoleRequest;
import com.custoking.ims.identityservice.api.dto.PasswordResetRequest;
import com.custoking.ims.identityservice.application.IdentityAuthService;
import com.custoking.ims.identityservice.application.IdentityAuthService.AuthResponse;
import com.custoking.ims.identityservice.application.IdentityAuthService.IntrospectionResponse;
import com.custoking.ims.identityservice.application.IdentityAuthService.LoginRequest;
import com.custoking.ims.identityservice.application.IdentityAuthService.LoginResult;
import com.custoking.ims.identityservice.infrastructure.TenantSchoolClient;
import com.custoking.ims.identityservice.persistence.IdentityUserProvisioningRepository;
import com.custoking.ims.identityservice.persistence.RbacCommandRepository;
import com.custoking.ims.identityservice.persistence.RbacReadRepository;
import com.custoking.ims.identityservice.persistence.UserDirectoryReadRepository;
import com.custoking.ims.identityservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdentityControllersTest {

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void loginDelegatesAndSetsRefreshCookie() {
        IdentityAuthService authService = mock(IdentityAuthService.class);
        AuthController controller = new AuthController(authService, true, "None", 60_000L, "identity-token");
        LoginRequest request = new LoginRequest("admin@custoking.com", "secret");
        AuthResponse authResponse = authResponse("access-token");
        when(authService.login(request)).thenReturn(new LoginResult("refresh-token", authResponse));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        AuthResponse response = controller.login(request, servletResponse);

        assertThat(response).isSameAs(authResponse);
        assertThat(servletResponse.getHeader(HttpHeaders.SET_COOKIE))
                .contains("refresh_token=refresh-token")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=None")
                .contains("Max-Age=60");
    }

    @Test
    void refreshRejectsMissingRefreshTokenBeforeServiceCall() {
        IdentityAuthService authService = mock(IdentityAuthService.class);
        AuthController controller = new AuthController(authService, false, "Strict", 60_000L, "identity-token");

        assertThatThrownBy(() -> controller.refresh(null, new MockHttpServletResponse()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(authService, never()).refresh(null);
    }

    @Test
    void logoutClearsRefreshCookie() {
        IdentityAuthService authService = mock(IdentityAuthService.class);
        AuthController controller = new AuthController(authService, false, "Lax", 60_000L, "identity-token");
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        controller.logout("refresh-token", servletResponse);

        verify(authService).logout("refresh-token");
        assertThat(servletResponse.getHeader(HttpHeaders.SET_COOKIE))
                .contains("refresh_token=")
                .contains("Max-Age=0")
                .contains("SameSite=Lax");
    }

    @Test
    void introspectRejectsInvalidServiceTokenBeforeServiceCall() {
        IdentityAuthService authService = mock(IdentityAuthService.class);
        AuthController controller = new AuthController(authService, false, "Strict", 60_000L, "identity-token");

        assertThatThrownBy(() -> controller.introspect("wrong-token", new AuthController.IntrospectionRequest("access-token")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(authService, never()).introspect("access-token");
    }

    @Test
    void introspectDelegatesWithValidServiceToken() {
        IdentityAuthService authService = mock(IdentityAuthService.class);
        AuthController controller = new AuthController(authService, false, "Strict", 60_000L, "identity-token");
        IntrospectionResponse result = new IntrospectionResponse(true, authResponse("access-token"));
        when(authService.introspect("access-token")).thenReturn(result);

        IntrospectionResponse response = controller.introspect(
                "identity-token",
                new AuthController.IntrospectionRequest("access-token"));

        assertThat(response).isSameAs(result);
        verify(authService).introspect("access-token");
    }

    @Test
    void rbacRolesRejectsInvalidTokenBeforeQuerying() {
        RbacReadRepository rbac = mock(RbacReadRepository.class);
        RbacReadController controller = new RbacReadController(rbac, mock(RbacCommandRepository.class), mock(TenantSchoolClient.class), "identity-token");

        assertThatThrownBy(() -> controller.roles("wrong-token"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(rbac, never()).roles();
    }

    @Test
    void rbacUserPermissionsDelegatesFilters() {
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        RbacReadRepository rbac = mock(RbacReadRepository.class);
        RbacReadController controller = new RbacReadController(rbac, mock(RbacCommandRepository.class), mock(TenantSchoolClient.class), "identity-token");
        when(rbac.effectivePermissions(9L, 4L, 2L)).thenReturn(List.of("STUDENTS_READ"));

        Object response = controller.userPermissions("identity-token", 9L, 4L, 2L);

        assertThat(response).isEqualTo(List.of("STUDENTS_READ"));
        verify(rbac).effectivePermissions(9L, 4L, 2L);
    }

    @Test
    void rbacAssignSchoolRoleDelegatesBody() {
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        RbacCommandRepository commands = mock(RbacCommandRepository.class);
        RbacReadController controller = new RbacReadController(mock(RbacReadRepository.class), commands, mock(TenantSchoolClient.class), "identity-token");
        Map<String, Object> result = Map.of("id", 44L);
        when(commands.assignSchoolRole(eq(9L), anyMap())).thenReturn(result);

        AssignSchoolRoleRequest req = new AssignSchoolRoleRequest("ADMIN", 4L, 1L);
        Object response = controller.assignSchoolRole("identity-token", 9L, req);

        assertThat(response).isSameAs(result);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(commands).assignSchoolRole(eq(9L), captor.capture());
        assertThat(captor.getValue()).containsKey("role");
        assertThat(captor.getValue()).containsKey("schoolId");
    }

    @Test
    void userDirectoryReturnsNotFoundForMissingUser() {
        UserDirectoryReadRepository users = mock(UserDirectoryReadRepository.class);
        UserDirectoryController controller = new UserDirectoryController(users, "identity-token");
        when(users.user(404L)).thenReturn(null);

        assertThatThrownBy(() -> controller.user("identity-token", 404L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(response.getReason()).isEqualTo("user not found");
                });
    }

    @Test
    void userDirectoryResetPasswordUsesAuthenticatedActorNotClientSuppliedFields() {
        TenantContext.set(new TenantContext(7L, "authenticated-admin@custoking.com", "SUPERADMIN", null, null));
        UserDirectoryReadRepository users = mock(UserDirectoryReadRepository.class);
        UserDirectoryController controller = new UserDirectoryController(users, "identity-token");

        // Client-supplied actorId/actorEmail must be ignored in favor of the authenticated principal.
        PasswordResetRequest req = new PasswordResetRequest("new-password", 1L, "spoofed@custoking.com");
        controller.resetPassword("identity-token", 9L, req);

        verify(users).resetPassword(9L, "new-password", 7L, "authenticated-admin@custoking.com");
    }

    @Test
    void provisioningRejectsInvalidTokenBeforeRepositoryAccess() {
        IdentityUserProvisioningRepository users = mock(IdentityUserProvisioningRepository.class);
        IdentityProvisioningController controller = new IdentityProvisioningController(users, "identity-token");
        Map<String, Object> request = Map.of("email", "admin@school.test");

        assertThatThrownBy(() -> controller.provisionSchoolUser("wrong-token", 4L, "ADMIN", request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(users, never()).provisionSchoolUser(4L, "ADMIN", request);
    }

    @Test
    void provisioningDelegatesZoneAdminCreation() {
        TenantContext.set(new TenantContext(7L, "authenticated-admin@custoking.com", "SUPERADMIN", null, null));
        IdentityUserProvisioningRepository users = mock(IdentityUserProvisioningRepository.class);
        IdentityProvisioningController controller = new IdentityProvisioningController(users, "identity-token");
        Map<String, Object> request = Map.of("email", "zone@school.test");
        Map<String, Object> result = Map.of("id", 10L);
        when(users.provisionZoneAdmin(eq(2L), anyMap())).thenReturn(result);

        Map<String, Object> response = controller.provisionZoneAdmin("identity-token", 2L, request);

        assertThat(response).isSameAs(result);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(users).provisionZoneAdmin(eq(2L), captor.capture());
        // assignedBy must come from the authenticated principal, not any client-supplied value.
        assertThat(captor.getValue()).containsEntry("email", "zone@school.test");
        assertThat(captor.getValue()).containsEntry("assignedBy", 7L);
    }

    private AuthResponse authResponse(String accessToken) {
        return new AuthResponse(
                accessToken,
                1L,
                "Admin User",
                "admin@custoking.com",
                "SUPERADMIN",
                null,
                null,
                null,
                null,
                List.of("SUPERADMIN"),
                List.of("USERS_READ"),
                List.of());
    }
}
