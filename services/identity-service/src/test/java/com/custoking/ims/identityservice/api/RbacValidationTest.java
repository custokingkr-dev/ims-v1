package com.custoking.ims.identityservice.api;

import com.custoking.ims.identityservice.infrastructure.TenantSchoolClient;
import com.custoking.ims.identityservice.persistence.RbacCommandRepository;
import com.custoking.ims.identityservice.persistence.RbacReadRepository;
import com.custoking.ims.identityservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RbacValidationTest {

    private static final String VALID_TOKEN = "identity-token";

    RbacCommandRepository commands;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        // These tests assert request-mapping/response behavior, not authorization; the caller
        // is set to SUPERADMIN so the (separately tested) requireSuperAdmin() gate is satisfied.
        TenantContext.set(new TenantContext(1L, "sa@custoking.com", "SUPERADMIN", null, null));
        commands = mock(RbacCommandRepository.class);
        RbacReadRepository reads = mock(RbacReadRepository.class);
        TenantSchoolClient schoolClient = mock(TenantSchoolClient.class);
        RbacReadController controller = new RbacReadController(reads, commands, schoolClient, VALID_TOKEN);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ValidationExceptionHandler())
                .build();
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void createRole_blankName_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/rbac/roles")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.name").exists());
        verifyNoInteractions(commands);
    }

    @Test
    void createRole_validName_callsRepositoryWithKeys() throws Exception {
        when(commands.createRole(anyMap())).thenReturn(Map.of("id", 1));
        mvc.perform(post("/api/v1/rbac/roles")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"name\":\"AUDITOR\",\"description\":\"d\"}"))
                .andExpect(status().isCreated());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(commands).createRole(captor.capture());
        assertEquals("AUDITOR", captor.getValue().get("name"));
        assertEquals("d", captor.getValue().get("description"));
    }

    @Test
    void assignSchoolRole_missingSchoolId_returns400() throws Exception {
        mvc.perform(post("/api/v1/rbac/users/9/roles/school")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.schoolId").exists());
        verify(commands, never()).assignSchoolRole(anyLong(), anyMap());
    }

    @Test
    void assignSchoolRole_valid_callsRepositoryWithRoleAndSchoolIdKeys() throws Exception {
        when(commands.assignSchoolRole(anyLong(), anyMap())).thenReturn(Map.of("id", 10));
        mvc.perform(post("/api/v1/rbac/users/9/roles/school")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"role\":\"ADMIN\",\"schoolId\":4}"))
                .andExpect(status().isOk());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(commands).assignSchoolRole(eq(9L), captor.capture());
        assertNotNull(captor.getValue().get("role"));
        assertNotNull(captor.getValue().get("schoolId"));
    }

    // --- updateRole partial-update tests (cover the critical bug fix) ---

    @Test
    void updateRole_descriptionOnly_doesNotTouchPermissions() throws Exception {
        when(commands.updateRole(anyLong(), anyMap())).thenReturn(Map.of("id", 5));
        mvc.perform(put("/api/v1/rbac/roles/5")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"description\":\"new\"}"))
                .andExpect(status().isOk());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(commands).updateRole(eq(5L), captor.capture());
        assertTrue(captor.getValue().containsKey("description"), "description key must be present");
        assertFalse(captor.getValue().containsKey("permissions"), "permissions key must NOT be present when not sent");
    }

    @Test
    void updateRole_permissionsOnly_doesNotTouchDescription() throws Exception {
        when(commands.updateRole(anyLong(), anyMap())).thenReturn(Map.of("id", 5));
        mvc.perform(put("/api/v1/rbac/roles/5")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"permissions\":[\"fee:read\"]}"))
                .andExpect(status().isOk());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(commands).updateRole(eq(5L), captor.capture());
        assertTrue(captor.getValue().containsKey("permissions"), "permissions key must be present");
        assertFalse(captor.getValue().containsKey("description"), "description key must NOT be present when not sent");
    }

    // --- assignPlatformRole + assignZoneRole valid-path tests ---

    @Test
    void assignPlatformRole_valid_callsRepoWithRole() throws Exception {
        when(commands.assignPlatformRole(anyLong(), anyMap())).thenReturn(Map.of("id", 20));
        mvc.perform(post("/api/v1/rbac/users/9/roles/platform")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"role\":\"AUDITOR\"}"))
                .andExpect(status().isOk());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(commands).assignPlatformRole(eq(9L), captor.capture());
        assertEquals("AUDITOR", captor.getValue().get("role"));
    }

    @Test
    void assignZoneRole_valid_callsRepoWithRoleAndZone() throws Exception {
        when(commands.assignZoneRole(anyLong(), anyMap())).thenReturn(Map.of("id", 30));
        mvc.perform(post("/api/v1/rbac/users/9/roles/zone")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"role\":\"ZONE_ADMIN\",\"zoneId\":4}"))
                .andExpect(status().isOk());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(commands).assignZoneRole(eq(9L), captor.capture());
        assertEquals("ZONE_ADMIN", captor.getValue().get("role"));
        assertEquals(4L, captor.getValue().get("zoneId"));
    }
}
