package com.custoking.ims.operationsservice.api;

import com.custoking.ims.operationsservice.api.dto.CreateFirefightingRequestRequest;
import com.custoking.ims.operationsservice.api.dto.CreateQuotationRequest;
import com.custoking.ims.operationsservice.api.dto.VendorPaidRequest;
import com.custoking.ims.operationsservice.persistence.FirefightingReadRepository;
import com.custoking.ims.operationsservice.persistence.FirefightingReadRepository.FirefightingRequestRow;
import com.custoking.ims.operationsservice.persistence.FirefightingReadRepository.QuotationRow;
import com.custoking.ims.operationsservice.security.TenantContext;
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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
        CreateFirefightingRequestRequest req = new CreateFirefightingRequestRequest(
                "Replace extinguishers", null, null, null, null, 4L, null, null, null, null, null);
        Map<String, Object> result = Map.of("code", "FF-1002", "status", "DRAFT");
        when(firefighting.createRequest(anyMap())).thenReturn(result);

        Map<String, Object> response = controller.create("ff-token", req);

        assertThat(response).isSameAs(result);
        verify(firefighting).createRequest(argThat(m ->
                "Replace extinguishers".equals(m.get("title")) && Long.valueOf(4L).equals(m.get("schoolId"))));
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
    void addQuotation_repoIllegalArgument_mapsToBadRequest() {
        CreateQuotationRequest req = new CreateQuotationRequest("Vendor A", 120000L, null, null, null);
        when(firefighting.addQuotation(eq("FF-1001"), anyMap()))
                .thenThrow(new IllegalArgumentException("Request not found"));

        assertThatThrownBy(() -> controller.addQuotation("ff-token", "FF-1001", req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(response.getReason()).isEqualTo("Request not found");
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
        TenantContext.set(new TenantContext(1L, "s@x", "SUPERADMIN", null, null));
        Map<String, Object> result = Map.of("code", "FF-1001", "vendorPaid", true);
        when(firefighting.markVendorPaid(eq("FF-1001"), anyMap())).thenReturn(result);

        Map<String, Object> response = controller.markVendorPaid("ff-token", "FF-1001",
                new VendorPaidRequest(null, 9L, "paid by bank transfer"));

        assertThat(response).isSameAs(result);
        // applyResolvedSchool injects schoolId into the body; paidBy is the trusted authenticated
        // actor (TenantContext), NOT the client-supplied 9L, which must be ignored.
        verify(firefighting).markVendorPaid(eq("FF-1001"),
                argThat(m -> Long.valueOf(1L).equals(m.get("paidBy")) && m.containsKey("schoolId")));
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
                null,
                null,
                null,
                null);
    }
}
