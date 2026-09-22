package com.custoking.ims.schoolcoreservice.api.compat;

import com.custoking.ims.schoolcoreservice.persistence.CatalogReadRepository;
import com.custoking.ims.schoolcoreservice.persistence.CatalogReadRepository.CatalogOrderRow;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.HashMap;
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

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void markPaidDelegatesToRepository() {
        // Set SUPERADMIN context so resolveSchoolId(1L) passes through to the repo call
        TenantContext.set(new TenantContext(null, null, "SUPERADMIN", null, null));

        CatalogOrderRow row = order("12");
        // actorId is always sourced from TenantContext (null here), never from the client body's "actorId":9.
        Map<String, Object> request = new HashMap<>(Map.of("schoolId", 1, "actorId", 9L, "notes", "paid offline"));
        when(repo.markVendorPaid("12", 1L, null, "paid offline")).thenReturn(row);

        assertThat(controller.markCatalogVendorPaid("tok", "12", request)).containsEntry("order", row);
        verify(repo).markVendorPaid("12", 1L, null, "paid offline");
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

    private static TenantContext schoolAdmin(long schoolId, String... permissions) {
        return new TenantContext(7L, "admin@school.test", "ADMIN", schoolId, null, java.util.Set.of(), java.util.Set.of(permissions));
    }

    @Test
    void updateOrderStatusIsRefusedForSchoolAdminEvenWithOrderUpdatePermission() {
        // The legacy status column has no state machine; APPROVED is superadmin's decision alone.
        TenantContext.set(schoolAdmin(5L, "order:update", "order:approve"));

        assertThatThrownBy(() -> controller.updateOrderStatus("tok", "12", new HashMap<>(Map.of("status", "APPROVED"))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verify(repo, never()).updateOrderStatus(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void updateOrderStatusDelegatesForSuperAdmin() {
        TenantContext.set(new TenantContext(1L, "sa@custoking.test", "SUPERADMIN", null, null));
        CatalogOrderRow row = order("12");
        when(repo.updateOrderStatus("12", "APPROVED")).thenReturn(row);

        assertThat(controller.updateOrderStatus("tok", "12", new HashMap<>(Map.of("status", "APPROVED")))).isSameAs(row);
        verify(repo).updateOrderStatus("12", "APPROVED");
    }

    @Test
    void createOrderIgnoresClientSuppliedStatusAndAlwaysStartsAsDraft() {
        TenantContext.set(schoolAdmin(5L, "order:create"));
        Map<String, Object> request = new HashMap<>(Map.of("category", "UNIFORMS", "status", "APPROVED", "totalAmount", 1));
        when(repo.createOrder(org.mockito.ArgumentMatchers.anyMap())).thenReturn(order("12"));

        controller.createOrder("tok", request);

        org.mockito.ArgumentCaptor<Map<String, Object>> captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(repo).createOrder(captor.capture());
        assertThat(captor.getValue()).containsEntry("status", "DRAFT");
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
                null,
                null);
    }
}
