package com.custoking.ims.operationsservice.api;

import com.custoking.ims.operationsservice.application.GroupLinkHistoryService;
import com.custoking.ims.operationsservice.application.GroupLinkHistoryService.GroupLinkChangePage;
import com.custoking.ims.operationsservice.application.GroupLinkHistoryService.GroupLinkChangeView;
import com.custoking.ims.operationsservice.application.GroupLinkService;
import com.custoking.ims.operationsservice.application.OrderWorkflowService;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.WorkflowRuleViolation;
import com.custoking.ims.operationsservice.persistence.WorkflowReadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The WhatsApp group link change log API: its workflow service token, its optional filters passed through as given, every
 * refusal as {@code {"code"?, "message"}}, and that it shares {@code /api/v1/workflows} with the other workflow
 * controllers without either taking the other's requests.
 */
class WhatsAppGroupChangeControllerTest {
    static final String TOKEN = "X-Workflow-Service-Token";
    static final String LINK = "https://chat.whatsapp.com/AbCdEf1234567890";

    GroupLinkHistoryService history;
    WorkflowReadRepository workflows;
    MockMvc mvc;

    @BeforeEach void setup() {
        history = mock(GroupLinkHistoryService.class);
        workflows = mock(WorkflowReadRepository.class);
        OrderWorkflowService orders = mock(OrderWorkflowService.class);
        GroupLinkService groups = mock(GroupLinkService.class);
        mvc = MockMvcBuilders.standaloneSetup(new WhatsAppGroupChangeController(history, "workflow-token"),
                new WhatsAppGroupImportController(groups, "workflow-token"),
                new SchoolWhatsAppGroupController(orders, groups, "workflow-token"),
                new WorkflowReadController(workflows, "workflow-token"),
                new OrderWorkflowController(orders, "workflow-token")).build();
    }

    @Test void theLogNeedsTheWorkflowServiceToken() throws Exception {
        mvc.perform(get("/api/v1/workflows/whatsapp-groups/changes"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("invalid workflow service token"));
        mvc.perform(get("/api/v1/workflows/whatsapp-groups/changes").header(TOKEN, "machine-token")).andExpect(status().isUnauthorized());
        MockMvc unconfigured = MockMvcBuilders.standaloneSetup(new WhatsAppGroupChangeController(history, "  ")).build();
        unconfigured.perform(get("/api/v1/workflows/whatsapp-groups/changes").header(TOKEN, "")).andExpect(status().isUnauthorized());
        verifyNoInteractions(history, workflows);
    }

    @Test void theFiltersArePassedAsGivenAndThePageAnswered() throws Exception {
        GroupLinkChangeView change = new GroupLinkChangeView(9, OffsetDateTime.parse("2026-11-15T10:00:00Z"), "ITEM", 12L, "FLIERS",
                null, null, null, LINK, "ADDED", "ORDER", "CK-101", 51, "Asha Operator");
        when(history.changes(12L, 40L, 2)).thenReturn(new GroupLinkChangePage(List.of(change), 9L));
        mvc.perform(get("/api/v1/workflows/whatsapp-groups/changes").header(TOKEN, "workflow-token")
                        .param("schoolId", "12").param("before", "40").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].kind").value("ITEM"))
                .andExpect(jsonPath("$.changes[0].categoryCode").value("FLIERS"))
                .andExpect(jsonPath("$.changes[0].after").value(LINK))
                .andExpect(jsonPath("$.changes[0].orderId").value("CK-101"))
                .andExpect(jsonPath("$.changes[0].changedByName").value("Asha Operator"))
                .andExpect(jsonPath("$.nextBefore").value(9));

        when(history.changes(null, null, null)).thenReturn(new GroupLinkChangePage(List.of(), null));
        mvc.perform(get("/api/v1/workflows/whatsapp-groups/changes").header(TOKEN, "workflow-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.changes").isEmpty());
        verify(history).changes(null, null, null);
        verifyNoInteractions(workflows);
    }

    @Test void aRefusalCarriesItsCodeOrReason() throws Exception {
        when(history.changes(null, null, null)).thenThrow(new WorkflowRuleViolation(WorkflowRuleViolation.SUPERADMIN_ONLY,
                "Only superadmin can see who changed WhatsApp group links"));
        mvc.perform(get("/api/v1/workflows/whatsapp-groups/changes").header(TOKEN, "workflow-token"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SUPERADMIN_ONLY"))
                .andExpect(jsonPath("$.message").value("Only superadmin can see who changed WhatsApp group links"));
        when(history.changes(null, null, 500)).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 200"));
        mvc.perform(get("/api/v1/workflows/whatsapp-groups/changes").header(TOKEN, "workflow-token").param("limit", "500"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("limit must be between 1 and 200"));
        mvc.perform(get("/api/v1/workflows/whatsapp-groups/changes").header(TOKEN, "workflow-token").param("before", "last"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid before"));
    }
}
