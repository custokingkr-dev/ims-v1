package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.catalog.domain.OrderNumbers;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Mints an order's number at placement (design D11), inside the placing transaction, on both the order-form path and the
 * legacy one. The running number is taken from {@code catalog.order_number_counters} with a single-row upsert, never
 * MAX()+1: the counter row stays locked until the placing transaction ends, so concurrent placements of one item code take
 * distinct numbers, and a placement that rolls back leaves a gap (gaps are acceptable). Drafts get no number, and neither
 * does a category without an order code. A number, once minted, never changes (catalog V37's trigger refuses it).
 */
final class OrderNumberAllocator {
    private OrderNumberAllocator() { }

    /**
     * The order's number, minting it when the order is placed and has none; null when its category has no order code.
     * Call it after the placement has set {@code placed_at}, in the same transaction, with the order's school visible.
     */
    static String mint(JdbcClient jdbc, String orderId) {
        Map<String, Object> order = jdbc.sql("""
                SELECT co.order_number, co.status, co.placed_at, pc.order_code AS item_code,
                       s.id AS school_id, s.order_code AS school_code, s.time_zone
                FROM catalog.catalog_orders co
                LEFT JOIN catalog.product_categories pc ON pc.code = co.category
                LEFT JOIN tenant_school.schools s ON s.id = co.school_id
                WHERE co.id = :id
                """).param("id", orderId).query().listOfRows().stream().findFirst().orElse(null);
        if (order == null) return null;
        if (order.get("order_number") != null) return String.valueOf(order.get("order_number"));
        if (order.get("item_code") == null || "DRAFT".equals(order.get("status"))) return null;
        if (order.get("school_code") == null) {
            throw new IllegalStateException("The school of order " + orderId + " is not readable here, so its order number cannot be minted");
        }
        String item = String.valueOf(order.get("item_code"));
        LocalDate day = OrderNumbers.placementDate(instant(order.get("placed_at")), (String) order.get("time_zone"));
        int counter = jdbc.sql("""
                INSERT INTO catalog.order_number_counters (item_code, year, last_value) VALUES (:item, :year, 1)
                ON CONFLICT (item_code, year) DO UPDATE SET last_value = catalog.order_number_counters.last_value + 1
                RETURNING last_value
                """).param("item", item).param("year", OrderNumbers.counterYear(day)).query(Integer.class).single();
        String number = OrderNumbers.format(item, String.valueOf(order.get("school_code")), day, counter);
        jdbc.sql("UPDATE catalog.catalog_orders SET order_number = :number WHERE id = :id AND order_number IS NULL")
                .param("number", number).param("id", orderId).update();
        return number;
    }

    private static Instant instant(Object placedAt) {
        if (placedAt instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (placedAt instanceof OffsetDateTime offset) return offset.toInstant();
        if (placedAt instanceof Instant instant) return instant;
        return Instant.now();
    }
}
