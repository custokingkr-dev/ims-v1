package com.custoking.ims.catalogservice.api;

import com.custoking.ims.catalogservice.persistence.CatalogReadRepository;
import com.custoking.ims.catalogservice.persistence.CatalogReadRepository.AnnualPlanItemRow;
import com.custoking.ims.catalogservice.persistence.CatalogReadRepository.CatalogOrderRow;
import com.custoking.ims.catalogservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CatalogValidationTest {

    private static final String VALID_TOKEN = "catalog-token";

    CatalogReadRepository catalog;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        catalog = mock(CatalogReadRepository.class);
        CatalogReadController controller = new CatalogReadController(catalog, VALID_TOKEN);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ValidationExceptionHandler())
                .build();
        // Set SUPERADMIN context so tenant-scoping passes through
        TenantContext.set(new TenantContext(null, null, "SUPERADMIN", null, null));
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    // ── POST /orders ──────────────────────────────────────────────────────────

    @Test
    void createOrder_blankCategory_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/catalog/orders")
                        .header("X-Catalog-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"category\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.category").exists());
        verifyNoInteractions(catalog);
    }

    @Test
    void createOrder_missingCategory_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/catalog/orders")
                        .header("X-Catalog-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.category").exists());
        verifyNoInteractions(catalog);
    }

    @Test
    void createOrder_valid_callsRepoWithCorrectKeys() throws Exception {
        CatalogOrderRow stubOrder = stubOrderRow("CK-1001");
        when(catalog.createOrder(any())).thenReturn(stubOrder);

        mvc.perform(post("/api/v1/catalog/orders")
                        .header("X-Catalog-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"category\":\"STATIONERY\",\"schoolId\":4,\"subtotal\":1000,\"gst\":120,\"totalAmount\":1120,\"notes\":\"test order\"}"))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(catalog).createOrder(captor.capture());
        Map<String, Object> body = captor.getValue();
        assertEquals("STATIONERY", body.get("category"));
        assertEquals(1000L, ((Number) body.get("subtotal")).longValue());
        assertEquals(120L, ((Number) body.get("gst")).longValue());
        assertEquals(1120L, ((Number) body.get("totalAmount")).longValue());
        assertEquals("test order", body.get("notes"));
        // schoolId resolved through applyResolvedSchool (SUPERADMIN context passes 4L through)
        assertEquals(4L, ((Number) body.get("schoolId")).longValue());
    }

    @Test
    void createOrder_valid_nullableFieldsOmittedFromBody() throws Exception {
        CatalogOrderRow stubOrder = stubOrderRow("CK-1002");
        when(catalog.createOrder(any())).thenReturn(stubOrder);

        mvc.perform(post("/api/v1/catalog/orders")
                        .header("X-Catalog-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"category\":\"IDCARDS\"}"))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(catalog).createOrder(captor.capture());
        Map<String, Object> body = captor.getValue();
        assertEquals("IDCARDS", body.get("category"));
        assertTrue(!body.containsKey("subtotal"), "subtotal should be absent when not sent");
        assertTrue(!body.containsKey("notes"), "notes should be absent when not sent");
    }

    // ── POST /annual-plan/items ───────────────────────────────────────────────

    @Test
    void saveAnnualPlanItem_blankCategory_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/catalog/annual-plan/items")
                        .header("X-Catalog-Service-Token", VALID_TOKEN)
                        .param("schoolId", "4")
                        .contentType("application/json")
                        .content("{\"category\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.category").exists());
        verify(catalog, never()).saveAnnualPlanItem(anyLong(), any());
    }

    @Test
    void saveAnnualPlanItem_missingCategory_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/catalog/annual-plan/items")
                        .header("X-Catalog-Service-Token", VALID_TOKEN)
                        .param("schoolId", "4")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.category").exists());
        verify(catalog, never()).saveAnnualPlanItem(anyLong(), any());
    }

    @Test
    void saveAnnualPlanItem_valid_callsRepoWithCorrectKeys() throws Exception {
        AnnualPlanItemRow stubItem = stubAnnualPlanItemRow();
        when(catalog.saveAnnualPlanItem(anyLong(), any())).thenReturn(stubItem);

        mvc.perform(post("/api/v1/catalog/annual-plan/items")
                        .header("X-Catalog-Service-Token", VALID_TOKEN)
                        .param("schoolId", "4")
                        .contentType("application/json")
                        .content("{\"category\":\"NOTEBOOKS\",\"termName\":\"Term 1\",\"description\":\"Ruled notebooks\",\"quantity\":\"200 units\",\"estimatedAmount\":5000}"))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Long> schoolCaptor = ArgumentCaptor.forClass(Long.class);
        verify(catalog).saveAnnualPlanItem(schoolCaptor.capture(), bodyCaptor.capture());

        // schoolId resolved via TenantScope.resolveSchoolId (SUPERADMIN passes 4L through)
        assertEquals(4L, schoolCaptor.getValue());
        Map<String, Object> body = bodyCaptor.getValue();
        assertEquals("NOTEBOOKS", body.get("category"));
        assertEquals("Term 1", body.get("termName"));
        assertEquals("Ruled notebooks", body.get("description"));
        assertEquals("200 units", body.get("quantity"));
        assertEquals(5000L, ((Number) body.get("estimatedAmount")).longValue());
    }

    @Test
    void saveAnnualPlanItem_valid_nullableFieldsOmittedFromBody() throws Exception {
        AnnualPlanItemRow stubItem = stubAnnualPlanItemRow();
        when(catalog.saveAnnualPlanItem(anyLong(), any())).thenReturn(stubItem);

        mvc.perform(post("/api/v1/catalog/annual-plan/items")
                        .header("X-Catalog-Service-Token", VALID_TOKEN)
                        .param("schoolId", "4")
                        .contentType("application/json")
                        .content("{\"category\":\"STATIONERY\"}"))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(catalog).saveAnnualPlanItem(anyLong(), bodyCaptor.capture());
        Map<String, Object> body = bodyCaptor.getValue();
        assertEquals("STATIONERY", body.get("category"));
        assertTrue(!body.containsKey("termName"), "termName should be absent when not sent");
        assertTrue(!body.containsKey("estimatedAmount"), "estimatedAmount should be absent when not sent");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private CatalogOrderRow stubOrderRow(String id) {
        return new CatalogOrderRow(
                id, "STATIONERY", "{}", 1000L, 120L, 1120L, "DRAFT",
                null, null, null, null, null, null, null, null,
                "NOT_REQUIRED", "NOT_REQUIRED", null, "1-2 weeks",
                null, null, null, OffsetDateTime.now(), 4L, 0L,
                null, null, null, null, null);
    }

    private AnnualPlanItemRow stubAnnualPlanItemRow() {
        return new AnnualPlanItemRow(
                "item-1", "Term 1", "NOTEBOOKS", "Ruled notebooks",
                "200 units", 5000L, "PLANNED", null,
                OffsetDateTime.now(), 4L, "AY-2025");
    }
}
