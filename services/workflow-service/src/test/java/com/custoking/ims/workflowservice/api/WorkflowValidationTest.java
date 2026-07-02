package com.custoking.ims.workflowservice.api;

import com.custoking.ims.workflowservice.persistence.WorkflowReadRepository;
import com.custoking.ims.workflowservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkflowValidationTest {

    private static final String VALID_TOKEN = "workflow-token";

    WorkflowReadRepository repo;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        repo = mock(WorkflowReadRepository.class);
        WorkflowReadController controller = new WorkflowReadController(repo, VALID_TOKEN);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ValidationExceptionHandler())
                .build();
        // Use SUPERADMIN so TenantScope passes through provided schoolId
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // --- POST /instances (create-or-get) ---

    @Test
    void createInstance_blankEntityType_returns400() throws Exception {
        mvc.perform(post("/api/v1/workflows/instances")
                        .header("X-Workflow-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"entityType\":\"\",\"entityId\":\"order-1\",\"definitionId\":\"def-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.entityType").exists());
        verify(repo, never()).createOrGetInstance(anyMap());
    }

    @Test
    void createInstance_blankEntityId_returns400() throws Exception {
        mvc.perform(post("/api/v1/workflows/instances")
                        .header("X-Workflow-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"entityType\":\"ORDER\",\"entityId\":\"\",\"definitionId\":\"def-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.entityId").exists());
        verify(repo, never()).createOrGetInstance(anyMap());
    }

    @Test
    void createInstance_missingBothRequired_returns400WithBothFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/workflows/instances")
                        .header("X-Workflow-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"definitionId\":\"def-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.entityType").exists())
                .andExpect(jsonPath("$.fieldErrors.entityId").exists());
        verify(repo, never()).createOrGetInstance(anyMap());
    }

    /**
     * Fix 1 regression test: a get-or-create call for an EXISTING (entityType,entityId) instance
     * WITHOUT definitionId must succeed (return 200), not 400. The DTO no longer @NotBlank on
     * definitionId, so the request reaches the repo which returns the existing instance.
     */
    @Test
    void createInstance_existingInstanceWithoutDefinitionId_succeeds() throws Exception {
        when(repo.createOrGetInstance(anyMap()))
                .thenReturn(Map.of("id", 55L, "status", "IN_PROGRESS", "entityType", "ORDER", "entityId", "order-42"));

        mvc.perform(post("/api/v1/workflows/instances")
                        .header("X-Workflow-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"entityType\":\"ORDER\",\"entityId\":\"order-42\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(55));

        verify(repo, times(1)).createOrGetInstance(anyMap());
    }

    @Test
    void createInstance_valid_callsRepositoryWithExpectedKeys() throws Exception {
        when(repo.createOrGetInstance(anyMap())).thenReturn(Map.of("id", 10L));
        mvc.perform(post("/api/v1/workflows/instances")
                        .header("X-Workflow-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"entityType\":\"ORDER\",\"entityId\":\"order-1\"," +
                                 "\"definitionId\":\"def-approval\",\"schoolId\":42}"))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(repo).createOrGetInstance(captor.capture());
        Map<String, Object> body = captor.getValue();
        assertEquals("ORDER", body.get("entityType"));
        assertEquals("order-1", body.get("entityId"));
        assertEquals("def-approval", body.get("definitionId"));
        // Fix 2: use assertTrue (not assertNotNull) — containsKey returns boolean, not Object
        assertTrue(body.containsKey("schoolId"));
        // Fix 3: assert the resolved schoolId value (SUPERADMIN passes 42L through)
        assertEquals(42L, body.get("schoolId"));
    }

    @Test
    void createInstance_withOptionalFields_callsRepoWithInitiatedBy() throws Exception {
        when(repo.createOrGetInstance(anyMap())).thenReturn(Map.of("id", 11L));
        mvc.perform(post("/api/v1/workflows/instances")
                        .header("X-Workflow-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"entityType\":\"ORDER\",\"entityId\":\"order-2\"," +
                                 "\"definitionId\":\"def-approval\",\"schoolId\":42,\"initiatedBy\":7}"))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(repo).createOrGetInstance(captor.capture());
        Map<String, Object> body = captor.getValue();
        assertEquals(7L, body.get("initiatedBy"));
        // Fix 2: use assertTrue (not assertNotNull) — containsKey returns boolean, not Object
        assertTrue(body.containsKey("schoolId"));
        // Fix 3: TenantScope.resolveSchoolId(42L) = 42L for SUPERADMIN
        assertEquals(42L, body.get("schoolId"));
    }
}
