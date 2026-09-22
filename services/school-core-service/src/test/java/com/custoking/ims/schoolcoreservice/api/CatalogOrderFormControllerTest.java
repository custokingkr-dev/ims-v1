package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.CatalogOrderFormService;
import com.custoking.ims.schoolcoreservice.persistence.CatalogReadRepository;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CatalogOrderFormControllerTest {
    CatalogOrderFormService forms;
    CatalogReadRepository orders;
    MockMvc mvc;

    @BeforeEach void setup() {
        forms = mock(CatalogOrderFormService.class);
        orders = mock(CatalogReadRepository.class);
        mvc = MockMvcBuilders.standaloneSetup(new CatalogOrderFormController(forms, orders, "test-token")).build();
    }
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void rejectsMissingServiceTokenBeforeReadingAssets() throws Exception {
        mvc.perform(get("/api/v1/supply/orders/CK-1/assets/1/content")).andExpect(status().isUnauthorized());
        verifyNoInteractions(forms, orders);
    }

    @Test void quoteRequiresSuperadminEvenWithQuotePermission() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@school", "ADMIN", 10L, null, Set.of(), Set.of("catalog:quote")));
        mvc.perform(put("/api/v1/supply/orders/CK-1/quote").header("X-Catalog-Service-Token", "test-token")
                .contentType("application/json").content("{\"version\":1,\"lines\":[],\"gstPaise\":0}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(forms, orders);
    }

    @Test void draftUpdatesRequireUpdatePermission() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@school", "ADMIN", 10L, null, Set.of(), Set.of("order:create")));
        mvc.perform(patch("/api/v1/supply/orders/CK-1").header("X-Catalog-Service-Token", "test-token")
                .contentType("application/json").content("{\"version\":0,\"orderData\":{}}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(forms, orders);
    }

    @Test void streamedFilesArePrivateAndCannotExecuteInline() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@school", "ADMIN", 10L, null, Set.of(), Set.of("order:read")));
        when(forms.content("CK-1", 1L)).thenReturn(new CatalogOrderFormService.AssetContent(new byte[]{1, 2, 3}, "image/png", "design.png"));
        mvc.perform(get("/api/v1/supply/orders/CK-1/assets/1/content").header("X-Catalog-Service-Token", "test-token"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "sandbox"));
    }
}
