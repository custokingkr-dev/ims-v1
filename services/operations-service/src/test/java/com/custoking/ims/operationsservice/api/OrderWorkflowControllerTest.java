package com.custoking.ims.operationsservice.api;

import com.custoking.ims.operationsservice.application.OrderWorkflowService;
import com.custoking.ims.operationsservice.application.OrderWorkflowService.ContactsChange;
import com.custoking.ims.operationsservice.application.OrderWorkflowService.ListQuery;
import com.custoking.ims.operationsservice.application.orderworkflow.OrderWorkflowView.NoteEntry;
import com.custoking.ims.operationsservice.application.orderworkflow.OrderWorkflowView.OrderWorkflowCounts;
import com.custoking.ims.operationsservice.application.orderworkflow.OrderWorkflowView.OrderWorkflowPage;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.GateFailure;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.Sla;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.WorkflowRuleViolation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The order workflow API (design §3.5): the workflow service token on every route, the list's query, the request bodies
 * and their required parts, and refusals that reach the screen with their stable code and every unmet requirement.
 */
class OrderWorkflowControllerTest {
    private static final String TOKEN = "X-Workflow-Service-Token";
    OrderWorkflowService service;
    MockMvc mvc;

    @BeforeEach void setup() {
        service = mock(OrderWorkflowService.class);
        mvc = MockMvcBuilders.standaloneSetup(new OrderWorkflowController(service, "workflow-token")).build();
    }

