package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeeReadRepositoryReceiptDelegationTest {

    @Test
    void preservesReceiptPublicApiWhileDelegatingTheExtractedConcern() {
        FeeReceiptRepository receipts = mock(FeeReceiptRepository.class);
        Map<String, Object> payment = Map.of("paymentId", "PAY-1");
        byte[] pdf = {4, 5, 6};
        when(receipts.byPaymentId("PAY-1")).thenReturn(payment);
        when(receipts.byReceiptNumber("RCPT-1")).thenReturn(payment);
        when(receipts.pdfByPaymentId("PAY-1")).thenReturn(pdf);
        when(receipts.pdfByReceiptNumber("RCPT-1")).thenReturn(pdf);
        FeeReadRepository repository = new FeeReadRepository(
                mock(JdbcClient.class),
                mock(OutboxWriter.class),
                receipts,
                mock(FeeDocumentRenderer.class));

        assertThat(repository.receiptByPaymentId("PAY-1")).isSameAs(payment);
        assertThat(repository.receiptByReceiptNumber("RCPT-1")).isSameAs(payment);
        assertThat(repository.receiptPdfByPaymentId("PAY-1")).isSameAs(pdf);
        assertThat(repository.receiptPdfByReceiptNumber("RCPT-1")).isSameAs(pdf);
        verify(receipts).byPaymentId("PAY-1");
        verify(receipts).byReceiptNumber("RCPT-1");
        verify(receipts).pdfByPaymentId("PAY-1");
        verify(receipts).pdfByReceiptNumber("RCPT-1");
    }
}
