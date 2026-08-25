package com.custoking.ims.schoolcoreservice.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeeReceiptRepositoryTest {

    private JdbcClient jdbc;
    private JdbcClient.StatementSpec statement;
    private JdbcClient.MappedQuerySpec<Map<String, Object>> query;
    private FeeDocumentRenderer documents;
    private FeeReceiptRepository receipts;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcClient.class);
        statement = mock(JdbcClient.StatementSpec.class);
        query = mock(JdbcClient.MappedQuerySpec.class);
        documents = mock(FeeDocumentRenderer.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(any(RowMapper.class))).thenReturn(query);
        receipts = new FeeReceiptRepository(jdbc, documents);
    }

    @Test
    void findsReceiptByPaymentIdUsingTheCanonicalPaymentPredicate() {
        Map<String, Object> expected = receipt("PAY-1", "RCPT-1");
        when(query.optional()).thenReturn(Optional.of(expected));

        assertThat(receipts.byPaymentId("PAY-1")).isSameAs(expected);

        verify(jdbc).sql(argThat(sql -> sql.contains("WHERE p.id = :paymentId")
                && sql.contains("LEFT JOIN student.students")));
        verify(statement).param("paymentId", "PAY-1");
    }

    @Test
    void findsReceiptByReceiptNumberUsingTheCanonicalReceiptPredicate() {
        Map<String, Object> expected = receipt("PAY-2", "RCPT-2");
        when(query.optional()).thenReturn(Optional.of(expected));

        assertThat(receipts.byReceiptNumber("RCPT-2")).isSameAs(expected);

        verify(jdbc).sql(argThat(sql -> sql.contains("WHERE p.receipt_number = :receiptNumber")));
        verify(statement).param("receiptNumber", "RCPT-2");
    }

    @Test
    void preservesNotFoundMessages() {
        when(query.optional()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> receipts.byPaymentId("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Payment not found");
        assertThatThrownBy(() -> receipts.byReceiptNumber("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Receipt not found");
    }

    @Test
    void rendersReceiptPdfFromThePersistedReceiptShape() {
        Map<String, Object> payment = receipt("PAY-3", "RCPT-3");
        when(query.optional()).thenReturn(Optional.of(payment));
        when(documents.render(anyString())).thenReturn(new byte[] {1, 2, 3});

        assertThat(receipts.pdfByPaymentId("PAY-3")).containsExactly(1, 2, 3);
        verify(documents).render("Receipt RCPT-3 | Student: Ada Lovelace | Amount: 12500 | Mode: UPI"
                + " | Paid at: 2026-08-25T09:30+05:30");
    }

    private Map<String, Object> receipt(String paymentId, String receiptNumber) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("paymentId", paymentId);
        row.put("receiptNumber", receiptNumber);
        row.put("studentId", 42L);
        row.put("student", "Ada Lovelace");
        row.put("amount", 12_500L);
        row.put("mode", "UPI");
        row.put("paidAt", OffsetDateTime.parse("2026-08-25T09:30:00+05:30"));
        return row;
    }
}
