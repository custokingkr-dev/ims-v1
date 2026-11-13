package com.custoking.ims.operationsservice.api;

import com.custoking.ims.operationsservice.application.OrderWorkflowService;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.WorkflowRuleViolation;
import com.custoking.ims.operationsservice.persistence.OrderWorkflowRepository;
import com.custoking.ims.operationsservice.persistence.WorkflowReadRepository;
import com.custoking.ims.operationsservice.security.TenantContext;
import com.custoking.ims.operationsservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The school-keyed WhatsApp group API: its workflow service token and route scopes, that a refusal carries its reason
 * as {@code {"message": ...}}, and that it shares {@code /api/v1/workflows} with the other workflow controllers without
 * either taking the other's requests.
 */
class SchoolWhatsAppGroupControllerTest {
    static final String LINK = "https://chat.whatsapp.com/AbCdEf1234567890";
    static final String TOKEN = "X-Workflow-Service-Token";

    OrderWorkflowService service;
    WorkflowReadRepository workflows;
    MockMvc mvc;

    @BeforeEach void setup() {
        service = mock(OrderWorkflowService.class);
        workflows = mock(WorkflowReadRepository.class);
        // Every controller mapped under /api/v1/workflows, so the routing below is decided as it is in the service.
        mvc = MockMvcBuilders.standaloneSetup(new SchoolWhatsAppGroupController(service, "workflow-token"),
                new WorkflowReadController(workflows, "workflow-token"),
                new OrderWorkflowController(service, "workflow-token")).build();
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test void bothEndpointsNeedTheWorkflowServiceToken() throws Exception {
        mvc.perform(get("/api/v1/workflows/school-whatsapp-groups/12"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("invalid workflow service token"));
        mvc.perform(get("/api/v1/workflows/school-whatsapp-groups/12").header(TOKEN, "machine-token")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/workflows/school-whatsapp-groups/12/SCHOOL_GROUP")
                        .contentType("application/json").content("{\"url\":\"" + LINK + "\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("invalid workflow service token"));
        mvc.perform(put("/api/v1/workflows/school-whatsapp-groups/12/SCHOOL_GROUP").header(TOKEN, "")
                        .contentType("application/json").content("{\"url\":\"" + LINK + "\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void aBlankConfiguredTokenRefusesEveryCall() throws Exception {
        MockMvc unconfigured = MockMvcBuilders.standaloneSetup(new SchoolWhatsAppGroupController(service, "  ")).build();
        unconfigured.perform(get("/api/v1/workflows/school-whatsapp-groups/12").header(TOKEN, "")).andExpect(status().isUnauthorized());
        unconfigured.perform(get("/api/v1/workflows/school-whatsapp-groups/12").header(TOKEN, "anything")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void readingReturnsTheSchoolsLinksFromTheWorkflowService() throws Exception {
        when(service.schoolWhatsAppGroupLinks(12L)).thenReturn(Map.of("SCHOOL_GROUP", LINK, "INTERNAL_APPROVAL", LINK));
        mvc.perform(get("/api/v1/workflows/school-whatsapp-groups/12").header(TOKEN, "workflow-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.SCHOOL_GROUP").value(LINK))
                .andExpect(jsonPath("$.INTERNAL_APPROVAL").value(LINK));
        verify(service).schoolWhatsAppGroupLinks(12L);
    }

    @Test void savingPassesTheSchoolTargetAndUrlAndAnswersWithTheLinks() throws Exception {
        when(service.setSchoolWhatsAppGroupLink(12L, "INTERNAL_APPROVAL", LINK)).thenReturn(Map.of("INTERNAL_APPROVAL", LINK));
        mvc.perform(put("/api/v1/workflows/school-whatsapp-groups/12/INTERNAL_APPROVAL").header(TOKEN, "workflow-token")
                        .contentType("application/json").content("{\"url\":\"" + LINK + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.INTERNAL_APPROVAL").value(LINK));
        verify(service).setSchoolWhatsAppGroupLink(12L, "INTERNAL_APPROVAL", LINK);

        // A blank or absent url reaches the service as such; the service treats it as "remove the group".
        when(service.setSchoolWhatsAppGroupLink(12L, "SCHOOL_GROUP", "")).thenReturn(Map.of());
        mvc.perform(put("/api/v1/workflows/school-whatsapp-groups/12/SCHOOL_GROUP").header(TOKEN, "workflow-token")
                        .contentType("application/json").content("{\"url\":\"\"}"))
                .andExpect(status().isOk());
        verify(service).setSchoolWhatsAppGroupLink(12L, "SCHOOL_GROUP", "");
        mvc.perform(put("/api/v1/workflows/school-whatsapp-groups/12/SCHOOL_GROUP").header(TOKEN, "workflow-token")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        verify(service).setSchoolWhatsAppGroupLink(12L, "SCHOOL_GROUP", null);
    }

    @Test void aRefusalCarriesItsReasonAsAMessage() throws Exception {
        when(service.schoolWhatsAppGroupLinks(12L)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "No access to this school"));
        mvc.perform(get("/api/v1/workflows/school-whatsapp-groups/12").header(TOKEN, "workflow-token"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("No access to this school"));
        when(service.setSchoolWhatsAppGroupLink(12L, "SCHOOL_GROUP", "http://chat.whatsapp.com/AbCdEf1234567890"))
                .thenThrow(new WorkflowRuleViolation(WorkflowRuleViolation.INVALID_INVITE, "Paste a WhatsApp group invite link"));
        mvc.perform(put("/api/v1/workflows/school-whatsapp-groups/12/SCHOOL_GROUP").header(TOKEN, "workflow-token")
                        .contentType("application/json").content("{\"url\":\"http://chat.whatsapp.com/AbCdEf1234567890\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INVITE"))
                .andExpect(jsonPath("$.message").value("Paste a WhatsApp group invite link"));
        when(service.schoolWhatsAppGroupLinks(13L)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        mvc.perform(get("/api/v1/workflows/school-whatsapp-groups/13").header(TOKEN, "workflow-token"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("Forbidden"));
    }

    @Test void aMalformedSchoolIdOrBodyIsABadRequestWithAMessage() throws Exception {
        mvc.perform(get("/api/v1/workflows/school-whatsapp-groups/twelve").header(TOKEN, "workflow-token"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("schoolId must be a number"));
        mvc.perform(put("/api/v1/workflows/school-whatsapp-groups/twelve/SCHOOL_GROUP").header(TOKEN, "workflow-token")
                        .contentType("application/json").content("{\"url\":\"" + LINK + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("schoolId must be a number"));
        mvc.perform(put("/api/v1/workflows/school-whatsapp-groups/12/SCHOOL_GROUP").header(TOKEN, "workflow-token")
                        .contentType("application/json").content("not json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
        mvc.perform(put("/api/v1/workflows/school-whatsapp-groups/12/SCHOOL_GROUP").header(TOKEN, "workflow-token")
                        .contentType("application/json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
        verifyNoInteractions(service);
    }

    /**
     * Who reaches a school's links, end to end from the gateway's identity headers through the real service (only its
     * repository and transactions stubbed): superadmin reads and writes, an operator assigned to the whole school reads,
     * and an operator scoped there to one item only, notebooks included, is refused with the reason: these are the
     * school-wide rows, and an item-scoped operator reads an order's groups through the order (design D34, D42).
     */
    @Test void onlySuperadminAndTheSchoolsSchoolWideOperatorsReachItsLinks() throws Exception {
        OrderWorkflowRepository repo = mock(OrderWorkflowRepository.class);
        // Rows a school kept under the retired vendor keys are no longer shown (design D34).
        when(repo.whatsappGroupLinks(41L)).thenReturn(Map.of("INTERNAL_APPROVAL", LINK, "PRODUCT_VENDOR", LINK, "board_shree", LINK));
        OrderWorkflowService real = new OrderWorkflowService(repo, null, null, new TransactionTemplate(mock(PlatformTransactionManager.class)),
                Clock.systemUTC(), null, null, null, null, null, null, null, null);
        MockMvc gateway = MockMvcBuilders.standaloneSetup(new SchoolWhatsAppGroupController(real, "workflow-token"))
                .addFilters(new TenantContextFilter()).build();
        String itemOnlyCodes = "workspace:access,notebook-tracker:read,notebook-tracker:act,order:read,order:update";
        String operationsCodes = "workspace:access,order:read,order:update,workflow:read,notebook-tracker:read,notebook-tracker:act";

        for (String scope : List.of("41:BELTS", "41:NOTEBOOKS", "41:BELTS,41:NOTEBOOKS")) {
            gateway.perform(get("/api/v1/workflows/school-whatsapp-groups/41").header(TOKEN, "workflow-token")
                            .header("X-Authenticated-User-Id", "12").header("X-Authenticated-Role", "OPERATIONS")
                            .header("X-Authenticated-Permissions", itemOnlyCodes).header("X-Authenticated-Operator-Scopes", scope))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("No access to this school"));
        }
        // School-wide at another school and item-scoped here is still no school-wide assignment here.
        gateway.perform(get("/api/v1/workflows/school-whatsapp-groups/41").header(TOKEN, "workflow-token")
                        .header("X-Authenticated-User-Id", "12").header("X-Authenticated-Role", "OPERATIONS")
                        .header("X-Authenticated-School-Id", "42").header("X-Authenticated-Permissions", operationsCodes)
                        .header("X-Authenticated-Operator-Schools", "42").header("X-Authenticated-Operator-Scopes", "41:NOTEBOOKS"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("No access to this school"));
        verify(repo, never()).whatsappGroupLinks(anyLong());

        gateway.perform(get("/api/v1/workflows/school-whatsapp-groups/41").header(TOKEN, "workflow-token")
                        .header("X-Authenticated-User-Id", "10").header("X-Authenticated-Role", "OPERATIONS")
                        .header("X-Authenticated-School-Id", "41").header("X-Authenticated-Permissions", operationsCodes)
                        .header("X-Authenticated-Operator-Schools", "41"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.INTERNAL_APPROVAL").value(LINK))
                .andExpect(jsonPath("$.PRODUCT_VENDOR").doesNotExist()).andExpect(jsonPath("$.board_shree").doesNotExist());
        gateway.perform(put("/api/v1/workflows/school-whatsapp-groups/41/INTERNAL_APPROVAL").header(TOKEN, "workflow-token")
                        .header("X-Authenticated-User-Id", "10").header("X-Authenticated-Role", "OPERATIONS")
                        .header("X-Authenticated-School-Id", "41").header("X-Authenticated-Permissions", operationsCodes)
                        .header("X-Authenticated-Operator-Schools", "41")
                        .contentType("application/json").content("{\"url\":\"" + LINK + "\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SUPERADMIN_ONLY"))
                .andExpect(jsonPath("$.message").value("Only superadmin can maintain WhatsApp group links"));
        verify(repo, never()).upsertWhatsAppGroupLink(anyLong(), anyString(), anyString(), anyLong());

        gateway.perform(get("/api/v1/workflows/school-whatsapp-groups/41").header(TOKEN, "workflow-token")
                        .header("X-Authenticated-User-Id", "1").header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.INTERNAL_APPROVAL").value(LINK));
        gateway.perform(put("/api/v1/workflows/school-whatsapp-groups/41/INTERNAL_APPROVAL").header(TOKEN, "workflow-token")
                        .header("X-Authenticated-User-Id", "1").header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType("application/json").content("{\"url\":\" HTTPS://CHAT.WHATSAPP.COM:/AbCdEf1234567890 \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.INTERNAL_APPROVAL").value(LINK));
        verify(repo).upsertWhatsAppGroupLink(41L, "INTERNAL_APPROVAL", LINK, 1L);

        // Only the school's own two groups are kept here; vendor groups live on the vendor records now (D34).
        gateway.perform(put("/api/v1/workflows/school-whatsapp-groups/41/PRODUCT_VENDOR").header(TOKEN, "workflow-token")
                        .header("X-Authenticated-User-Id", "1").header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType("application/json").content("{\"url\":\"" + LINK + "\"}"))
                .andExpect(status().isBadRequest());
        gateway.perform(put("/api/v1/workflows/school-whatsapp-groups/41/SCHOOL_GROUP").header(TOKEN, "workflow-token")
                        .header("X-Authenticated-User-Id", "1").header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType("application/json").content("{\"url\":\"https://example.com/AbCdEf1234567890\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INVITE"));
        verify(repo, never()).upsertWhatsAppGroupLink(anyLong(), org.mockito.ArgumentMatchers.eq("PRODUCT_VENDOR"), anyString(), anyLong());
    }

    /**
     * WorkflowReadController serves GET /api/v1/workflows/{instanceId}/actions; the literal segment here wins for a school
     * path, and a real instance id still reaches the workflow actions.
     */
    @Test void schoolPathsAndWorkflowInstancePathsDoNotCollide() throws Exception {
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        when(service.schoolWhatsAppGroupLinks(anyLong())).thenReturn(Map.of());
        when(workflows.actions(12L)).thenReturn(List.of());

        mvc.perform(get("/api/v1/workflows/school-whatsapp-groups/12").header(TOKEN, "workflow-token")).andExpect(status().isOk());
        verify(service).schoolWhatsAppGroupLinks(12L);
        // "actions" in the school position stays with this controller (and is not a school id) instead of being read as
        // instance "school-whatsapp-groups".
        mvc.perform(get("/api/v1/workflows/school-whatsapp-groups/actions").header(TOKEN, "workflow-token"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("schoolId must be a number"));
        verifyNoInteractions(workflows);

        mvc.perform(get("/api/v1/workflows/12/actions").header(TOKEN, "workflow-token")).andExpect(status().isOk());
        verify(workflows).actions(12L);
    }
}
