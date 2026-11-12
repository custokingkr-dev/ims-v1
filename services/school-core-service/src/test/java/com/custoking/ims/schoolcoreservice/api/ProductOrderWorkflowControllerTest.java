package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.ProductOrderWorkflowService;
import com.custoking.ims.schoolcoreservice.persistence.ProductOrderWorkflowService.StageView;
import com.custoking.ims.schoolcoreservice.persistence.ProductOrderWorkflowService.WorkflowView;
import com.custoking.ims.schoolcoreservice.security.TenantContextFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProductOrderWorkflowControllerTest {
    ProductOrderWorkflowService workflows;
    MockMvc mvc;

    @BeforeEach void setup() {
        workflows = mock(ProductOrderWorkflowService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ProductOrderWorkflowController(workflows, "test-token"))
                .setControllerAdvice(new CatalogOrderFormExceptionHandler(), new ProductOrderWorkflowRouteExceptionHandler())
                .addFilters(new TenantContextFilter()).build();
    }

    @Test void refusalsCarryTheirReasonAsAMessage() throws Exception {
        when(workflows.completeStage("CK-1", "ORDER_DETAILS", 3L))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Workflow changed; refresh and try again"));
        mvc.perform(post("/api/v1/catalog/orders/CK-1/production-workflow/stages/ORDER_DETAILS/complete")
                        .header("X-Catalog-Service-Token", "test-token").header("X-Authenticated-Role", "SUPERADMIN")
                        .header("X-Authenticated-User-Id", "1").contentType("application/json").content("{\"expectedVersion\":3}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Workflow changed; refresh and try again"));

        when(workflows.view("CK-2")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "This order has no production workflow"));
        mvc.perform(get("/api/v1/catalog/orders/CK-2/production-workflow").header("X-Catalog-Service-Token", "test-token")
                        .header("X-Authenticated-Role", "ADMIN").header("X-Authenticated-User-Id", "1")
                        .header("X-Authenticated-School-Id", "10").header("X-Authenticated-Permissions", "order:read"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("This order has no production workflow"));
    }

    @Test void theServiceTokenAndUpdatePermissionAreCheckedBeforeTheService() throws Exception {
        mvc.perform(get("/api/v1/catalog/orders/CK-1/production-workflow"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("invalid catalog service token"));
        mvc.perform(put("/api/v1/catalog/orders/CK-1/production-workflow/stages/ORDER_DETAILS")
                        .header("X-Catalog-Service-Token", "test-token").header("X-Authenticated-Role", "VIEWER")
                        .header("X-Authenticated-User-Id", "1").header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "order:read")
                        .contentType("application/json").content("{\"data\":{\"details\":\"x\"},\"expectedVersion\":0}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("permission required: order:update"));
        verifyNoInteractions(workflows);
    }

    @Test void anUnreadableBodyIsA400WithAMessage() throws Exception {
        mvc.perform(post("/api/v1/catalog/orders/CK-1/production-workflow/stages/ORDER_DETAILS/complete")
                        .header("X-Catalog-Service-Token", "test-token").header("X-Authenticated-Role", "SUPERADMIN")
                        .header("X-Authenticated-User-Id", "1").contentType("application/json").content("{\"expectedVersion\":\"later\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isNotEmpty());
        verify(workflows, never()).completeStage(anyString(), anyString(), any());
    }

    /** A request that reaches no handler on a workflow path still carries a message (contract A5). */
    @Test void aWrongMethodOnAWorkflowPathIsA405WithAMessage() throws Exception {
        mvc.perform(get("/api/v1/catalog/orders/CK-1/production-workflow/stages/ORDER_DETAILS/complete")
                        .header("X-Catalog-Service-Token", "test-token").header("X-Authenticated-Role", "SUPERADMIN")
                        .header("X-Authenticated-User-Id", "1"))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", "POST"))
                .andExpect(jsonPath("$.message").value("This production workflow request does not accept that method"));
        verifyNoInteractions(workflows);
    }

    @Test void theViewHasTheContractShapeAndNoWhatsAppLinks() throws Exception {
        when(workflows.view("CK-1")).thenReturn(new WorkflowView("CK-1", "FLEX", "ACTIVE", "ORDER_DETAILS", 4, false, false,
                List.of(new StageView("ORDER_DETAILS", 1, "Order Details", "ACTIVE", Map.of(), null, null))));
        mvc.perform(get("/api/v1/catalog/orders/CK-1/production-workflow").header("X-Catalog-Service-Token", "test-token")
                        .header("X-Authenticated-Role", "ADMIN").header("X-Authenticated-User-Id", "1")
                        .header("X-Authenticated-School-Id", "10").header("X-Authenticated-Permissions", "order:read"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.canEdit").value(false)).andExpect(jsonPath("$.canComplete").value(false))
                .andExpect(jsonPath("$.stages[0].order").value(1)).andExpect(jsonPath("$.stages[0].data").isEmpty())
                .andExpect(jsonPath("$.whatsappGroups").doesNotExist());
        mvc.perform(put("/api/v1/catalog/orders/CK-1/production-workflow/whatsapp-groups/VENDOR")
                        .header("X-Catalog-Service-Token", "test-token").header("X-Authenticated-Role", "SUPERADMIN")
                        .header("X-Authenticated-User-Id", "1").contentType("application/json").content("{\"url\":\"https://chat.whatsapp.com/abcdefghijkl\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("This production workflow request does not exist"));
        verify(workflows, never()).saveStage(anyString(), anyString(), any());
        verify(workflows, never()).completeStage(anyString(), anyString(), anyLong());
    }
}
