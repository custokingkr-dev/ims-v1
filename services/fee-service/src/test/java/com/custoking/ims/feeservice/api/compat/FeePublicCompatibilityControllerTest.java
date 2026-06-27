package com.custoking.ims.feeservice.api.compat;

import com.custoking.ims.feeservice.persistence.FeeReadRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
}
