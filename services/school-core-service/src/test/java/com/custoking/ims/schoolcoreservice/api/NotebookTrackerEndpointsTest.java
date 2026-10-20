package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.CatalogOrderFormService;
import com.custoking.ims.schoolcoreservice.persistence.CatalogReadRepository;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The tracker's machine endpoints are no longer on the catalogue path (they live on the internal route); user endpoints keep their rules. */
class NotebookTrackerEndpointsTest {
    CatalogOrderFormService forms;
    MockMvc mvc;

    @BeforeEach void setup() {
        forms = mock(CatalogOrderFormService.class);
        mvc = MockMvcBuilders.standaloneSetup(new CatalogOrderFormController(forms, mock(CatalogReadRepository.class), "test-token")).build();
    }
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void theTrackerMachineEndpointsAreNoLongerOnTheCatalogPath() throws Exception {
        for (String base : new String[]{"/api/v1/catalog/orders", "/api/v1/supply/orders"}) {
            for (String[] header : new String[][]{{"X-Notebook-Tracker-Service-Token", "machine-token"}, {"X-Catalog-Service-Token", "test-token"}}) {
                mvc.perform(get(base + "/CK-1/draft-images").header(header[0], header[1])).andExpect(status().isNotFound());
                mvc.perform(put(base + "/CK-1/tracker-status").header(header[0], header[1])
                        .contentType("application/json").content("{\"status\":\"PRINTING_PRESS\"}")).andExpect(status().isNotFound());
            }
        }
        verifyNoInteractions(forms);
    }

    @Test void trackerSummaryAndEnsureUseTheUsualTokenAndPermissions() throws Exception {
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        mvc.perform(get("/api/v1/catalog/orders/CK-1/tracker-summary")).andExpect(status().isUnauthorized());
        when(forms.trackerSummary("CK-1")).thenReturn(Map.of("orderId", "CK-1"));
        mvc.perform(get("/api/v1/catalog/orders/CK-1/tracker-summary").header("X-Catalog-Service-Token", "test-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.orderId").value("CK-1"));
        when(forms.ensureTracker("CK-1")).thenReturn(Map.of("trackerPending", false));
        mvc.perform(post("/api/v1/catalog/orders/CK-1/tracker/ensure")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/catalog/orders/CK-1/tracker/ensure").header("X-Catalog-Service-Token", "test-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.trackerPending").value(false));
    }

    @Test void aRefusedRequestCarriesItsReasonToTheScreenAsAMessage() throws Exception {
        MockMvc withAdvice = MockMvcBuilders.standaloneSetup(new CatalogOrderFormController(forms, mock(CatalogReadRepository.class), "test-token"))
                .setControllerAdvice(new CatalogOrderFormExceptionHandler()).build();
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        when(forms.upload(any(), any(), any(), any())).thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "No more images allowed. Contact IT."));
        withAdvice.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/supply/orders/CK-1/assets")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "d.png", "image/png", new byte[]{1}))
                        .param("assetKind", "DESIGN_DRAFT").header("X-Catalog-Service-Token", "test-token"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("No more images allowed. Contact IT."));
    }

    @Test void aRefusalWithNoReasonStillGetsAMessage() throws Exception {
        MockMvc withAdvice = MockMvcBuilders.standaloneSetup(new CatalogOrderFormController(forms, mock(CatalogReadRepository.class), "test-token"))
                .setControllerAdvice(new CatalogOrderFormExceptionHandler()).build();
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        when(forms.trackerSummary("CK-1")).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        withAdvice.perform(get("/api/v1/catalog/orders/CK-1/tracker-summary").header("X-Catalog-Service-Token", "test-token"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Not Found"));
    }

    @Test void aUserWithoutOrderPermissionsCannotReadTheSummary() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@school", "ADMIN", 10L, null, Set.of(), Set.of("order:create")));
        mvc.perform(get("/api/v1/catalog/orders/CK-1/tracker-summary").header("X-Catalog-Service-Token", "test-token")).andExpect(status().isForbidden());
        verifyNoInteractions(forms);
    }
}
