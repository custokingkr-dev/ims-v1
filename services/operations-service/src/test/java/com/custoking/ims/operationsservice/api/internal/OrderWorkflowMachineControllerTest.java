package com.custoking.ims.operationsservice.api.internal;

import com.custoking.ims.operationsservice.application.OrderWorkflowService;
import com.custoking.ims.operationsservice.application.OrderWorkflowService.CreateWorkflow;
import com.custoking.ims.operationsservice.application.OrderWorkflowService.DesignFileAccess;
import com.custoking.ims.operationsservice.application.OrderWorkflowService.ImportedStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * School-core's calls are served on the order-workflow internal paths only; the notebook-tracker paths that were aliases for
 * one release (design D6) are gone. Caller checks live in MachineCallerFilter.
 */
class OrderWorkflowMachineControllerTest {
    /** What a school-core that predates the item order workflows sent: no template, item, number or imported progress. */
    private static final String LEGACY_CREATE = "{\"orderId\":\"CK-1\",\"schoolId\":4,\"customised\":true,\"orderedCount\":2000,"
            + "\"placedAt\":\"2026-10-19T09:30:00+05:30\",\"timeZone\":\"Asia/Kolkata\",\"placedBy\":7,\"assigneeUserId\":42,\"schoolName\":\"School A\"}";
    /** What school-core sends now (OperationsTrackerClient.body). */
    private static final String CREATE = "{\"orderId\":\"CK-9\",\"schoolId\":4,\"customised\":true,\"orderedCount\":300,"
            + "\"placedAt\":\"2026-11-13T09:30:00+05:30\",\"timeZone\":\"Asia/Kolkata\",\"placedBy\":7,\"assigneeUserId\":null,"
            + "\"schoolName\":\"School A\",\"definitionId\":\"FLIER_ORDER_WORKFLOW\",\"categoryCode\":\"FLIERS\",\"itemCode\":\"FE\","
            + "\"itemNoun\":\"flier\",\"orderNumber\":\"FE-SCA_BLR-26-11-13-0001\",\"importedProgress\":[{\"stepCode\":\"DESIGNING_ACTIVITY\","
            + "\"sourceStageCode\":\"DESIGN_ACTIVITY\",\"stepOrder\":2,\"status\":\"COMPLETED\",\"completedAt\":\"2026-11-10T10:00:00Z\","
            + "\"completedBy\":3,\"details\":\"Two drafts shared\",\"note\":null}]}";

    OrderWorkflowService service;
    MockMvc mvc;

    @BeforeEach void setup() {
        service = mock(OrderWorkflowService.class);
        mvc = MockMvcBuilders.standaloneSetup(new OrderWorkflowMachineController(service)).build();
    }

    @Test void createPassesTheOrderAndItsItemOnAndAnswersCreatedOrExisting() throws Exception {
        when(service.create(any())).thenReturn(new OrderWorkflowService.Created(9L, true), new OrderWorkflowService.Created(9L, false));
        mvc.perform(post("/api/v1/internal/order-workflows").contentType("application/json").content(CREATE))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.instanceId").value(9)).andExpect(jsonPath("$.created").value(true));
        mvc.perform(post("/api/v1/internal/order-workflows").contentType("application/json").content(CREATE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(false));

        ArgumentCaptor<CreateWorkflow> sent = ArgumentCaptor.forClass(CreateWorkflow.class);
        verify(service, times(2)).create(sent.capture());
        CreateWorkflow command = sent.getValue();
        assertEquals("CK-9", command.orderId());
        assertEquals("FLIER_ORDER_WORKFLOW", command.definitionId());
        assertEquals("FLIERS", command.categoryCode());
        assertEquals("FE", command.itemCode());
        assertEquals("flier", command.itemNoun());
        assertEquals("FE-SCA_BLR-26-11-13-0001", command.orderNumber());
        assertNull(command.assigneeUserId());
        assertEquals(List.of(new ImportedStep("DESIGNING_ACTIVITY", "DESIGN_ACTIVITY", 2, "COMPLETED",
                OffsetDateTime.parse("2026-11-10T10:00:00Z"), 3L, "Two drafts shared", null)), command.importedProgress());
    }

    @Test void theRetiredNotebookTrackerPathsAreGone() throws Exception {
        mvc.perform(post("/api/v1/internal/notebook-trackers").contentType("application/json").content(LEGACY_CREATE))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/internal/notebook-trackers/CK-1/image-access").contentType("application/json")
                        .content("{\"userId\":8,\"role\":\"OPERATIONS\"}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }

    /** A create without a template still reaches the service, which treats it as a notebook order. */
    @Test void aCreateWithoutATemplateIsPassedOnWithNone() throws Exception {
        when(service.create(any())).thenReturn(new OrderWorkflowService.Created(5L, true));
        mvc.perform(post("/api/v1/internal/order-workflows").contentType("application/json").content(LEGACY_CREATE))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.instanceId").value(5));
        ArgumentCaptor<CreateWorkflow> sent = ArgumentCaptor.forClass(CreateWorkflow.class);
        verify(service).create(sent.capture());
        CreateWorkflow command = sent.getValue();
        assertEquals("CK-1", command.orderId());
        assertEquals(4L, command.schoolId());
        assertTrue(command.customised());
        assertEquals(2000, command.orderedCount());
        assertTrue(command.placedAt().isEqual(OffsetDateTime.parse("2026-10-19T09:30:00+05:30")));
        assertEquals("Asia/Kolkata", command.timeZone());
        assertEquals(7L, command.placedBy());
        assertEquals(42L, command.assigneeUserId());
        assertEquals("School A", command.schoolName());
        assertNull(command.definitionId(), "the service treats a create without a template as a notebook order");
        assertEquals(List.of(), command.importedProgress());
    }

    @Test void createWithoutTheRequiredFactsIsA400() throws Exception {
        mvc.perform(post("/api/v1/internal/order-workflows").contentType("application/json").content("{\"orderId\":\"CK-1\",\"customised\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/internal/order-workflows").contentType("application/json").content("not json"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void aRefusedCreateCarriesItsReason() throws Exception {
        when(service.create(any())).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "itemCode is required"));
        mvc.perform(post("/api/v1/internal/order-workflows").contentType("application/json").content(CREATE))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("itemCode is required"));
    }

    @Test void designFileAccessIsAnswered() throws Exception {
        when(service.designFileAccess("CK-1", 8L, "OPERATIONS")).thenReturn(new DesignFileAccess(false, "locked"));
        mvc.perform(post("/api/v1/internal/order-workflows/CK-1/design-file-access").contentType("application/json")
                        .content("{\"userId\":8,\"role\":\"OPERATIONS\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(false)).andExpect(jsonPath("$.reason").value("locked"));
        verify(service, times(1)).designFileAccess("CK-1", 8L, "OPERATIONS");
    }

    @Test void designFileAccessWithoutAUserIsA400AndCrossedAliasesDoNotExist() throws Exception {
        mvc.perform(post("/api/v1/internal/order-workflows/CK-1/design-file-access").contentType("application/json")
                .content("{\"role\":\"OPERATIONS\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/internal/order-workflows/CK-1/image-access").contentType("application/json")
                .content("{\"userId\":8}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/internal/notebook-trackers/CK-1/design-file-access").contentType("application/json")
                .content("{\"userId\":8}")).andExpect(status().isNotFound());
        verify(service, never()).designFileAccess(any(), anyLong(), any());
    }
}
