package com.custoking.ims.schoolcoreservice.api.internal;

import com.custoking.ims.schoolcoreservice.persistence.CatalogOrderFormService;
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

/** Operations' two tracker calls are served on the internal path with the bodies they had before; caller checks live in MachineCallerFilter. */
class CatalogTrackerMachineControllerTest {
    CatalogOrderFormService forms;
    MockMvc mvc;

    @BeforeEach void setup() {
        forms = mock(CatalogOrderFormService.class);
        mvc = MockMvcBuilders.standaloneSetup(new CatalogTrackerMachineController(forms)).build();
    }

    @Test void draftImagesAreListedForTheTracker() throws Exception {
        when(forms.draftImagesForTracker("CK-1")).thenReturn(List.of(Map.of("id", 5, "contentType", "image/png", "originalFilename", "d.png")));
        mvc.perform(get("/api/v1/internal/catalog-orders/CK-1/draft-images"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(5))
                .andExpect(jsonPath("$[0].contentType").value("image/png"))
                .andExpect(jsonPath("$[0].originalFilename").value("d.png"));
    }

    @Test void trackerStatusIsPassedOnWithItsVersionAndAnswersNoContent() throws Exception {
        mvc.perform(put("/api/v1/internal/catalog-orders/CK-1/tracker-status").contentType("application/json")
                        .content("{\"status\":\"PRINTING_PRESS\",\"trackerVersion\":12}"))
                .andExpect(status().isNoContent());
        verify(forms).applyTrackerStatus("CK-1", "PRINTING_PRESS", 12L);
    }
}
