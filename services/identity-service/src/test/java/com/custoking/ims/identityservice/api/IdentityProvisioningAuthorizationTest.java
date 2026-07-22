package com.custoking.ims.identityservice.api;

import com.custoking.ims.identityservice.persistence.IdentityUserProvisioningRepository;
import com.custoking.ims.identityservice.security.TenantContext;
import com.custoking.ims.identityservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Broken-access-control regression coverage: user-provisioning endpoints must require the
 * SUPERADMIN role (not just a valid internal service token), otherwise any authenticated
 * caller could provision users into any school/zone.
 */
class IdentityProvisioningAuthorizationTest {

    private static final String VALID_TOKEN = "identity-token";

    private final IdentityUserProvisioningRepository users = mock(IdentityUserProvisioningRepository.class);

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new IdentityProvisioningController(users, VALID_TOKEN))
            .addFilters(new TenantContextFilter())
            .build();

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    // ---- provisionSchoolUser ----

    @Test
    void provisionSchoolUser_nonSuperadmin_isForbidden() throws Exception {
        mvc.perform(post("/api/v1/users/provisioning/schools/4/users/ADMIN")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "4")
                        .header("X-Authenticated-Permissions", "user:create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.c\"}"))
                .andExpect(status().isForbidden());
        verify(users, never()).provisionSchoolUser(any(), any(), any());
    }

    @Test
    void provisionSchoolUser_superadmin_isAllowed() throws Exception {
        when(users.provisionSchoolUser(eq(4L), eq("ADMIN"), any())).thenReturn(Map.of("id", 1));
        mvc.perform(post("/api/v1/users/provisioning/schools/4/users/ADMIN")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.c\"}"))
                .andExpect(status().isCreated());
        verify(users).provisionSchoolUser(eq(4L), eq("ADMIN"), any());
    }

    // ---- provisionZoneAdmin ----

    @Test
    void provisionZoneAdmin_nonSuperadmin_isForbidden() throws Exception {
        mvc.perform(post("/api/v1/users/provisioning/zones/2/admin")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "4")
                        .header("X-Authenticated-Permissions", "user:create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"z@b.c\"}"))
                .andExpect(status().isForbidden());
        verify(users, never()).provisionZoneAdmin(any(), any());
    }

    @Test
    void provisionZoneAdmin_superadmin_isAllowed() throws Exception {
        when(users.provisionZoneAdmin(eq(2L), any())).thenReturn(Map.of("id", 10));
        mvc.perform(post("/api/v1/users/provisioning/zones/2/admin")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"z@b.c\"}"))
                .andExpect(status().isCreated());
        verify(users).provisionZoneAdmin(eq(2L), any());
    }
}
