package com.custoking.ims.identityservice.api;

import com.custoking.ims.identityservice.application.PasswordResetService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PasswordResetControllerTest {
    @Test void secretManagerLineEndingsDoNotPreventGatewayRecoveryRequests() throws Exception {
        var service = mock(PasswordResetService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new PasswordResetController(service, " test-peer-token\r\n")).build();
        mvc.perform(get("/api/v1/auth/password-reset/capabilities").header("X-Identity-Service-Token", "test-peer-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(post("/api/v1/auth/password-reset/request").header("X-Identity-Service-Token", "test-peer-token")
                .contentType("application/json").content("{\"email\":\"staff@example.invalid\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/auth/password-reset/confirm").header("X-Identity-Service-Token", "test-peer-token")
                .contentType("application/json").content("{\"token\":\"synthetic-reset-token\",\"password\":\"Synthetic-password-123!\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/password-reset/capabilities").header("X-Identity-Service-Token", "wrong-token"))
                .andExpect(status().isUnauthorized());
        var blank = MockMvcBuilders.standaloneSetup(new PasswordResetController(service, " \r\n")).build();
        blank.perform(get("/api/v1/auth/password-reset/capabilities").header("X-Identity-Service-Token", ""))
                .andExpect(status().isUnauthorized());
        verify(service).request("staff@example.invalid");
        verify(service).confirm("synthetic-reset-token", "Synthetic-password-123!");
    }

    @Test void publicBrowserRoutesStillRequireTheGatewayServiceCredentialAtThePrivatePeer() throws Exception {
        var service = mock(PasswordResetService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new PasswordResetController(service, "test-peer-token")).build();
        mvc.perform(get("/api/v1/auth/password-reset/capabilities")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/password-reset/capabilities").header("X-Identity-Service-Token", "test-peer-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(post("/api/v1/auth/password-reset/request").contentType("application/json").content("{\"email\":\"staff@example.invalid\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/password-reset/request").header("X-Identity-Service-Token", "test-peer-token")
                .contentType("application/json").content("{\"email\":\"staff@example.invalid\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("If the account is eligible")));
        verify(service).request("staff@example.invalid");
        var blank = MockMvcBuilders.standaloneSetup(new PasswordResetController(service, "")).build();
        blank.perform(get("/api/v1/auth/password-reset/capabilities").header("X-Identity-Service-Token", ""))
                .andExpect(status().isUnauthorized());
    }
}
