package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.ProductCatalogRepository;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CatalogProductFormControllerTest {
    private ProductCatalogRepository repository;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        repository = mock(ProductCatalogRepository.class);
        mvc = MockMvcBuilders.standaloneSetup(new CatalogProductFormController(repository, null, new ObjectMapper(), "catalog-token"))
                .setControllerAdvice(new CatalogProductFormExceptionHandler(), new ValidationExceptionHandler()).build();
        TenantContext.set(new TenantContext(1L, "admin@example.test", "SUPERADMIN", null, null));
    }
    @AfterEach void cleanup() { TenantContext.clear(); }

    @Test
    void tokenRequiredForReadsAndWrites() throws Exception {
        mvc.perform(get("/api/v1/supply/product-catalog/categories")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/supply/product-catalog/categories").contentType("application/json").content("{\"code\":\"BOOKS\",\"label\":\"Books\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(repository);
    }

    @Test
    void schoolCannotWriteOrReadInactiveAdminDefinition() throws Exception {
        TenantContext.set(new TenantContext(2L, "school@example.test", "ADMIN", 10L, null));
        mvc.perform(patch("/api/v1/supply/product-catalog/options/3").header("X-Catalog-Service-Token", "catalog-token")
                        .contentType("application/json").content("{\"widthMm\":180,\"heightMm\":270}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(repository);
    }

    @Test
    void adminReadRoutesRequireSuperAdminRegardlessOfAnyRequestValue() throws Exception {
        // The privileged read lives on its own route with an unconditional guard, so no request
        // value decides whether authorization runs (issue #259).
        TenantContext.set(new TenantContext(2L, "school@example.test", "ADMIN", 10L, null));
        mvc.perform(get("/api/v1/supply/product-catalog/admin/categories").header("X-Catalog-Service-Token", "catalog-token"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/supply/product-catalog/admin/forms/NOTEBOOKS").header("X-Catalog-Service-Token", "catalog-token"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(repository);
    }

    @Test
    void adminReadRoutesReturnInactiveDefinitionsForSuperAdmin() throws Exception {
        when(repository.categories(true)).thenReturn(List.of(Map.of("code", "RETIRED")));
        when(repository.form("NOTEBOOKS", true)).thenReturn(Map.of("categoryCode", "NOTEBOOKS"));

        mvc.perform(get("/api/v1/supply/product-catalog/admin/categories").header("X-Catalog-Service-Token", "catalog-token"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/supply/product-catalog/admin/forms/NOTEBOOKS").header("X-Catalog-Service-Token", "catalog-token"))
                .andExpect(status().isOk());

        verify(repository).categories(true);
        verify(repository).form("NOTEBOOKS", true);
    }

    @Test
    void publicReadRoutesAreActiveOnlyAndIgnoreAnyIncludeInactiveParameter() throws Exception {
        // A school admin holding order:read may read the catalog; the parameter no longer exists, so
        // it cannot widen the result to inactive definitions.
        TenantContext.set(new TenantContext(2L, "school@example.test", "ADMIN", 10L, null,
                java.util.Set.of(), java.util.Set.of("order:read")));
        when(repository.categories(false)).thenReturn(List.of(Map.of("code", "NOTEBOOKS")));
        when(repository.form("NOTEBOOKS", false)).thenReturn(Map.of("categoryCode", "NOTEBOOKS"));

        mvc.perform(get("/api/v1/supply/product-catalog/categories?includeInactive=true").header("X-Catalog-Service-Token", "catalog-token"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/supply/product-catalog/forms/NOTEBOOKS?includeInactive=true").header("X-Catalog-Service-Token", "catalog-token"))
                .andExpect(status().isOk());

        verify(repository).categories(false);
        verify(repository).form("NOTEBOOKS", false);
        verify(repository, org.mockito.Mockito.never()).categories(true);
        verify(repository, org.mockito.Mockito.never()).form("NOTEBOOKS", true);
    }

    @Test
    void invalidCodesAndDimensionsReturnFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/supply/product-catalog/options").header("X-Catalog-Service-Token", "catalog-token")
                        .contentType("application/json").content("{\"code\":\"lowercase\",\"widthMm\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.code").exists()).andExpect(jsonPath("$.fieldErrors.widthMm").exists());
        verifyNoInteractions(repository);
    }

    @Test
    void categoryReadHasPrivateCacheAndSupportsNotModified() throws Exception {
        when(repository.categories(false)).thenReturn(List.of(Map.of("code", "NOTEBOOKS")));
        var response = mvc.perform(get("/api/v1/supply/product-catalog/categories").header("X-Catalog-Service-Token", "catalog-token"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, max-age=60"))
                .andExpect(header().exists("ETag")).andReturn();
        mvc.perform(get("/api/v1/supply/product-catalog/categories").header("X-Catalog-Service-Token", "catalog-token")
                        .header("If-None-Match", response.getResponse().getHeader("ETag"))).andExpect(status().isNotModified());
    }

    @Test
    void patchRemainsPartialAndHardDeleteReturnsReferenceCount() throws Exception {
        when(repository.update(eq("options"), eq(3L), anyMap())).thenReturn(Map.of("id", 3, "label", "Long renamed"));
        mvc.perform(patch("/api/v1/supply/product-catalog/options/3").header("X-Catalog-Service-Token", "catalog-token")
                        .contentType("application/json").content("{\"label\":\"Long renamed\"}"))
                .andExpect(status().isOk());
        verify(repository).update("options", 3L, Map.of("label", "Long renamed"));
        when(repository.delete("options", 3L, true)).thenThrow(new ProductCatalogRepository.CatalogReferenceConflict(2));
        mvc.perform(delete("/api/v1/supply/product-catalog/options/3?hard=true").header("X-Catalog-Service-Token", "catalog-token"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.referencingOrderCount").value(2));
    }
}
