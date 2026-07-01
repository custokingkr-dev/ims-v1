package com.custoking.ims.identityservice.api;

import com.custoking.ims.identityservice.persistence.RbacCommandRepository;
import com.custoking.ims.identityservice.persistence.RbacReadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RbacValidationTest {

    private static final String VALID_TOKEN = "identity-token";

    RbacCommandRepository commands;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        commands = mock(RbacCommandRepository.class);
        RbacReadRepository reads = mock(RbacReadRepository.class);
        RbacReadController controller = new RbacReadController(reads, commands, VALID_TOKEN);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ValidationExceptionHandler())
                .build();
    }

    @Test
    void createRole_blankName_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/rbac/roles")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.name").exists());
        verifyNoInteractions(commands);
    }

    @Test
    void createRole_validName_callsRepositoryWithKeys() throws Exception {
        when(commands.createRole(anyMap())).thenReturn(Map.of("id", 1));
        mvc.perform(post("/api/v1/rbac/roles")
                        .header("X-Identity-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"name\":\"AUDITOR\",\"description\":\"d\"}"))
                .andExpect(status().isCreated());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(commands).createRole(captor.capture());
        assertEquals("AUDITOR", captor.getValue().get("name"));
        assertEquals("d", captor.getValue().get("description"));
    }
}
