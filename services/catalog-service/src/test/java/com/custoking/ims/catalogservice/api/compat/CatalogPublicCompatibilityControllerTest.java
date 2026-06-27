package com.custoking.ims.catalogservice.api.compat;

import com.custoking.ims.catalogservice.persistence.CatalogReadRepository;
import com.custoking.ims.catalogservice.persistence.CatalogReadRepository.CatalogOrderRow;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogPublicCompatibilityControllerTest {

    private final CatalogReadRepository repo = mock(CatalogReadRepository.class);
    private final CatalogPublicCompatibilityController controller =
            new CatalogPublicCompatibilityController(repo, "tok");

    @Test
    void markPaidDelegatesToRepository() {
        CatalogOrderRow row = order("12");
        Map<String, Object> request = Map.of("schoolId", 1, "actorId", 9L, "notes", "paid offline");
        when(repo.markVendorPaid("12", 1L, 9L, "paid offline")).thenReturn(row);

        assertThat(controller.markCatalogVendorPaid("tok", "12", request)).containsEntry("order", row);
        verify(repo).markVendorPaid("12", 1L, 9L, "paid offline");
    }

    @Test
    void markPaidRejectsInvalidToken() {
        Map<String, Object> request = Map.of("schoolId", 1);

        assertThatThrownBy(() -> controller.markCatalogVendorPaid("bad", "12", request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(repo, never()).markVendorPaid("12", 1L, null, null);
    }

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
