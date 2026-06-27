package com.custoking.ims.feeservice.api.compat;

import com.custoking.ims.feeservice.persistence.FeeReadRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeePublicCompatibilityControllerTest {

    @Test
    void feesReceiptsPdfAliasDelegatesToPaymentIdLookup() {
        FeeReadRepository fees = mock(FeeReadRepository.class);
        when(fees.receiptPdfByPaymentId("PMT-1")).thenReturn(new byte[]{1, 2, 3});
        var controller = new FeePublicCompatibilityController(fees, "tok");

        ResponseEntity<byte[]> res = controller.receiptByPaymentIdPdf("tok", "PMT-1");

        assertThat(res.getBody()).containsExactly(1, 2, 3);
    }

    @Test
    void dashboardFeeRemindersDelegateToFeeReminders() {
        FeeReadRepository fees = mock(FeeReadRepository.class);
        when(fees.feeReminderRequests(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("queued", 3));
        var controller = new FeePublicCompatibilityController(fees, "tok");

        assertThat(controller.dashboardFeeReminders("tok", Map.of("schoolId", 1)))
                .containsEntry("queued", 3);
    }

    @Test
    void dashboardFeeRemindersRejectInvalidToken() {
        FeeReadRepository fees = mock(FeeReadRepository.class);
        var controller = new FeePublicCompatibilityController(fees, "tok");

        assertThatThrownBy(() -> controller.dashboardFeeReminders("bad", Map.of("schoolId", 1)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(fees, never()).feeReminderRequests(any(), any(), any(), any(), any());
    }
}
