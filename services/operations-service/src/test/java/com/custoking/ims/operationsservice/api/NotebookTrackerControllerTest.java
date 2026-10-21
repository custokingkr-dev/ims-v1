package com.custoking.ims.operationsservice.api;

import com.custoking.ims.operationsservice.application.NotebookTrackerService;
import com.custoking.ims.operationsservice.application.tracker.TrackerRuleViolation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The tracker API's tokens, its worklist parameters, and that a refusal carries its reason to the screen. */
class NotebookTrackerControllerTest {
    NotebookTrackerService service;
    MockMvc mvc;

    @BeforeEach void setup() {
        service = mock(NotebookTrackerService.class);
        mvc = MockMvcBuilders.standaloneSetup(new NotebookTrackerController(service, "workflow-token")).build();
    }

    @Test void everyUserEndpointNeedsTheWorkflowServiceToken() throws Exception {
        mvc.perform(get("/api/v1/workflows/notebook-orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/workflows/notebook-orders/CK-1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/workflows/notebook-orders/CK-1").header("X-Workflow-Service-Token", "machine-token")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void theMachineEndpointsAreNoLongerOnTheUserFacingPath() throws Exception {
        String body = "{\"orderId\":\"CK-1\",\"schoolId\":1,\"customised\":true,\"orderedCount\":10,\"placedAt\":\"2026-10-19T09:30:00+05:30\",\"timeZone\":\"Asia/Kolkata\"}";
        for (String header : new String[]{"X-Notebook-Tracker-Service-Token", "X-Workflow-Service-Token"}) {
            String token = header.startsWith("X-Workflow") ? "workflow-token" : "machine-token";
            mvc.perform(post("/api/v1/workflows/notebook-orders").header(header, token).contentType("application/json").content(body))
                    .andExpect(status().isMethodNotAllowed());
            mvc.perform(post("/api/v1/workflows/notebook-orders/CK-1/design/image-access").header(header, token)
                    .contentType("application/json").content("{\"userId\":1,\"role\":\"OPERATIONS\"}")).andExpect(status().isNotFound());
        }
        verifyNoInteractions(service);
    }

    @Test void theWorklistDefaultsToMineAndRejectsAnUnknownScope() throws Exception {
        when(service.list(anyBoolean(), any(), anyBoolean(), anyInt())).thenReturn(List.of());
        mvc.perform(get("/api/v1/workflows/notebook-orders").header("X-Workflow-Service-Token", "workflow-token")).andExpect(status().isOk());
        org.mockito.Mockito.verify(service).list(true, null, false, 100);
        mvc.perform(get("/api/v1/workflows/notebook-orders").param("scope", "all").param("schoolId", "4").param("includeFinished", "true").param("limit", "20")
                .header("X-Workflow-Service-Token", "workflow-token")).andExpect(status().isOk());
        org.mockito.Mockito.verify(service).list(false, 4L, true, 20);
        mvc.perform(get("/api/v1/workflows/notebook-orders").param("scope", "everyone").header("X-Workflow-Service-Token", "workflow-token"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("scope must be mine or all"));
    }

    @Test void aRefusalCarriesItsReasonAsAMessage() throws Exception {
        when(service.view("CK-1")).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "This step is assigned to another operator"));
        mvc.perform(get("/api/v1/workflows/notebook-orders/CK-1").header("X-Workflow-Service-Token", "workflow-token"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("This step is assigned to another operator"));
        when(service.view("CK-2")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        mvc.perform(get("/api/v1/workflows/notebook-orders/CK-2").header("X-Workflow-Service-Token", "workflow-token"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Not Found"));
    }

    @Test void aRuleViolationKeepsItsStableCodeAndMapsToTheRightStatus() throws Exception {
        when(service.view("CK-1")).thenThrow(new TrackerRuleViolation("STEP_NOT_ACTIVE", "Designing Activity is not the active step"));
        mvc.perform(get("/api/v1/workflows/notebook-orders/CK-1").header("X-Workflow-Service-Token", "workflow-token"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STEP_NOT_ACTIVE"))
                .andExpect(jsonPath("$.message").value("Designing Activity is not the active step"));
        when(service.view("CK-2")).thenThrow(new TrackerRuleViolation("SUPERADMIN_ONLY", "Only superadmin can reopen a completed step"));
        mvc.perform(get("/api/v1/workflows/notebook-orders/CK-2").header("X-Workflow-Service-Token", "workflow-token")).andExpect(status().isForbidden());
        when(service.view("CK-3")).thenThrow(new TrackerRuleViolation("NOTE_TOO_LONG", "A stage note is limited to 100 words"));
        mvc.perform(get("/api/v1/workflows/notebook-orders/CK-3").header("X-Workflow-Service-Token", "workflow-token")).andExpect(status().isBadRequest());
    }

    @Test void savingNeedsAnExpectedVersion() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/workflows/notebook-orders/CK-1/steps/5/draft")
                .header("X-Workflow-Service-Token", "workflow-token").contentType("application/json").content("{\"data\":{}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("expectedVersion is required"));
        verifyNoInteractions(service);
    }

    /** A sign-off approves the data superadmin's screen loaded, so it carries that step version like a save does. */
    @Test void aSignOffCarriesTheStepVersionItApproves() throws Exception {
        mvc.perform(post("/api/v1/workflows/notebook-orders/CK-1/steps/8/sign-off")
                .header("X-Workflow-Service-Token", "workflow-token").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("expectedVersion is required"));
        verifyNoInteractions(service);
        mvc.perform(post("/api/v1/workflows/notebook-orders/CK-1/steps/8/sign-off")
                .header("X-Workflow-Service-Token", "workflow-token").contentType("application/json").content("{\"expectedVersion\":4}"))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(service).signOff("CK-1", 8, 4L);
    }
}
