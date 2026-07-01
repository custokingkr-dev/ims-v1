package com.custoking.ims.identityservice.api;

import com.custoking.ims.identityservice.persistence.UserDirectoryReadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserDirectoryValidationTest {

    private static final String VALID_TOKEN = "identity-token";

    UserDirectoryReadRepository users;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        users = mock(UserDirectoryReadRepository.class);
        UserDirectoryController controller = new UserDirectoryController(users, VALID_TOKEN);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ValidationExceptionHandler())
                .build();
    }

    @Test
    void resetPassword_shortPassword_returns400() throws Exception {
        mvc.perform(post("/api/v1/users/9/password-reset")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"password\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.password").exists());
        verify(users, never()).resetPassword(anyLong(), any(), any(), any());
    }

    @Test
    void resetPassword_valid_callsRepositoryWithCorrectArgs() throws Exception {
        mvc.perform(post("/api/v1/users/9/password-reset")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"password\":\"longenough\",\"actorId\":1,\"actorEmail\":\"owner@custoking.com\"}"))
                .andExpect(status().isNoContent());
        verify(users).resetPassword(eq(9L), eq("longenough"), eq(1L), eq("owner@custoking.com"));
    }
}
