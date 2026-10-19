package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.CatalogOrderFormService;
import com.custoking.ims.schoolcoreservice.persistence.CatalogReadRepository;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
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

/** The tracker's machine endpoints need the machine token, which the gateway never carries; user endpoints keep their rules. */
class NotebookTrackerEndpointsTest {
    CatalogOrderFormService forms;
    MockMvc mvc;
    MockMvc noMachineToken;

    @BeforeEach void setup() {
        forms = mock(CatalogOrderFormService.class);
        mvc = MockMvcBuilders.standaloneSetup(new CatalogOrderFormController(forms, mock(CatalogReadRepository.class), "test-token", "machine-token")).build();
        noMachineToken = MockMvcBuilders.standaloneSetup(new CatalogOrderFormController(forms, mock(CatalogReadRepository.class), "test-token", "")).build();
    }
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void draftImagesNeedTheMachineTokenNotTheCatalogServiceToken() throws Exception {
        mvc.perform(get("/api/v1/catalog/orders/CK-1/draft-images")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/catalog/orders/CK-1/draft-images").header("X-Notebook-Tracker-Service-Token", "test-token")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/catalog/orders/CK-1/draft-images").header("X-Catalog-Service-Token", "test-token")).andExpect(status().isUnauthorized());
        verifyNoInteractions(forms);
    }

    @Test void draftImagesAreListedForTheTracker() throws Exception {
        when(forms.draftImagesForTracker("CK-1")).thenReturn(List.of(Map.of("id", 5, "contentType", "image/png")));
        mvc.perform(get("/api/v1/supply/orders/CK-1/draft-images").header("X-Notebook-Tracker-Service-Token", "machine-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(5));
    }

    @Test void trackerStatusNeedsTheMachineTokenAndPassesTheStatusOn() throws Exception {
        mvc.perform(put("/api/v1/catalog/orders/CK-1/tracker-status").contentType("application/json").content("{\"status\":\"PRINTING_PRESS\"}"))
                .andExpect(status().isUnauthorized());
        verify(forms, never()).applyTrackerStatus(any(), any());
        mvc.perform(put("/api/v1/catalog/orders/CK-1/tracker-status").header("X-Notebook-Tracker-Service-Token", "machine-token")
                .contentType("application/json").content("{\"status\":\"PRINTING_PRESS\"}")).andExpect(status().isNoContent());
        verify(forms).applyTrackerStatus("CK-1", "PRINTING_PRESS");
    }

    @Test void aBlankConfiguredMachineTokenDisablesTheMachineEndpoints() throws Exception {
        noMachineToken.perform(get("/api/v1/catalog/orders/CK-1/draft-images").header("X-Notebook-Tracker-Service-Token", "")).andExpect(status().isUnauthorized());
        noMachineToken.perform(put("/api/v1/catalog/orders/CK-1/tracker-status").header("X-Notebook-Tracker-Service-Token", "")
                .contentType("application/json").content("{\"status\":\"X\"}")).andExpect(status().isUnauthorized());
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
        MockMvc withAdvice = MockMvcBuilders.standaloneSetup(new CatalogOrderFormController(forms, mock(CatalogReadRepository.class), "test-token", "machine-token"))
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
        MockMvc withAdvice = MockMvcBuilders.standaloneSetup(new CatalogOrderFormController(forms, mock(CatalogReadRepository.class), "test-token", "machine-token"))
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
