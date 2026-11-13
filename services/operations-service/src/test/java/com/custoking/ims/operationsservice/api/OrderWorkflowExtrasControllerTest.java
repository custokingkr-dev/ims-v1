package com.custoking.ims.operationsservice.api;

import com.custoking.ims.operationsservice.application.ActivityService;
import com.custoking.ims.operationsservice.application.ActivityService.ActivityEntry;
import com.custoking.ims.operationsservice.application.ActivityService.ActivityPage;
import com.custoking.ims.operationsservice.application.ActivityService.Kind;
import com.custoking.ims.operationsservice.application.CallLogService;
import com.custoking.ims.operationsservice.application.CallLogService.CallLogEntry;
import com.custoking.ims.operationsservice.application.CallLogService.Contacted;
import com.custoking.ims.operationsservice.application.CallLogService.NewCall;
import com.custoking.ims.operationsservice.application.CallLogService.Outcome;
import com.custoking.ims.operationsservice.application.GroupLinkService;
import com.custoking.ims.operationsservice.application.WorkflowSettingsService;
import com.custoking.ims.operationsservice.application.WorkflowSettingsService.InternalApprovalGroupView;
import com.custoking.ims.operationsservice.application.orderworkflow.GroupLinkResolver.GroupLink;
import com.custoking.ims.operationsservice.application.orderworkflow.GroupLinkResolver.ResolvedGroups;
import com.custoking.ims.operationsservice.application.orderworkflow.GroupLinkResolver.Source;
import com.custoking.ims.operationsservice.application.orderworkflow.VendorLookup.VendorSummary;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.WorkflowRuleViolation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The call log, activity, WhatsApp group and settings endpoints of the order workflows (contract
 * {@code orderWorkflowClient.v1}): their workflow service token, how parameters and bodies bind, the status codes, and
 * that refusals reach the screen as the contract's {@code OrderWorkflowError}.
 */
class OrderWorkflowExtrasControllerTest {
    static final String TOKEN = "X-Workflow-Service-Token";
    static final String BASE = "/api/v1/workflows/item-orders/CK-1042";
    static final String INVITE = "https://chat.whatsapp.com/ItemFlierGroup0001";
    static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-13T06:30:00Z");

    CallLogService calls;
    ActivityService activity;
    WorkflowSettingsService settings;
    GroupLinkService groups;
    MockMvc mvc;

    @BeforeEach void setup() {
        calls = mock(CallLogService.class);
        activity = mock(ActivityService.class);
        settings = mock(WorkflowSettingsService.class);
        groups = mock(GroupLinkService.class);
        mvc = MockMvcBuilders.standaloneSetup(new OrderCallLogController(calls, "workflow-token"),
                new OrderActivityController(activity, "workflow-token"),
                new WorkflowSettingsController(settings, groups, "workflow-token")).build();
    }

