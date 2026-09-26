package com.custoking.ims.billingservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/** Complete platform invoice aggregates; list pagination must never affect totals. */
final class BillingInvoiceStatistics {
    private final JdbcClient jdbc;
    private final String table;
    private final Clock clock;
    private final ZoneId timeZone;

    BillingInvoiceStatistics(JdbcClient jdbc, String table, Clock clock, ZoneId timeZone) {
        if (!table.matches("[a-zA-Z_][a-zA-Z0-9_]*\\.superadmin_invoices")) {
            throw new IllegalArgumentException("Invalid billing invoice table");
        }
        this.jdbc = jdbc;
        this.table = table;
        this.clock = clock;
        this.timeZone = timeZone;
    }

    Map<String, Object> current() {
        LocalDate start = LocalDate.now(clock.withZone(timeZone)).withDayOfMonth(1);
        LocalDate end = start.plusMonths(1);
        Map<String, Object> result = jdbc.sql("""
                SELECT count(*) FILTER (WHERE issue_date >= :start AND issue_date < :end) AS monthly,
                       count(*) FILTER (WHERE lower(trim(status)) = 'paid') AS paid,
                       count(*) FILTER (WHERE lower(trim(status)) = 'awaiting payment') AS pending,
                       COALESCE(sum(total), 0) AS total
                FROM (
                    SELECT status, total,
                           CASE WHEN issued_at ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
                                     AND pg_input_is_valid(issued_at, 'date')
                                THEN issued_at::date
                                ELSE (created_at AT TIME ZONE :zone)::date END AS issue_date
                    FROM %s
                ) invoices
                """.formatted(table))
                .param("start", start).param("end", end).param("zone", timeZone.getId())
                .query((rs, row) -> {
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("sentThisMonth", rs.getLong("monthly"));
                    values.put("paid", rs.getLong("paid"));
                    values.put("pending", rs.getLong("pending"));
                    values.put("totalInvoiced", rs.getLong("total"));
                    return values;
                }).single();
        result.put("periodStart", start.toString());
        result.put("periodEndExclusive", end.toString());
        result.put("reportingTimeZone", timeZone.getId());
        return result;
    }
}
