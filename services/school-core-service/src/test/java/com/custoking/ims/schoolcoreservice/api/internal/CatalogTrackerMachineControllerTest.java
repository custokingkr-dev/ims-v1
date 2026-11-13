package com.custoking.ims.schoolcoreservice.api.internal;

import com.custoking.ims.schoolcoreservice.persistence.CatalogOrderFormService;
import com.custoking.ims.schoolcoreservice.persistence.CatalogOrderFormService.WorkflowStepReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The order workflow engine's two calls are served on the internal path; caller checks live in MachineCallerFilter. */
class CatalogTrackerMachineControllerTest {
    CatalogOrderFormService forms;
    MockMvc mvc;

    @BeforeEach void setup() {
        forms = mock(CatalogOrderFormService.class);
        mvc = MockMvcBuilders.standaloneSetup(new CatalogTrackerMachineController(forms)).build();
    }

    @Test void draftImagesAreListedForTheEngine() throws Exception {
        when(forms.draftImagesForTracker("CK-1")).thenReturn(List.of(Map.of("id", 5, "contentType", "image/png", "originalFilename", "d.png")));
        mvc.perform(get("/api/v1/internal/catalog-orders/CK-1/draft-images"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(5))
                .andExpect(jsonPath("$[0].contentType").value("image/png"))
                .andExpect(jsonPath("$[0].originalFilename").value("d.png"));
    }

    @Test void aStatusOnItsOwnIsPassedOnWithItsVersionAndAnswersNoContent() throws Exception {
        mvc.perform(put("/api/v1/internal/catalog-orders/CK-1/tracker-status").contentType("application/json")
                        .content("{\"status\":\"PRINTING_PRESS\",\"trackerVersion\":12}"))
                .andExpect(status().isNoContent());
        verify(forms).applyTrackerStatus("CK-1", "PRINTING_PRESS", 12L, new WorkflowStepReport(null, null, null, null, null));
    }

    /** Design §3.5: the push carries the template and the current step for order lists. */
    @Test void theStepFieldsArePassedOn() throws Exception {
        mvc.perform(put("/api/v1/internal/catalog-orders/CK-2/tracker-status").contentType("application/json")
                        .content("{\"status\":\"PRE_PRINT_QUALITY_CHECK\",\"trackerVersion\":4,\"definitionId\":\"FLIER_ORDER_WORKFLOW\","
                                + "\"stepOrder\":6,\"stepTitle\":\"Pre-Print Quality Check\",\"totalSteps\":9,\"completedSteps\":5}"))
                .andExpect(status().isNoContent());
        verify(forms).applyTrackerStatus("CK-2", "PRE_PRINT_QUALITY_CHECK", 4L,
                new WorkflowStepReport("FLIER_ORDER_WORKFLOW", 6, "Pre-Print Quality Check", 9, 5));
    }
}
