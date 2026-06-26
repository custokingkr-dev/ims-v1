package com.custoking.ims.catalogservice.api;

import com.custoking.ims.catalogservice.persistence.CatalogReadRepository;
import com.custoking.ims.catalogservice.persistence.CatalogReadRepository.CatalogOrderRow;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogReadControllerTest {

    private final CatalogReadRepository catalog = mock(CatalogReadRepository.class);
    private final CatalogReadController controller = new CatalogReadController(catalog, "catalog-token");

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
        when(catalog.order("CK-404")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.order("catalog-token", "CK-404"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void createOrderMapsMissingSchoolToNotFound() {
        Map<String, Object> request = Map.of("schoolId", 999L, "category", "STATIONERY");
        when(catalog.createOrder(request)).thenThrow(new IllegalArgumentException("School not found"));

        assertThatThrownBy(() -> controller.createOrder("catalog-token", request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(response.getReason()).isEqualTo("School not found");
                });
    }

    @Test
    void markVendorPaidMapsCrossSchoolAccessToForbidden() {
        Map<String, Object> request = Map.of("schoolId", 5L, "actorId", 9L, "notes", "paid offline");
        when(catalog.markVendorPaid("CK-1001", 5L, 9L, "paid offline"))
                .thenThrow(new SecurityException("Cross-school access denied"));

        assertThatThrownBy(() -> controller.markVendorPaid("catalog-token", "CK-1001", request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(response.getReason()).isEqualTo("Cross-school access denied");
                });
    }

    @Test
    void confirmAnnualPlanReturnsCompatibilityPayload() {
        Object response = controller.confirmAnnualPlan("catalog-token");

        assertThat(response).isEqualTo(Map.of(
                "ok", true,
                "message", "Annual plan confirmed and Custoking notified"));
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
                null);
    }
}
