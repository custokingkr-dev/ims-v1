package com.custoking.ims.platformservice.api;

import com.custoking.ims.platformservice.persistence.ReportingCommandRepository;
import com.custoking.ims.platformservice.persistence.ReportingReadRepository;
import com.custoking.ims.platformservice.security.TenantContext;
import com.custoking.ims.platformservice.security.TenantContextFilter;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that tenant scope is derived from authenticated context headers, not from the request body.
 * The critical property under test: a client-supplied {@code "superAdmin": true} in the body of a
 * command request MUST NOT elevate a regular tenant caller to cross-tenant access.
 */
class ReportingTenantScopingTest {

    private final ReportingReadRepository reporting = mock(ReportingReadRepository.class);
    private final ReportingCommandRepository commands = mock(ReportingCommandRepository.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ReportingReadController(reporting, commands, "tok"))
            .addFilters(new TenantContextFilter())
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    // ── Recipe A: read endpoint scope ──────────────────────────────────────────

    @Test
    void summary_crossTenantSchoolId_isForbidden() throws Exception {
        mvc.perform(get("/api/v1/reporting/summary?schoolId=99")
                        .header("X-Reporting-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10"))
                .andExpect(status().isForbidden());
        verify(reporting, never()).summary(any());
    }

    @Test
    void summary_omittedSchoolId_scopesToAuthenticatedSchool() throws Exception {
        when(reporting.summary(10L)).thenReturn(Map.of("students", 5));
        mvc.perform(get("/api/v1/reporting/summary")
                        .header("X-Reporting-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10"))
                .andExpect(status().isOk());
        verify(reporting).summary(10L);
    }

    @Test
    void summary_superadmin_canTargetAnySchool() throws Exception {
        when(reporting.summary(99L)).thenReturn(Map.of("students", 3));
        mvc.perform(get("/api/v1/reporting/summary?schoolId=99")
                        .header("X-Reporting-Service-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());
        verify(reporting).summary(99L);
    }

    // ── Client superAdmin body flag must NOT bypass tenant scope ────────────────

    /**
     * ADMIN caller (school 10) sends body {"superAdmin": true, "schoolId": 99}.
     * The body flag must be ignored; the cross-tenant schoolId must result in 403.
     */
    @Test
    void acceptAction_bodyFlagSuperAdmin_doesNotBypassTenantScope() throws Exception {
        UUID id = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        String body = mapper.writeValueAsString(Map.of("superAdmin", true, "schoolId", 99, "actorId", 1));
        mvc.perform(post("/api/v1/reporting/command-center/actions/{id}/accept", id)
                        .header("X-Reporting-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        // Repository must never be invoked regardless of the body flag.
        verify(commands, never()).acceptAction(eq(id), any(), any(), eq(false));
        verify(commands, never()).acceptAction(eq(id), any(), any(), eq(true));
    }

    /**
     * ADMIN caller (school 10) without body superAdmin flag, action belonging to school 99:
     * scope resolves to school 10, repo denies with "Access denied to this action" → 400.
     */
    @Test
    void acceptAction_adminCaller_crossTenantActionDenied_byRepo() throws Exception {
        UUID id = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        // Resolved school = 10 (from auth context); repo enforces action ownership.
        when(commands.acceptAction(eq(id), any(), eq(10L), eq(false)))
                .thenThrow(new IllegalArgumentException("Access denied to this action"));
        String body = mapper.writeValueAsString(Map.of("actorId", 1));
        mvc.perform(post("/api/v1/reporting/command-center/actions/{id}/accept", id)
                        .header("X-Reporting-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    /**
     * SUPERADMIN token: cross-tenant acceptAction is allowed. The superAdmin flag passed to
     * the repo comes from TenantContext (true), not from the body.
     */
    @Test
    void acceptAction_superadminToken_crossTenantAllowed() throws Exception {
        UUID id = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        Map<String, Object> result = Map.of("id", id.toString(), "status", "ACCEPTED");
        when(commands.acceptAction(eq(id), any(), eq(99L), eq(true))).thenReturn(result);
        String body = mapper.writeValueAsString(Map.of("actorId", 1, "schoolId", 99));
        mvc.perform(post("/api/v1/reporting/command-center/actions/{id}/accept", id)
                        .header("X-Reporting-Service-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        verify(commands).acceptAction(eq(id), any(), eq(99L), eq(true));
    }
}
