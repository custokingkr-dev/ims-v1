package com.custoking.ims.identityservice.api.internal;

import com.custoking.ims.identityservice.application.OperatorDirectory;
import com.custoking.ims.identityservice.application.OperatorSelection;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The operator directory is served on the internal path with the bodies it had before; caller checks live in MachineCallerFilter. */
class OperatorDirectoryControllerTest {
    private final OperatorDirectory directory = mock(OperatorDirectory.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new OperatorDirectoryController(directory)).build();

    @Test
    void resolveReturnsTheOutcomeAndTheOperatorOrTheCandidates() throws Exception {
        when(directory.resolve(4, "NOTEBOOKS")).thenReturn(new OperatorSelection.Result(OperatorSelection.Outcome.ITEM_SPECIFIC, 7L, List.of(7L)));
        when(directory.resolve(5, "NOTEBOOKS")).thenReturn(new OperatorSelection.Result(OperatorSelection.Outcome.AMBIGUOUS, null, List.of(7L, 9L)));
        mvc.perform(get("/api/v1/internal/operator-directory/resolve").param("schoolId", "4").param("catalogCategory", "NOTEBOOKS"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("ITEM_SPECIFIC")).andExpect(jsonPath("$.userId").value(7));
        mvc.perform(get("/api/v1/internal/operator-directory/resolve").param("schoolId", "5").param("catalogCategory", "NOTEBOOKS"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("AMBIGUOUS"))
                .andExpect(jsonPath("$.userId").doesNotExist()).andExpect(jsonPath("$.candidates.length()").value(2));
    }

    @Test
    void resolveWithABadCategoryIsA400() throws Exception {
        when(directory.resolve(4, "bad code")).thenThrow(new IllegalArgumentException("catalogCategory must be a catalogue category code"));
        mvc.perform(get("/api/v1/internal/operator-directory/resolve").param("schoolId", "4").param("catalogCategory", "bad code"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void eligibleAnswersYesOrNoAndBadInputIsA400() throws Exception {
        when(directory.eligible(7, 4, "NOTEBOOKS")).thenReturn(true);
        when(directory.eligible(8, 4, "NOTEBOOKS")).thenReturn(false);
        when(directory.eligible(7, 4, "bad code")).thenThrow(new IllegalArgumentException("catalogCategory must be a catalogue category code"));
        mvc.perform(get("/api/v1/internal/operator-directory/eligible").param("userId", "7").param("schoolId", "4").param("catalogCategory", "NOTEBOOKS"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.eligible").value(true));
        mvc.perform(get("/api/v1/internal/operator-directory/eligible").param("userId", "8").param("schoolId", "4").param("catalogCategory", "NOTEBOOKS"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.eligible").value(false));
        mvc.perform(get("/api/v1/internal/operator-directory/eligible").param("userId", "7").param("schoolId", "4").param("catalogCategory", "bad code"))
                .andExpect(status().isBadRequest());
    }
}
