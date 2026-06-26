package com.custoking.ims.feeservice.api;

import com.custoking.ims.feeservice.persistence.FeeReadRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeeReadControllerTest {

    private final FeeReadRepository fees = mock(FeeReadRepository.class);
    private final FeeReadController controller = new FeeReadController(fees, "fee-token");

    @Test
    void bandsRejectsInvalidTokenBeforeQuerying() {
        assertThatThrownBy(() -> controller.bands("wrong-token", "2026"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(fees, never()).bands("2026");
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
        Map<String, Object> request = Map.of("classFrom", 5, "classTo", 1);
        when(fees.createBand(request)).thenThrow(new IllegalArgumentException("Class to must be >= class from"));

        assertThatThrownBy(() -> controller.createBand("fee-token", request))
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
