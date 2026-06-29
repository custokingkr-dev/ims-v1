package com.custoking.ims.firefightingservice.api;

import com.custoking.ims.firefightingservice.persistence.FirefightingReadRepository;
import com.custoking.ims.firefightingservice.persistence.FirefightingReadRepository.FirefightingRequestRow;
import com.custoking.ims.firefightingservice.persistence.FirefightingReadRepository.QuotationRow;
import com.custoking.ims.firefightingservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FirefightingReadControllerTest {

    private final FirefightingReadRepository firefighting = mock(FirefightingReadRepository.class);
    private final FirefightingReadController controller = new FirefightingReadController(firefighting, "ff-token");

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void requestsRejectsInvalidTokenBeforeQuerying() {
        assertThatThrownBy(() -> controller.requests("wrong-token", 4L, "DRAFT", 25))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(firefighting, never()).requests(4L, "DRAFT", 25);
    }

    @Test
    void requestsDelegatesFiltersWithValidToken() {
        TenantContext.set(new TenantContext(1L, "s@x", "SUPERADMIN", null, null));
        FirefightingRequestRow row = requestRow("FF-1001", "Urgent repair");
        when(firefighting.requests(4L, "PENDING", 25)).thenReturn(List.of(row));

        List<FirefightingRequestRow> response = controller.requests("ff-token", 4L, "PENDING", 25);

        assertThat(response).containsExactly(row);
        verify(firefighting).requests(4L, "PENDING", 25);
    }

    @Test
    void detailMapsValidationFailureToBadRequest() {
        when(firefighting.detail("FF-404")).thenThrow(new IllegalArgumentException("Request not found"));

        assertThatThrownBy(() -> controller.request("ff-token", "FF-404"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(response.getReason()).isEqualTo("Request not found");
                });
    }

    @Test
    void timelineDelegatesRequestCode() {
        List<Map<String, Object>> timeline = List.of(Map.of(
                "status", "CREATED",
                "at", OffsetDateTime.parse("2026-06-27T00:00:00Z")));
        when(firefighting.timeline("FF-1001")).thenReturn(timeline);

        List<Map<String, Object>> response = controller.timeline("ff-token", "FF-1001");

        assertThat(response).isSameAs(timeline);
        verify(firefighting).timeline("FF-1001");
    }

    @Test
    void createDelegatesRequestBody() {
        TenantContext.set(new TenantContext(1L, "s@x", "SUPERADMIN", null, null));
        Map<String, Object> request = Map.of("title", "Replace extinguishers", "schoolId", 4L);
        Map<String, Object> result = Map.of("code", "FF-1002", "status", "DRAFT");
        when(firefighting.createRequest(request)).thenReturn(result);

        Map<String, Object> response = controller.create("ff-token", request);

        assertThat(response).isSameAs(result);
        verify(firefighting).createRequest(request);
    }

    @Test
    void quotationsDelegatesRequestCode() {
        QuotationRow row = new QuotationRow(
                "quote-1",
                "Vendor A",
                120000L,
                "2 days",
                "Available",
                null,
                false,
                true,
                OffsetDateTime.parse("2026-06-27T00:00:00Z"),
                "FF-1001");
        when(firefighting.quotations("FF-1001")).thenReturn(List.of(row));

        List<QuotationRow> response = controller.quotations("ff-token", "FF-1001");

        assertThat(response).containsExactly(row);
        verify(firefighting).quotations("FF-1001");
    }

    @Test
    void addQuotationMapsValidationFailureToBadRequest() {
        Map<String, Object> request = Map.of("amount", 120000L);
        when(firefighting.addQuotation("FF-1001", request))
                .thenThrow(new IllegalArgumentException("vendorName is required"));

        assertThatThrownBy(() -> controller.addQuotation("ff-token", "FF-1001", request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(response.getReason()).isEqualTo("vendorName is required");
                });
    }

    @Test
    void approveBursarUsesEmptyBodyWhenRequestIsMissing() {
        Map<String, Object> result = Map.of("code", "FF-1001", "status", "BURSAR_APPROVED");
        when(firefighting.approveBursar("FF-1001", Map.of())).thenReturn(result);

        Map<String, Object> response = controller.approveBursar("ff-token", "FF-1001", null);

        assertThat(response).isSameAs(result);
        verify(firefighting).approveBursar("FF-1001", Map.of());
    }

    @Test
    void markVendorPaidDelegatesProvidedBody() {
        Map<String, Object> request = Map.of("actorId", 9L, "notes", "paid by bank transfer");
        Map<String, Object> result = Map.of("code", "FF-1001", "vendorPaid", true);
        when(firefighting.markVendorPaid("FF-1001", request)).thenReturn(result);

        Map<String, Object> response = controller.markVendorPaid("ff-token", "FF-1001", request);

        assertThat(response).isSameAs(result);
        verify(firefighting).markVendorPaid("FF-1001", request);
    }

    private FirefightingRequestRow requestRow(String code, String title) {
        return new FirefightingRequestRow(
                code,
                title,
                "EQUIPMENT",
                "HIGH",
                LocalDate.parse("2026-07-01"),
                120000L,
                "Replace expired extinguishers",
                null,
                9L,
                "PENDING",
                null,
                null,
                null,
                null,
                null,
                null,
                "{}",
                null,
                null,
                OffsetDateTime.parse("2026-06-27T00:00:00Z"),
                4L,
                0L,
                "Admin",
                "Admin",
                null,
                null,
                null);
    }
}
