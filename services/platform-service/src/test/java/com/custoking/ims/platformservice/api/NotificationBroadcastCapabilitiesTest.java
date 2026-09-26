package com.custoking.ims.platformservice.api;

import com.custoking.ims.platformservice.persistence.NotificationBroadcastCommandRepository;
import com.custoking.ims.platformservice.application.BroadcastDispatchService;
import com.custoking.ims.platformservice.security.TenantContext;
import com.custoking.ims.platformservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.util.Map;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationBroadcastCapabilitiesTest {
    private final BroadcastDispatchService dispatch = mock(BroadcastDispatchService.class);
    private final NotificationBroadcastCommandRepository repository = mock(NotificationBroadcastCommandRepository.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(dispatch.capabilities(true)).thenReturn(Map.of("canCreateDraft", true, "canApprove", true, "canSend", false, "sendUnavailableReason", BroadcastDispatchService.LIVE_BLOCK));
        when(dispatch.capabilities(false)).thenReturn(Map.of("canCreateDraft", false, "canApprove", false, "canSend", false));
        mvc = MockMvcBuilders.standaloneSetup(new NotificationBroadcastCommandController(repository, "test-token", dispatch))
                .addFilters(new TenantContextFilter()).build();
    }

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    @Test
    void superadminCanPrepareAndApproveButDispatchRemainsBlocked() throws Exception {
        mvc.perform(get("/api/v1/notifications/broadcasts/capabilities")
                        .header("X-Notification-Service-Token", "test-token")
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canCreateDraft").value(true))
                .andExpect(jsonPath("$.canApprove").value(true))
                .andExpect(jsonPath("$.canSend").value(false))
                .andExpect(jsonPath("$.sendUnavailableReason").value(BroadcastDispatchService.LIVE_BLOCK));
        verifyNoInteractions(repository);
    }

    @Test
    void schoolAdministratorCannotManageBroadcastDrafts() throws Exception {
        mvc.perform(get("/api/v1/notifications/broadcasts/capabilities")
                        .header("X-Notification-Service-Token", "test-token")
                        .header("X-Authenticated-Role", "SCHOOLADMIN")
                        .header("X-Authenticated-Permissions", "notification:read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canCreateDraft").value(false))
                .andExpect(jsonPath("$.canApprove").value(false))
                .andExpect(jsonPath("$.canSend").value(false));
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsAnUntrustedCapabilityRequest() throws Exception {
        mvc.perform(get("/api/v1/notifications/broadcasts/capabilities"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(repository);
    }
}
