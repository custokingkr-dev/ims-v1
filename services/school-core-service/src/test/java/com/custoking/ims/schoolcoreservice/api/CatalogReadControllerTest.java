package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.api.dto.CreateCatalogOrderRequest;
import com.custoking.ims.schoolcoreservice.persistence.CatalogReadRepository;
import com.custoking.ims.schoolcoreservice.persistence.CatalogReadRepository.CatalogOrderRow;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CatalogReadControllerTest {

    private final CatalogReadRepository catalog = mock(CatalogReadRepository.class);
    private final CatalogReadController controller = new CatalogReadController(catalog, "catalog-token");

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void itemsRejectsInvalidTokenBeforeQuerying() {
        assertThatThrownBy(() -> controller.items("bad-token"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(catalog, never()).items();
    }

    @Test
    void orderReturnsNotFoundForMissingOrder() {
        TenantContext.set(new TenantContext(1L, "sa@school.test", "SUPERADMIN", null, null));
        when(catalog.order("CK-404")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.order("catalog-token", "CK-404"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void createOrderMapsMissingSchoolToNotFound() {
        // Set SUPERADMIN context so resolveSchoolId(999L) passes through to the repo call
        TenantContext.set(new TenantContext(null, null, "SUPERADMIN", null, null));

        CreateCatalogOrderRequest dto = new CreateCatalogOrderRequest(
                "STATIONERY", 999L, null, null, null, null, null, null, null, null, null, null, null, null);
        when(catalog.createOrder(any())).thenThrow(new IllegalArgumentException("School not found"));

        assertThatThrownBy(() -> controller.createOrder("catalog-token", dto))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(response.getReason()).isEqualTo("School not found");
                });
    }

    @Test
    void markVendorPaidMapsCrossSchoolAccessToForbidden() {
        // Set SUPERADMIN context so resolveSchoolId(5L) passes through to the repo call
        TenantContext.set(new TenantContext(null, null, "SUPERADMIN", null, null));

        // actorId is now sourced from TenantContext (null here), never from the client body.
        Map<String, Object> request = new HashMap<>(Map.of("schoolId", 5L, "actorId", 9L, "notes", "paid offline"));
        when(catalog.markVendorPaid("CK-1001", 5L, null, "paid offline"))
                .thenThrow(new SecurityException("Cross-school access denied"));

        assertThatThrownBy(() -> controller.markVendorPaid("catalog-token", "CK-1001", request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(response.getReason()).isEqualTo("Cross-school access denied");
                });
    }

    /**
     * A refused order action carries its reason as {@code {"message": ...}}, where the order screen looks for it, and keeps its
     * status. Spring's default error body leaves the reason out, so the screen used to say "Request failed with status code 400".
     */
    @Test
    void refusedOrderActionsCarryTheirReasonToTheScreen() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new CatalogOrderFormExceptionHandler(), new CatalogProductFormExceptionHandler(),
                        new ValidationExceptionHandler())
                .addFilters(new com.custoking.ims.schoolcoreservice.security.TenantContextFilter()).build();
        when(catalog.placeOrder("CK-1", 1L)).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "This action is unavailable while the order is PROCESSING"));
        when(catalog.createOrder(any())).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select a school for the order"));
        when(catalog.approveBySuperadmin("CK-1")).thenThrow(new IllegalStateException("Only orders awaiting review can be approved"));
        when(catalog.returnBySuperadmin("CK-1", "")).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "The order changed. Reload it before saving"));
        when(catalog.markDelivered("CK-1", 1L)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "ORDERS module is not enabled for this school"));
        when(catalog.updateOrderStatus("CK-1", "APPROVED")).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Save the superadmin quote before final approval"));
        when(catalog.markDesignApproved("CK-1")).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "This order does not require design approval"));

        mvc.perform(superadmin(post("/api/v1/catalog/orders/CK-1/place")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("This action is unavailable while the order is PROCESSING"));
        mvc.perform(superadmin(post("/api/v1/catalog/orders")).contentType("application/json").content("{\"category\":\"FLEX\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Select a school for the order"));
        mvc.perform(superadmin(post("/api/v1/catalog/orders/CK-1/superadmin-approve")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Only orders awaiting review can be approved"));
        mvc.perform(superadmin(post("/api/v1/catalog/orders/CK-1/superadmin-reject")).contentType("application/json").content("{\"reason\":\"\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("The order changed. Reload it before saving"));
        mvc.perform(superadmin(post("/api/v1/catalog/orders/CK-1/deliver")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("ORDERS module is not enabled for this school"));
        mvc.perform(superadmin(patch("/api/v1/catalog/orders/CK-1/status")).contentType("application/json").content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Save the superadmin quote before final approval"));
        mvc.perform(superadmin(post("/api/v1/catalog/orders/CK-1/design-approved")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("This order does not require design approval"));
        mvc.perform(post("/api/v1/catalog/orders/CK-1/place").header("X-Catalog-Service-Token", "bad-token"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("invalid catalog service token"));
    }

    private static MockHttpServletRequestBuilder superadmin(MockHttpServletRequestBuilder request) {
        return request.header("X-Catalog-Service-Token", "catalog-token").header("X-Authenticated-Role", "SUPERADMIN")
                .header("X-Authenticated-User-Id", "1");
    }

    @Test
    void confirmationCannotReportSuccessWithoutPersistence() {
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        assertThatThrownBy(() -> controller.confirmAnnualPlan("catalog-token", 10L, Map.of("fingerprint", "reviewed")))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @SuppressWarnings("unused")
    private CatalogOrderRow order(String id) {
        return new CatalogOrderRow(
                id,
                "STATIONERY",
                "{}",
                1000L,
                120L,
                1120L,
                "PROCESSING",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "NOT_REQUIRED",
                "PENDING",
                null,
                "1-2 weeks",
                9L,
                OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                null,
                OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                4L,
                1L,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
