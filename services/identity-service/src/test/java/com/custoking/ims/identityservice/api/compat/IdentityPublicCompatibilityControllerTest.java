package com.custoking.ims.identityservice.api.compat;

import com.custoking.ims.identityservice.persistence.IdentityUserProvisioningRepository;
import com.custoking.ims.identityservice.security.TenantContext;
import com.custoking.ims.identityservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IdentityPublicCompatibilityControllerTest {

    private final IdentityUserProvisioningRepository users = mock(IdentityUserProvisioningRepository.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new IdentityPublicCompatibilityController(users, "tok"))
            .addFilters(new TenantContextFilter())
            .build();

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    // ---- createSchoolAdmin POST /api/v1/schools/{schoolId}/admin ----

    @Test
    void createSchoolAdmin_superadmin_delegates() throws Exception {
        when(users.provisionSchoolUser(eq(7L), eq("ADMIN"), any()))
                .thenReturn(Map.of("id", 1));
        mvc.perform(post("/api/v1/schools/7/admin")
                        .header("X-Identity-Service-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.c\"}"))
                .andExpect(status().isCreated());
        verify(users).provisionSchoolUser(eq(7L), eq("ADMIN"), any());
    }

    @Test
    void createSchoolAdmin_nonSuperadmin_isForbidden() throws Exception {
        mvc.perform(post("/api/v1/schools/7/admin")
                        .header("X-Identity-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.c\"}"))
                .andExpect(status().isForbidden());
        verify(users, never()).provisionSchoolUser(any(), any(), any());
    }

    // ---- createOperationsUser POST /api/v1/schools/{schoolId}/operations-user ----

    @Test
    void createOperationsUser_superadmin_delegates() throws Exception {
        when(users.provisionSchoolUser(eq(7L), eq("OPERATIONS"), any()))
                .thenReturn(Map.of("id", 2));
        mvc.perform(post("/api/v1/schools/7/operations-user")
                        .header("X-Identity-Service-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"b@c.d\"}"))
                .andExpect(status().isCreated());
        verify(users).provisionSchoolUser(eq(7L), eq("OPERATIONS"), any());
    }

    @Test
    void createOperationsUser_nonSuperadmin_isForbidden() throws Exception {
        mvc.perform(post("/api/v1/schools/7/operations-user")
                        .header("X-Identity-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"b@c.d\"}"))
                .andExpect(status().isForbidden());
        verify(users, never()).provisionSchoolUser(any(), any(), any());
    }

    // ---- createZoneAdmin POST /api/v1/zones/{zoneId}/admin ----

    @Test
    void createZoneAdmin_superadmin_delegates() throws Exception {
        when(users.provisionZoneAdmin(eq(3L), any()))
                .thenReturn(Map.of("id", 3));
        mvc.perform(post("/api/v1/zones/3/admin")
                        .header("X-Identity-Service-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"c@d.e\"}"))
                .andExpect(status().isCreated());
        verify(users).provisionZoneAdmin(eq(3L), any());
    }

    @Test
    void createZoneAdmin_nonSuperadmin_isForbidden() throws Exception {
        mvc.perform(post("/api/v1/zones/3/admin")
                        .header("X-Identity-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"c@d.e\"}"))
                .andExpect(status().isForbidden());
        verify(users, never()).provisionZoneAdmin(any(), any());
    }

    // ---- rejectsBadToken (pre-existing adapted) ----

    @Test
    void rejectsBadToken() throws Exception {
        mvc.perform(post("/api/v1/schools/7/admin")
                        .header("X-Identity-Service-Token", "nope")
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.c\"}"))
                .andExpect(status().isUnauthorized());
        verify(users, never()).provisionSchoolUser(any(), any(), any());
    }
}