    @Test void everyRouteNeedsTheWorkflowServiceToken() throws Exception {
        mvc.perform(get("/api/v1/workflows/item-orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/workflows/item-orders/CK-1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/workflows/item-orders/templates")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/workflows/designers")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/workflows/item-orders/CK-1/claim")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/workflows/item-orders/CK-1/steps/1/data").contentType("application/json")
                .content("{\"data\":{},\"expectedVersion\":0}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/workflows/item-orders/CK-1").header(TOKEN, "another-token")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void theOldNotebookOrderRoutesAreGone() throws Exception {
        mvc.perform(get("/api/v1/workflows/notebook-orders").header(TOKEN, "workflow-token")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/workflows/item-orders/CK-1/design/send").header(TOKEN, "workflow-token")).andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }

    @Test void theListPassesEveryFilterOnAndAnswersThePage() throws Exception {
        when(service.list(any())).thenReturn(new OrderWorkflowPage(List.of(), 0, new OrderWorkflowCounts(3, 1, 2, 0, 1),
                OffsetDateTime.parse("2026-11-13T04:00:00Z")));
        mvc.perform(get("/api/v1/workflows/item-orders").header(TOKEN, "workflow-token")
                        .param("item", "NB,FE").param("step", "PRINTING_PRESS").param("phase", "PRODUCTION").param("assignee", "me")
                        .param("schoolId", "4").param("sla", "OVERDUE").param("q", "dps").param("includeFinished", "true")
                        .param("sort", "number").param("limit", "20").param("offset", "40"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.counts.open").value(3)).andExpect(jsonPath("$.counts.dueToday").value(2));
        ArgumentCaptor<ListQuery> query = ArgumentCaptor.forClass(ListQuery.class);
        verify(service).list(query.capture());
        assertEquals(new ListQuery("NB,FE", "PRINTING_PRESS", "PRODUCTION", "me", 4L, "OVERDUE", "dps", true, "number", 20, 40), query.getValue());
    }

    @Test void theDueTodayChipsFilterReachesTheService() throws Exception {
        when(service.list(any())).thenReturn(new OrderWorkflowPage(List.of(), 2, new OrderWorkflowCounts(3, 1, 2, 0, 1),
                OffsetDateTime.parse("2026-11-13T04:00:00Z")));
        mvc.perform(get("/api/v1/workflows/item-orders").header(TOKEN, "workflow-token").param("sla", "DUE_TODAY"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.counts.dueToday").value(2));
        ArgumentCaptor<ListQuery> query = ArgumentCaptor.forClass(ListQuery.class);
        verify(service).list(query.capture());
        assertEquals("DUE_TODAY", query.getValue().sla());
    }

    @Test void aStepSaveNeedsItsDataAndExpectedVersion() throws Exception {
        mvc.perform(put("/api/v1/workflows/item-orders/CK-1/steps/5/data").header(TOKEN, "workflow-token")
                .contentType("application/json").content("{\"data\":{}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("expectedVersion is required"));
        mvc.perform(put("/api/v1/workflows/item-orders/CK-1/steps/5/data").header(TOKEN, "workflow-token")
                .contentType("application/json").content("{\"expectedVersion\":3}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("data is required"));
        mvc.perform(put("/api/v1/workflows/item-orders/CK-1/steps/0/data").header(TOKEN, "workflow-token")
                .contentType("application/json").content("{\"data\":{},\"expectedVersion\":3}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/workflows/item-orders/CK%201/steps/5/data").header(TOKEN, "workflow-token")
                .contentType("application/json").content("{\"data\":{},\"expectedVersion\":3}")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        mvc.perform(put("/api/v1/workflows/item-orders/CK-1/steps/5/data").header(TOKEN, "workflow-token")
                .contentType("application/json").content("{\"data\":{\"planningChecks\":[0,2]},\"expectedVersion\":3}"))
                .andExpect(status().isOk());
        verify(service).saveStepData("CK-1", 5, Map.of("planningChecks", List.of(0, 2)), 3L);
    }

    /** Design D19: Complete is refused with 409 GATE_UNMET listing every unmet requirement. */
    @Test void gateUnmetListsEveryRequirement() throws Exception {
        when(service.completeStep("CK-1", 3, 2L)).thenThrow(WorkflowRuleViolation.gateUnmet("CDR Conversion", List.of(
                new GateFailure("FIELDS_PRESENT", "Save the controlled storage link"),
                new GateFailure("ALL_CHECKED", "Tick the remaining 3 items of the checklist"))));
        mvc.perform(post("/api/v1/workflows/item-orders/CK-1/steps/3/complete").header(TOKEN, "workflow-token")
                        .contentType("application/json").content("{\"expectedVersion\":2}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GATE_UNMET"))
                .andExpect(jsonPath("$.unmet.length()").value(2))
                .andExpect(jsonPath("$.unmet[0].code").value("FIELDS_PRESENT"))
                .andExpect(jsonPath("$.unmet[0].message").value("Save the controlled storage link"))
                .andExpect(jsonPath("$.unmet[1].code").value("ALL_CHECKED"));
    }

    @Test void ruleViolationsMapToTheirStatusesWithTheirCodes() throws Exception {
        when(service.view("CK-1")).thenThrow(new WorkflowRuleViolation(WorkflowRuleViolation.NOT_ASSIGNEE, "assigned to another operator"));
        mvc.perform(get("/api/v1/workflows/item-orders/CK-1").header(TOKEN, "workflow-token"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_ASSIGNEE"))
                .andExpect(jsonPath("$.unmet").doesNotExist());
        when(service.view("CK-2")).thenThrow(new WorkflowRuleViolation(WorkflowRuleViolation.STEP_NOT_EDITABLE, "locked"));
        mvc.perform(get("/api/v1/workflows/item-orders/CK-2").header(TOKEN, "workflow-token"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STEP_NOT_EDITABLE"));
        when(service.view("CK-3")).thenThrow(new WorkflowRuleViolation(WorkflowRuleViolation.INVALID_STEP_DATA, "bad"));
        mvc.perform(get("/api/v1/workflows/item-orders/CK-3").header(TOKEN, "workflow-token")).andExpect(status().isBadRequest());
        when(service.view("CK-4")).thenThrow(new WorkflowRuleViolation(WorkflowRuleViolation.HEAD_APPROVAL_STALE, "reload"));
        mvc.perform(get("/api/v1/workflows/item-orders/CK-4").header(TOKEN, "workflow-token"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HEAD_APPROVAL_STALE"));
    }

    @Test void aRefusalCarriesItsReasonAsAMessage() throws Exception {
        when(service.view("CK-1")).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Order workflows are for superadmin and operators only"));
        mvc.perform(get("/api/v1/workflows/item-orders/CK-1").header(TOKEN, "workflow-token"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("Order workflows are for superadmin and operators only"));
        when(service.view("CK-2")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        mvc.perform(get("/api/v1/workflows/item-orders/CK-2").header(TOKEN, "workflow-token"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Not Found"));
    }

    @Test void headApprovalAndReopenCarryTheirTextAndVersion() throws Exception {
        mvc.perform(post("/api/v1/workflows/item-orders/CK-1/head-approval/approve").header(TOKEN, "workflow-token")
                .contentType("application/json").content("{\"comment\":\"Looks right\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("expectedVersion is required"));
        verifyNoInteractions(service);
        mvc.perform(post("/api/v1/workflows/item-orders/CK-1/head-approval/approve").header(TOKEN, "workflow-token")
                .contentType("application/json").content("{\"comment\":\"Looks right\",\"expectedVersion\":4}")).andExpect(status().isOk());
        verify(service).approveDesign("CK-1", "Looks right", 4L);
        mvc.perform(post("/api/v1/workflows/item-orders/CK-1/head-approval/request-changes").header(TOKEN, "workflow-token")
                .contentType("application/json").content("{\"comment\":\"Logo is wrong\",\"expectedVersion\":5}")).andExpect(status().isOk());
        verify(service).requestDesignChanges("CK-1", "Logo is wrong", 5L);
        mvc.perform(post("/api/v1/workflows/item-orders/CK-1/steps/2/reopen").header(TOKEN, "workflow-token")
                .contentType("application/json").content("{\"reason\":\"School changed the title\",\"expectedVersion\":6}")).andExpect(status().isOk());
        verify(service).reopenStep("CK-1", 2, "School changed the title", 6L);
        mvc.perform(post("/api/v1/workflows/item-orders/CK-1/steps/7/sign-off").header(TOKEN, "workflow-token")
                .contentType("application/json").content("{\"expectedVersion\":7}")).andExpect(status().isOk());
        verify(service).signOff("CK-1", 7, 7L);
    }

    @Test void assignmentClaimVendorsDesignerAndContactsReachTheService() throws Exception {
        mvc.perform(put("/api/v1/workflows/item-orders/CK-1/assignee").header(TOKEN, "workflow-token")
                .contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/workflows/item-orders/CK-1/assignee").header(TOKEN, "workflow-token")
                .contentType("application/json").content("{\"userId\":12}")).andExpect(status().isOk());
        verify(service).assignOrder("CK-1", 12L);
        mvc.perform(put("/api/v1/workflows/item-orders/CK-1/steps/4/assignee").header(TOKEN, "workflow-token")
                .contentType("application/json").content("{\"userId\":13}")).andExpect(status().isOk());
        verify(service).assignStep("CK-1", 4, 13L);
        mvc.perform(post("/api/v1/workflows/item-orders/CK-1/claim").header(TOKEN, "workflow-token")).andExpect(status().isOk());
        verify(service).claim("CK-1");
        mvc.perform(put("/api/v1/workflows/item-orders/CK-1/vendor-selections").header(TOKEN, "workflow-token")
                .contentType("application/json").content("{\"selections\":{\"BINDERS\":[3,7]},\"expectedVersion\":2}")).andExpect(status().isOk());
        verify(service).setVendorSelections("CK-1", Map.of("BINDERS", List.of(3L, 7L)), 2L);
        mvc.perform(put("/api/v1/workflows/item-orders/CK-1/designer").header(TOKEN, "workflow-token")
                .contentType("application/json").content("{\"designerId\":null}")).andExpect(status().isOk());
        verify(service).setDesigner("CK-1", null);
        mvc.perform(put("/api/v1/workflows/item-orders/CK-1/contacts").header(TOKEN, "workflow-token")
                .contentType("application/json").content("{\"schoolContactName\":\"Mrs Rao\",\"schoolContactPhone\":\"98450 12345\"}"))
                .andExpect(status().isOk());
        verify(service).setContacts("CK-1", new ContactsChange("Mrs Rao", "98450 12345", null, null));
    }

    @Test void aNoteIsCreated() throws Exception {
        when(service.addNote("CK-1", "Called the binder", 10)).thenReturn(new NoteEntry(5, 10, 3, "Asha", "Called the binder",
                OffsetDateTime.parse("2026-11-13T04:00:00Z")));
        mvc.perform(post("/api/v1/workflows/item-orders/CK-1/notes").header(TOKEN, "workflow-token")
                        .contentType("application/json").content("{\"body\":\"Called the binder\",\"stepOrder\":10}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(5)).andExpect(jsonPath("$.authorName").value("Asha"));
        mvc.perform(post("/api/v1/workflows/item-orders/CK-1/notes").header(TOKEN, "workflow-token")
                .contentType("application/json").content("{\"body\":\"x\",\"stepOrder\":0}")).andExpect(status().isBadRequest());
    }

    @Test void timeSlotsNeedTheirTemplateAndTakeBothPartsOrNeither() throws Exception {
        mvc.perform(get("/api/v1/workflows/time-slots/4").header(TOKEN, "workflow-token"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("definitionId is required"));
        mvc.perform(get("/api/v1/workflows/time-slots/4").param("definitionId", "FLIER_ORDER_WORKFLOW").header(TOKEN, "workflow-token"))
                .andExpect(status().isOk());
        verify(service).timeSlots(4L, "FLIER_ORDER_WORKFLOW");
        mvc.perform(put("/api/v1/workflows/time-slots/4/FLIER_ORDER_WORKFLOW/2").header(TOKEN, "workflow-token")
                .contentType("application/json").content("{\"value\":48,\"unit\":\"HOURS\"}")).andExpect(status().isNoContent());
        verify(service).setTimeSlot(4L, "FLIER_ORDER_WORKFLOW", 2, Sla.hours(48));
        mvc.perform(put("/api/v1/workflows/time-slots/4/FLIER_ORDER_WORKFLOW/3").header(TOKEN, "workflow-token")
                .contentType("application/json").content("{\"value\":null,\"unit\":null}")).andExpect(status().isNoContent());
        verify(service).setTimeSlot(4L, "FLIER_ORDER_WORKFLOW", 3, null);
        mvc.perform(put("/api/v1/workflows/time-slots/4/FLIER_ORDER_WORKFLOW/3").header(TOKEN, "workflow-token")
                .contentType("application/json").content("{\"value\":5}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("value and unit go together"));
        mvc.perform(put("/api/v1/workflows/time-slots/4/FLIER_ORDER_WORKFLOW/3").header(TOKEN, "workflow-token")
                .contentType("application/json").content("{\"value\":0,\"unit\":\"HOURS\"}")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/workflows/time-slots/4/FLIER_ORDER_WORKFLOW/3").header(TOKEN, "workflow-token"))
                .andExpect(status().isNoContent());
        verify(service).clearTimeSlot(4L, "FLIER_ORDER_WORKFLOW", 3);
        mvc.perform(put("/api/v1/workflows/item-orders/templates/FLIER_ORDER_WORKFLOW/steps/6/sla").header(TOKEN, "workflow-token")
                .contentType("application/json").content("{\"value\":30,\"unit\":\"HOURS\"}")).andExpect(status().isOk());
        verify(service).setDefaultSla("FLIER_ORDER_WORKFLOW", 6, Sla.hours(30));
    }

    @Test void designersAreServedOnTheirOwnPath() throws Exception {
        mvc.perform(post("/api/v1/workflows/designers").header(TOKEN, "workflow-token")
                .contentType("application/json").content("{\"name\":\"Ravi\",\"phone\":\"98450 00000\"}")).andExpect(status().isCreated());
        verify(service).createDesigner("Ravi", "98450 00000");
        mvc.perform(put("/api/v1/workflows/designers/3").header(TOKEN, "workflow-token")
                .contentType("application/json").content("{\"name\":\"Ravi\",\"phone\":\"98450 00000\",\"active\":false}")).andExpect(status().isOk());
        verify(service).updateDesigner(3L, "Ravi", "98450 00000", false);
        mvc.perform(put("/api/v1/workflows/designers/abc").header(TOKEN, "workflow-token")
                .contentType("application/json").content("{}")).andExpect(status().isBadRequest());
    }
}
