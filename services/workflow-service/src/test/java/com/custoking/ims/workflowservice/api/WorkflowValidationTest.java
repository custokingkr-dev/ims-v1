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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc validation tests for POST /instances (workflow-service Phase 1.7).
 *
 * Action endpoints (submit/approve/reject/cancel/complete) are deferred — all accept
 * {@code @RequestBody(required = false)} with no required fields; bean-validation would
 * add nothing there.
 */
class WorkflowValidationTest {

    private static final String VALID_TOKEN = "workflow-token";

    WorkflowReadRepository workflows;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        workflows = mock(WorkflowReadRepository.class);
        WorkflowReadController controller = new WorkflowReadController(workflows, VALID_TOKEN);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ValidationExceptionHandler())
                .build();
        // Superadmin context so TenantScope.resolveSchoolId passes through the supplied value
        TenantContext.set(new TenantContext(null, null, "SUPERADMIN", null, null));
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    // ─── POST /instances — missing required fields ────────────────────────────

    @Test
    void createInstance_emptyBody_returns400WithAllThreeFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/workflows/instances")
                        .header("X-Workflow-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.entityType").exists())
                .andExpect(jsonPath("$.fieldErrors.entityId").exists())
                .andExpect(jsonPath("$.fieldErrors.definitionId").exists());
        verifyNoInteractions(workflows);
    }

    @Test
    void createInstance_missingEntityType_returns400() throws Exception {
        mvc.perform(post("/api/v1/workflows/instances")
                        .header("X-Workflow-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"entityId\":\"order-1\",\"definitionId\":\"wf-def-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.entityType").exists());
        verifyNoInteractions(workflows);
    }

    @Test
    void createInstance_blankEntityType_returns400() throws Exception {
        mvc.perform(post("/api/v1/workflows/instances")
                        .header("X-Workflow-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"entityType\":\"\",\"entityId\":\"order-1\",\"definitionId\":\"wf-def-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.entityType").exists());
        verifyNoInteractions(workflows);
    }

    @Test
    void createInstance_missingEntityId_returns400() throws Exception {
        mvc.perform(post("/api/v1/workflows/instances")
                        .header("X-Workflow-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"entityType\":\"ORDER\",\"definitionId\":\"wf-def-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.entityId").exists());
        verifyNoInteractions(workflows);
    }

    @Test
    void createInstance_missingDefinitionId_returns400() throws Exception {
        mvc.perform(post("/api/v1/workflows/instances")
                        .header("X-Workflow-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"entityType\":\"ORDER\",\"entityId\":\"order-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.definitionId").exists());
        verifyNoInteractions(workflows);
    }

    // ─── POST /instances — valid payload, repo called with exact keys ─────────

    @Test
    @SuppressWarnings("unchecked")
    void createInstance_allRequiredFields_callsRepoWithExactKeys() throws Exception {
        when(workflows.createOrGetInstance(anyMap())).thenReturn(Map.of("id", 1L, "status", "PENDING"));

        mvc.perform(post("/api/v1/workflows/instances")
                        .header("X-Workflow-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"entityType\":\"ORDER\",\"entityId\":\"order-99\",\"definitionId\":\"wf-def-2\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(workflows).createOrGetInstance(captor.capture());
        Map<String, Object> body = captor.getValue();
        assertEquals("ORDER", body.get("entityType"));
        assertEquals("order-99", body.get("entityId"));
        assertEquals("wf-def-2", body.get("definitionId"));
        // schoolId key must be present (TenantScope.resolveSchoolId returns null for SUPERADMIN with null input)
        assertNotNull(body.containsKey("schoolId"));
        assertNull(body.get("schoolId"));
        // initiatedBy must NOT be present when omitted
        assertEquals(false, body.containsKey("initiatedBy"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void createInstance_withOptionalFields_callsRepoWithInitiatedBy() throws Exception {
        when(workflows.createOrGetInstance(anyMap())).thenReturn(Map.of("id", 2L, "status", "PENDING"));

        mvc.perform(post("/api/v1/workflows/instances")
                        .header("X-Workflow-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"entityType\":\"ORDER\",\"entityId\":\"order-7\",\"definitionId\":\"wf-def-3\","
                                + "\"schoolId\":42,\"initiatedBy\":101}"))
                .andExpect(status().isOk());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(workflows).createOrGetInstance(captor.capture());
        Map<String, Object> body = captor.getValue();
        assertEquals("ORDER", body.get("entityType"));
        assertEquals("order-7", body.get("entityId"));
        assertEquals("wf-def-3", body.get("definitionId"));
        assertEquals(101L, body.get("initiatedBy"));
    }
}