    @Test void everyEndpointNeedsTheWorkflowServiceToken() throws Exception {
        for (var request : List.of(get(BASE + "/calls"), post(BASE + "/calls").contentType("application/json").content("{}"),
                get(BASE + "/activity"), get(BASE + "/whatsapp-groups"),
                put(BASE + "/whatsapp-groups/school").contentType("application/json").content("{}"),
                get("/api/v1/workflows/settings/internal-approval-group"),
                put("/api/v1/workflows/settings/internal-approval-group").contentType("application/json").content("{}"))) {
            mvc.perform(request.header(TOKEN, "wrong")).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("invalid workflow service token"));
        }
        verifyNoInteractions(calls, activity, settings, groups);
    }

    // ---- calls ---------------------------------------------------------------------------------------------------------

    @Test void callsAreListedWithEveryContractProperty() throws Exception {
        when(calls.list("CK-1042", 10)).thenReturn(List.of(new CallLogEntry(3, 6, Contacted.SCHOOL, Outcome.CALLBACK_REQUESTED, "Mrs Rao",
                null, null, "Call after the assembly", AT.plusDays(1), 31, "Asha", AT)));
        mvc.perform(get(BASE + "/calls").header(TOKEN, "workflow-token").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]", aMapWithSize(12)))
                .andExpect(jsonPath("$[0].contacted").value("SCHOOL"))
                .andExpect(jsonPath("$[0].outcome").value("CALLBACK_REQUESTED"))
                .andExpect(jsonPath("$[0].nextFollowUpAt").value("2026-11-14T06:30:00Z"))
                .andExpect(jsonPath("$[0].createdByName").value("Asha"));
        mvc.perform(get(BASE + "/calls").header(TOKEN, "workflow-token")).andExpect(status().isOk());
        verify(calls).list("CK-1042", null);
    }

    @Test void loggingACallBindsTheContractsRequestAndAnswersCreated() throws Exception {
        when(calls.log(eq("CK-1042"), any())).thenReturn(new CallLogEntry(4, 5, Contacted.PRINTING_PRESS, Outcome.DELAY_REPORTED,
                "Cityline", 12L, null, "Plates late", null, 31, null, AT));
        mvc.perform(post(BASE + "/calls").header(TOKEN, "workflow-token").contentType("application/json")
                        .content("{\"contacted\":\"PRINTING_PRESS\",\"outcome\":\"DELAY_REPORTED\",\"contactLabel\":null,\"vendorId\":12,"
                                + "\"designerId\":null,\"stepOrder\":5,\"notes\":\"Plates late\",\"nextFollowUpAt\":\"2026-11-14T10:30:00+05:30\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(4))
                .andExpect(jsonPath("$.vendorId").value(12));
        org.mockito.ArgumentCaptor<NewCall> sent = org.mockito.ArgumentCaptor.forClass(NewCall.class);
        verify(calls).log(eq("CK-1042"), sent.capture());
        assertEquals(new NewCall("PRINTING_PRESS", "DELAY_REPORTED", null, 12L, null, 5, "Plates late", null),
                new NewCall(sent.getValue().contacted(), sent.getValue().outcome(), sent.getValue().contactLabel(), sent.getValue().vendorId(),
                        sent.getValue().designerId(), sent.getValue().stepOrder(), sent.getValue().notes(), null));
        assertEquals(OffsetDateTime.parse("2026-11-14T10:30:00+05:30").toInstant(), sent.getValue().nextFollowUpAt().toInstant(),
                "the follow-up is the instant sent, whatever offset it is read in");
    }

    @Test void aCallThatCannotBeReadIsRefusedWithWhatToSend() throws Exception {
        mvc.perform(post(BASE + "/calls").header(TOKEN, "workflow-token").contentType("application/json")
                        .content("{\"contacted\":\"SCHOOL\",\"outcome\":\"CALLBACK_REQUESTED\",\"nextFollowUpAt\":\"tomorrow\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Send the call as JSON: contacted, outcome and the optional fields; "
                        + "nextFollowUpAt is a date and time with its offset, such as 2026-11-14T10:30:00+05:30"));
        mvc.perform(get(BASE + "/calls").header(TOKEN, "workflow-token").param("limit", "ten"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid limit"));
        mvc.perform(get("/api/v1/workflows/item-orders/CK 1042/calls").header(TOKEN, "workflow-token"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid order id"));
        verifyNoInteractions(calls);
    }

    @Test void callRuleViolationsCarryTheirCodes() throws Exception {
        when(calls.log(anyString(), any())).thenThrow(new WorkflowRuleViolation(WorkflowRuleViolation.NOTE_REQUIRED, "Call notes cannot be empty"));
        mvc.perform(post(BASE + "/calls").header(TOKEN, "workflow-token").contentType("application/json")
                        .content("{\"contacted\":\"SCHOOL\",\"outcome\":\"DELAY_REPORTED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOTE_REQUIRED"))
                .andExpect(jsonPath("$.message").value("Call notes cannot be empty"));
        when(calls.list(anyString(), any())).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Order workflow not found"));
        mvc.perform(get(BASE + "/calls").header(TOKEN, "workflow-token"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$", aMapWithSize(1)))
                .andExpect(jsonPath("$.message").value("Order workflow not found"));
    }

    // ---- activity ------------------------------------------------------------------------------------------------------

    @Test void activityPassesItsCursorLimitAndStage() throws Exception {
        ActivityEntry entry = new ActivityEntry("action:12", Kind.ACTION, "STEP_DATA_SAVED", 2, 31L, "Asha", AT, null,
                Map.of("fields", List.of("designBrief")), 3);
        when(activity.activity("CK-1042", OffsetDateTime.parse("2026-11-13T10:00:00.123456+05:30"), 20, 2))
                .thenReturn(new ActivityPage(List.of(entry), AT.minusMinutes(9)));
        mvc.perform(get(BASE + "/activity").header(TOKEN, "workflow-token")
                        .param("before", "2026-11-13T10:00:00.123456+05:30").param("limit", "20").param("step", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", aMapWithSize(2)))
                .andExpect(jsonPath("$.entries[0]", aMapWithSize(10)))
                .andExpect(jsonPath("$.entries[0].id").value("action:12"))
                .andExpect(jsonPath("$.entries[0].kind").value("ACTION"))
                .andExpect(jsonPath("$.entries[0].count").value(3))
                .andExpect(jsonPath("$.entries[0].details.fields[0]").value("designBrief"))
                .andExpect(jsonPath("$.nextBefore").value("2026-11-13T06:21:00Z"));

        when(activity.activity("CK-1042", null, null, null)).thenReturn(new ActivityPage(List.of(), null));
        mvc.perform(get(BASE + "/activity").header(TOKEN, "workflow-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.entries").isEmpty()).andExpect(jsonPath("$.nextBefore").isEmpty());

        mvc.perform(get(BASE + "/activity").header(TOKEN, "workflow-token").param("before", "yesterday"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid before"));
    }

    // ---- WhatsApp groups and settings ---------------------------------------------------------------------------------

    @Test void theOrdersGroupsAreReadAndItsItemGroupSaved() throws Exception {
        ResolvedGroups resolved = new ResolvedGroups(new GroupLink(INVITE, Source.ITEM), GroupLink.none(),
                List.of(new VendorSummary(12, "Cityline Offset", List.of("OFFSET_PRINTERS"), null, "9876543210", "Pune", null, false, true)));
        when(groups.orderGroups("CK-1042")).thenReturn(resolved);
        mvc.perform(get(BASE + "/whatsapp-groups").header(TOKEN, "workflow-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.school.url").value(INVITE))
                .andExpect(jsonPath("$.school.source").value("ITEM"))
                .andExpect(jsonPath("$.internalApproval.source").value("NONE"))
                .andExpect(jsonPath("$.internalApproval", aMapWithSize(2)))
                .andExpect(jsonPath("$.vendors[0]", aMapWithSize(9)))
                .andExpect(jsonPath("$.vendors[0].name").value("Cityline Offset"));

        when(groups.setOrderSchoolGroup("CK-1042", INVITE)).thenReturn(resolved);
        mvc.perform(put(BASE + "/whatsapp-groups/school").header(TOKEN, "workflow-token").contentType("application/json")
                        .content("{\"inviteUrl\":\"" + INVITE + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.school.source").value("ITEM"));
        mvc.perform(put(BASE + "/whatsapp-groups/school").header(TOKEN, "workflow-token").contentType("application/json")
                        .content("{\"inviteUrl\":null}"))
                .andExpect(status().isOk());
        verify(groups).setOrderSchoolGroup("CK-1042", null);

        when(groups.setOrderSchoolGroup("CK-1042", "nope")).thenThrow(new WorkflowRuleViolation(WorkflowRuleViolation.NOT_ASSIGNEE,
                "Only the operator assigned the current stage, or a superadmin, can change this order's group link"));
        mvc.perform(put(BASE + "/whatsapp-groups/school").header(TOKEN, "workflow-token").contentType("application/json")
                        .content("{\"inviteUrl\":\"nope\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_ASSIGNEE"));
    }

    @Test void aMissingBodyIsRefusedRatherThanReadAsRemovingTheLink() throws Exception {
        mvc.perform(put(BASE + "/whatsapp-groups/school").header(TOKEN, "workflow-token"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Send {\"inviteUrl\": ...}"));
        mvc.perform(put("/api/v1/workflows/settings/internal-approval-group").header(TOKEN, "workflow-token"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/workflows/settings/internal-approval-group").header(TOKEN, "workflow-token")
                        .contentType("application/json").content("[1, 2]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Send {\"inviteUrl\": \"https://chat.whatsapp.com/...\"}, or null to remove the link"));
        verify(groups, never()).setOrderSchoolGroup(anyString(), any());
        verifyNoInteractions(settings);
    }

    @Test void theGlobalInternalApprovalGroupIsReadAndSet() throws Exception {
        when(settings.internalApprovalGroup()).thenReturn(new InternalApprovalGroupView(null, null, null));
        mvc.perform(get("/api/v1/workflows/settings/internal-approval-group").header(TOKEN, "workflow-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$", aMapWithSize(3))).andExpect(jsonPath("$.inviteUrl").isEmpty());

        when(settings.setInternalApprovalGroup(INVITE)).thenReturn(new InternalApprovalGroupView(INVITE, AT, 1L));
        mvc.perform(put("/api/v1/workflows/settings/internal-approval-group").header(TOKEN, "workflow-token")
                        .contentType("application/json").content("{\"inviteUrl\":\"" + INVITE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inviteUrl").value(INVITE))
                .andExpect(jsonPath("$.updatedBy").value(1));

        when(settings.setInternalApprovalGroup("x")).thenThrow(new WorkflowRuleViolation(WorkflowRuleViolation.SUPERADMIN_ONLY,
                "Only superadmin can set the internal approval group"));
        mvc.perform(put("/api/v1/workflows/settings/internal-approval-group").header(TOKEN, "workflow-token")
                        .contentType("application/json").content("{\"inviteUrl\":\"x\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SUPERADMIN_ONLY"));
    }
}
