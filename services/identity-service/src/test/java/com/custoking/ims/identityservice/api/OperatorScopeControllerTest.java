package com.custoking.ims.identityservice.api;

import com.custoking.ims.identityservice.application.OperatorDirectory;
import com.custoking.ims.identityservice.persistence.OperatorDirectoryRepository.OperatorRow;
import com.custoking.ims.identityservice.persistence.RbacCommandRepository;
import com.custoking.ims.identityservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who may manage operator scopes; the machine directory lookups now live on the internal route. */
class OperatorScopeControllerTest {
    private static final String IDENTITY_TOKEN = "identity-token";

    private final RbacCommandRepository commands = mock(RbacCommandRepository.class);
    private final OperatorDirectory directory = mock(OperatorDirectory.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new OperatorScopeController(commands, directory, IDENTITY_TOKEN))
            .addFilters(com.custoking.ims.identityservice.VerifiedSessionFixtures.filter())
            .defaultRequest(get("/").header("X-Authenticated-User-Id", "1").header("X-Authenticated-Session-Id", "verified-test-session"))
            .build();

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void assigningAScopeIsSuperadminOnly() throws Exception {
        mvc.perform(post("/api/v1/rbac/users/7/operator-scopes").header("X-Identity-Service-Token", IDENTITY_TOKEN)
                        .header("X-Authenticated-Role", "ADMIN").header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "role:assign")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"schoolId\":4,\"catalogCategory\":\"NOTEBOOKS\"}"))
                .andExpect(status().isForbidden());
        verify(commands, never()).assignOperatorScope(any(), any(), any(), any());
    }

    @Test
    void assigningAScopeNeedsTheServiceToken() throws Exception {
        mvc.perform(post("/api/v1/rbac/users/7/operator-scopes").header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"schoolId\":4,\"catalogCategory\":\"NOTEBOOKS\"}"))
                .andExpect(status().isUnauthorized());
        verify(commands, never()).assignOperatorScope(any(), any(), any(), any());
    }

    @Test
    void superadminAssignsAScopeAndTheActorIsRecorded() throws Exception {
        when(commands.assignOperatorScope(eq(7L), eq(4L), eq("NOTEBOOKS"), any())).thenReturn(Map.of("id", 55, "catalogCategory", "NOTEBOOKS"));
        mvc.perform(post("/api/v1/rbac/users/7/operator-scopes").header("X-Identity-Service-Token", IDENTITY_TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"schoolId\":4,\"catalogCategory\":\"NOTEBOOKS\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.catalogCategory").value("NOTEBOOKS"));
        verify(commands).assignOperatorScope(7L, 4L, "NOTEBOOKS", 1L);
    }

    @Test
    void listingOperatorsIsSuperadminOnly() throws Exception {
        when(directory.list(any(), any())).thenReturn(List.of(new OperatorRow(1, 7, "Op One", "op@x", 4, "NOTEBOOKS")));
        mvc.perform(get("/api/v1/rbac/operators").header("X-Identity-Service-Token", IDENTITY_TOKEN)
                        .header("X-Authenticated-Role", "OPERATIONS").header("X-Authenticated-Permissions", "role:read"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/rbac/operators").param("schoolId", "4").header("X-Identity-Service-Token", IDENTITY_TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].catalogCategory").value("NOTEBOOKS"))
                .andExpect(jsonPath("$[0].fullName").value("Op One"));
    }

    @Test
    void aValidTokenFromAUserWithoutThePermissionIsRefused() throws Exception {
        mvc.perform(get("/api/v1/rbac/operators").header("X-Identity-Service-Token", IDENTITY_TOKEN)
                        .header("X-Authenticated-Role", "OPERATIONS").header("X-Authenticated-Permissions", "workflow:read"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/rbac/users/7/operator-scopes").header("X-Identity-Service-Token", IDENTITY_TOKEN)
                        .header("X-Authenticated-Role", "OPERATIONS").header("X-Authenticated-Permissions", "workflow:read")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"schoolId\":4,\"catalogCategory\":\"NOTEBOOKS\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(directory);
        verify(commands, never()).assignOperatorScope(any(), any(), any(), any());
    }

    @Test
    void theDirectoryLookupsAreNoLongerOnTheRbacPath() throws Exception {
        for (String path : new String[]{"/api/v1/rbac/operator-directory/resolve", "/api/v1/rbac/operator-directory/eligible"}) {
            mvc.perform(get(path).param("schoolId", "4").param("catalogCategory", "NOTEBOOKS").param("userId", "7")
                            .header("X-Operator-Directory-Service-Token", "directory-token"))
                    .andExpect(status().isNotFound());
            mvc.perform(get(path).param("schoolId", "4").param("catalogCategory", "NOTEBOOKS").param("userId", "7")
                            .header("X-Identity-Service-Token", IDENTITY_TOKEN).header("X-Authenticated-Role", "SUPERADMIN"))
                    .andExpect(status().isNotFound());
        }
        verifyNoInteractions(directory);
    }
}
