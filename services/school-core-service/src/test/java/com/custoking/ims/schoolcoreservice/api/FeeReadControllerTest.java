package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.api.dto.CreateBandRequest;
import com.custoking.ims.schoolcoreservice.persistence.FeeReadRepository;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeeReadControllerTest {

    private final FeeReadRepository fees = mock(FeeReadRepository.class);
    private final FeeReadController controller = new FeeReadController(fees, "fee-token");

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void bandsRejectsInvalidTokenBeforeQuerying() {
        assertThatThrownBy(() -> controller.bands("wrong-token", "2026", null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(fees, never()).bands("2026", null);
    }

    @Test
    void exportStructureRejectsUnsupportedFormat() {
        assertThatThrownBy(() -> controller.exportStructure("fee-token", "2026", "csv"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(response.getReason()).isEqualTo("Only PDF export is supported");
                });

        verify(fees, never()).feeStructurePdf("2026");
    }

    @Test
    void createBandMapsRepositoryValidationToBadRequest() {
        // DTO with name present but invalid class range — repo throws IllegalArgumentException
        TenantContext.set(new TenantContext(
                1L,
                "admin@school.test",
                "ADMIN",
                10L,
                null,
                Set.of(),
                Set.of("fee_structure:manage")));
        CreateBandRequest req = new CreateBandRequest("General", 5, 1, null, null, null);
        when(fees.createBand(anyMap())).thenThrow(new IllegalArgumentException("Class to must be >= class from"));

        assertThatThrownBy(() -> controller.createBand("fee-token", req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(response.getReason()).isEqualTo("Class to must be >= class from");
                });
    }

    @Test
    void deleteBandReturnsCompatibilityPayloadAfterRepositoryDelete() {
        Map<String, Object> response = controller.deleteBand("fee-token", "band-1");

        assertThat(response).isEqualTo(Map.of("removed", true, "bandId", "band-1"));
        verify(fees).deleteBand("band-1");
    }

    @Test
    void paymentsDelegatesFiltersWithValidToken() {
        when(fees.payments(990001L, "assignment-1", 25)).thenReturn(List.of());

        Object response = controller.payments("fee-token", 990001L, "assignment-1", 25);

        assertThat(response).isEqualTo(List.of());
        verify(fees).payments(990001L, "assignment-1", 25);
    }
}
