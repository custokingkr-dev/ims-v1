package com.custoking.ims.schoolcoreservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Repository
public class FeeReceiptRepository {

    private final JdbcClient jdbc;
    private final FeeDocumentRenderer documents;

    public FeeReceiptRepository(JdbcClient jdbc, FeeDocumentRenderer documents) {
        this.jdbc = jdbc;
        this.documents = documents;
    }

    public Map<String, Object> byPaymentId(String paymentId) {
        return receipt("p.id = :paymentId", "paymentId", paymentId, "Payment not found");
    }

    public Map<String, Object> byReceiptNumber(String receiptNumber) {
        return receipt("p.receipt_number = :receiptNumber", "receiptNumber", receiptNumber, "Receipt not found");
    }

    public byte[] pdfByPaymentId(String paymentId) {
        return renderReceipt(byPaymentId(paymentId));
    }

    public byte[] pdfByReceiptNumber(String receiptNumber) {
        return renderReceipt(byReceiptNumber(receiptNumber));
    }

    private Map<String, Object> receipt(
            String predicate, String parameterName, String parameterValue, String notFoundMessage) {
        return jdbc.sql("""
                        SELECT p.id, p.amount, p.mode, p.paid_at, p.receipt_number,
                               s.id AS student_id, s.full_name AS student_name
                        FROM fee.payment_records p
                        LEFT JOIN student.students s ON s.id = p.student_id
                        WHERE """ + " " + predicate + " " + """
                        LIMIT 1
                        """)
                .param(parameterName, parameterValue)
                .query((rs, rowNum) -> row(
                        "paymentId", rs.getString("id"),
                        "receiptNumber", rs.getString("receipt_number"),
                        "studentId", rs.getLong("student_id"),
                        "student", rs.getString("student_name"),
                        "amount", rs.getLong("amount"),
                        "mode", rs.getString("mode"),
                        "paidAt", rs.getObject("paid_at", OffsetDateTime.class)))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException(notFoundMessage));
    }

    private byte[] renderReceipt(Map<String, Object> payment) {
        return documents.render("Receipt " + textOrDefault(payment.get("receiptNumber"), "")
                + " | Student: " + textOrDefault(payment.get("studentName"), textOrDefault(payment.get("student"), ""))
                + " | Amount: " + textOrDefault(payment.get("amount"), "0")
                + " | Mode: " + textOrDefault(payment.get("mode"), "")
                + " | Paid at: " + textOrDefault(payment.get("paidAt"), ""));
    }

    private String textOrDefault(Object value, String fallback) {
        if (value == null || String.valueOf(value).isBlank()) return fallback;
        return String.valueOf(value).trim();
    }

    private Map<String, Object> row(Object... kv) {
        if (kv.length % 2 != 0) throw new IllegalArgumentException("row requires key/value pairs");
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put(String.valueOf(kv[i]), kv[i + 1]);
        return map;
    }
}
