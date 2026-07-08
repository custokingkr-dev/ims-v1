package com.custoking.ims.identityservice.api;

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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new RbacReadController(reads, commands, VALID_TOKEN))
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
                        .header("X-Authenticated-School-Id", "10"))
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
}
