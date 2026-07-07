package com.custoking.ims.operationsservice.persistence;

import com.custoking.ims.operationsservice.outbox.OutboxWriter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class FirefightingReadRepository {

    private static final String FIREFIGHTING_REQUEST_UPSERTED = "firefighting-request.upserted.v1";

    private final JdbcClient jdbc;
    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;

    public FirefightingReadRepository(JdbcClient jdbc, OutboxWriter outboxWriter, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.outboxWriter = outboxWriter;
        this.objectMapper = objectMapper;
    }

    public List<FirefightingRequestRow> requests(Long schoolId, String status, int limit) {
        StringBuilder sql = new StringBuilder(requestSelect()).append(" WHERE 1=1");
        if (schoolId != null) sql.append(" AND school_id = :schoolId");
        if (status != null && !status.isBlank()) sql.append(" AND status = :status");
        sql.append(" ORDER BY created_at DESC NULLS LAST LIMIT :limit");

        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 500)));
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        if (status != null && !status.isBlank()) spec = spec.param("status", status);
        return spec.query(FirefightingRequestRow.class).list();
    }

    public List<FirefightingRequestRow> pending(Long schoolId, int limit) {
        StringBuilder sql = new StringBuilder(requestSelect()).append("""
                 WHERE status IN ('AWAITING_BURSAR', 'AWAITING_PRINCIPAL', 'APPROVED')
                """);
        if (schoolId != null) sql.append(" AND school_id = :schoolId");
        sql.append(" ORDER BY created_at DESC NULLS LAST LIMIT :limit");

        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 500)));
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        return spec.query(FirefightingRequestRow.class).list();
    }

    public Optional<FirefightingRequestRow> request(String code) {
        return jdbc.sql(requestSelect() + " WHERE code = :code")
                .param("code", code)
                .query(FirefightingRequestRow.class)
                .optional();
    }

    public Map<String, Object> detail(String code) {
        return detailRow(code);
    }

    public List<Map<String, Object>> timeline(String code) {
        FirefightingRequestRow row = request(code)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));
        var events = new java.util.ArrayList<Map<String, Object>>();
        addEvent(events, "CREATED", row.createdAt());
        addEvent(events, "BURSAR_APPROVED", row.bursarApprovedAt());
        addEvent(events, "PRINCIPAL_APPROVED", row.principalApprovedAt());
        addEvent(events, "VENDOR_PAID", row.vendorPaidAt());
        addEvent(events, "CUSTOKING_APPROVED", row.custokingApprovedAt());
        addEvent(events, "FULFILLED", row.fulfilledAt());
        addEvent(events, "REJECTED", row.rejectedAt());
        events.sort(java.util.Comparator.comparing(event -> (OffsetDateTime) event.get("at")));
        return events;
    }

    public List<QuotationRow> quotations(String requestId) {
        return jdbc.sql("""
                SELECT id, vendor_name, amount, delivery_timeline, notes, document_url,
                       is_custoking, is_recommended, created_at, request_id
                FROM ff_quotations
                WHERE request_id = :requestId
                ORDER BY is_recommended DESC, amount ASC, created_at ASC NULLS LAST
                """).param("requestId", requestId)
                .query(QuotationRow.class)
                .list();
    }

    public List<Map<String, Object>> stats(Long schoolId) {
        if (schoolId == null) {
            return jdbc.sql("""
                    SELECT COALESCE(status, 'UNKNOWN') AS status, count(*) AS count, COALESCE(sum(estimated_budget), 0) AS estimated_budget
                    FROM firefighting_requests
                    GROUP BY COALESCE(status, 'UNKNOWN')
                    ORDER BY status
                    """).query().listOfRows();
        }
        return jdbc.sql("""
                SELECT COALESCE(status, 'UNKNOWN') AS status, count(*) AS count, COALESCE(sum(estimated_budget), 0) AS estimated_budget
                FROM firefighting_requests
                WHERE school_id = :schoolId
                GROUP BY COALESCE(status, 'UNKNOWN')
                ORDER BY status
                """).param("schoolId", schoolId)
                .query()
                .listOfRows();
    }

    @Transactional
    public Map<String, Object> createRequest(Map<String, Object> request) {
        Long schoolId = longValue(request.get("schoolId"), null);
        if (schoolId == null) {
            throw new IllegalArgumentException("schoolId is required");
        }
        String code = nextCode();
        String category = str(request.get("category"), "Other");
        jdbc.sql("""
                INSERT INTO firefighting_requests(code, title, category, urgency, required_by_date,
                                                  estimated_budget, description, reference_file_url,
                                                  raised_by, status, custoking_criteria_json,
                                                  created_at, school_id, version, created_by, updated_by)
                VALUES (:code, :title, :category, :urgency, :requiredByDate, :estimatedBudget,
                        :description, :referenceFileUrl, :raisedBy, 'DRAFT', :criteria,
                        :createdAt, :schoolId, 0, :createdBy, :updatedBy)
                """)
                .param("code", code)
                .param("title", str(request.get("title"), "Request"))
                .param("category", category)
                .param("urgency", str(request.get("urgency"), "MEDIUM").toUpperCase(Locale.ROOT))
                .param("requiredByDate", parseDate(str(request.get("requiredByDate"), "")))
                .param("estimatedBudget", longValue(request.get("estimatedBudget"), 0L))
                .param("description", str(firstPresent(request, "description", "summary"), ""))
                .param("referenceFileUrl", trimToNull(str(request.get("referenceFileUrl"), "")))
                .param("raisedBy", longValue(request.get("actorId"), null))
                .param("criteria", criteriaJson(category))
                .param("createdAt", OffsetDateTime.now())
                .param("schoolId", schoolId)
                .param("createdBy", textOrNull(request.get("actorEmail")))
                .param("updatedBy", textOrNull(request.get("actorEmail")))
                .update();
        emitUpserted(code);
        return detailRow(code);
    }

    @Transactional
    public Map<String, Object> updateRequest(String code, Map<String, Object> request) {
        Map<String, Object> current = requestMap(code);
        requireStatus(current, "DRAFT");
        jdbc.sql("""
                UPDATE firefighting_requests
                SET title = :title, category = :category, urgency = :urgency, required_by_date = :requiredByDate,
                    estimated_budget = :estimatedBudget, description = :description, updated_by = :updatedBy
                WHERE code = :code
                """)
                .param("code", code)
                .param("title", request.containsKey("title") ? str(request.get("title"), str(current.get("title"), "Request")) : current.get("title"))
                .param("category", request.containsKey("category") ? str(request.get("category"), str(current.get("category"), "Other")) : current.get("category"))
                .param("urgency", request.containsKey("urgency") ? str(request.get("urgency"), str(current.get("urgency"), "MEDIUM")).toUpperCase(Locale.ROOT) : current.get("urgency"))
                .param("requiredByDate", request.containsKey("requiredByDate") ? parseDate(str(request.get("requiredByDate"), "")) : current.get("requiredByDate"))
                .param("estimatedBudget", request.containsKey("estimatedBudget") ? longValue(request.get("estimatedBudget"), 0L) : current.get("estimatedBudget"))
                .param("description", request.containsKey("description") ? str(request.get("description"), "") : current.get("description"))
                .param("updatedBy", textOrNull(request.get("actorEmail")))
                .update();
        emitUpserted(code);
        return detailRow(code);
    }

    @Transactional
    public Map<String, Object> addQuotation(String code, Map<String, Object> request) {
        Map<String, Object> current = requestMap(code);
        requireStatus(current, "DRAFT");
        String id = UUID.randomUUID().toString();
        String vendor = str(request.get("vendorName"), "Vendor");
        Long schoolId = longValue(current.get("schoolId"), null);
        jdbc.sql("""
                INSERT INTO ff_quotations(id, vendor_name, amount, delivery_timeline, notes, document_url,
                                          is_custoking, is_recommended, created_at, request_id, school_id)
                VALUES (:id, :vendorName, :amount, :deliveryTimeline, :notes, :documentUrl,
                        :isCustoking, false, :createdAt, :requestId, :schoolId)
                """)
                .param("id", id)
                .param("vendorName", vendor)
                .param("amount", longValue(request.get("amount"), 0L))
                .param("deliveryTimeline", str(request.get("deliveryTimeline"), ""))
                .param("notes", trimToNull(str(request.get("notes"), "")))
                .param("documentUrl", trimToNull(str(request.get("documentUrl"), "")))
                .param("isCustoking", "Custoking".equalsIgnoreCase(vendor))
                .param("createdAt", OffsetDateTime.now())
                .param("requestId", code)
                .param("schoolId", schoolId)
                .update();
        emitUpserted(code);
        return quotationMap(id);
    }

    @Transactional
    public Map<String, Object> updateQuotation(String code, String quotationId, Map<String, Object> request) {
        Map<String, Object> current = requestMap(code);
        requireStatus(current, "DRAFT");
        Map<String, Object> quote = quotationMap(quotationId);
        String vendor = request.containsKey("vendorName") ? str(request.get("vendorName"), str(quote.get("vendorName"), "Vendor")) : str(quote.get("vendorName"), "Vendor");
        jdbc.sql("""
                UPDATE ff_quotations
                SET vendor_name = :vendorName, amount = :amount, delivery_timeline = :deliveryTimeline,
                    notes = :notes, document_url = :documentUrl, is_custoking = :isCustoking
                WHERE id = :id AND request_id = :requestId
                """)
                .param("id", quotationId)
                .param("requestId", code)
                .param("vendorName", vendor)
                .param("amount", request.containsKey("amount") ? longValue(request.get("amount"), 0L) : quote.get("amount"))
                .param("deliveryTimeline", request.containsKey("deliveryTimeline") ? str(request.get("deliveryTimeline"), "") : quote.get("deliveryTimeline"))
                .param("notes", request.containsKey("notes") ? trimToNull(str(request.get("notes"), "")) : quote.get("notes"))
                .param("documentUrl", request.containsKey("documentUrl") ? trimToNull(str(request.get("documentUrl"), "")) : quote.get("documentUrl"))
                .param("isCustoking", "Custoking".equalsIgnoreCase(vendor))
                .update();
        emitUpserted(code);
        return quotationMap(quotationId);
    }

    @Transactional
    public Map<String, Object> deleteQuotation(String code, String quotationId) {
        Map<String, Object> current = requestMap(code);
        requireStatus(current, "DRAFT");
        jdbc.sql("DELETE FROM ff_quotations WHERE id = :id AND request_id = :requestId")
                .param("id", quotationId)
                .param("requestId", code)
                .update();
        emitUpserted(code);
        return row("ok", true);
    }

    @Transactional
    public Map<String, Object> submit(String code) {
        Map<String, Object> current = requestMap(code);
        requireStatus(current, "DRAFT");
        updateStatus(code, "AWAITING_BURSAR");
        emitUpserted(code);
        return detailRow(code);
    }

    @Transactional
    public Map<String, Object> approveBursar(String code, Map<String, Object> request) {
        Map<String, Object> current = requestMap(code);
        requireStatus(current, "AWAITING_BURSAR");
        jdbc.sql("""
                UPDATE firefighting_requests
                SET bursar_note = :note, bursar_approved_at = :approvedAt, status = 'AWAITING_PRINCIPAL'
                WHERE code = :code
                """)
                .param("code", code)
                .param("note", trimToNull(str(request.get("note"), "")))
                .param("approvedAt", OffsetDateTime.now())
                .update();
        emitUpserted(code);
        return detailRow(code);
    }

    @Transactional
    public Map<String, Object> approvePrincipal(String code, Map<String, Object> request) {
        Map<String, Object> current = requestMap(code);
        requireStatus(current, "AWAITING_PRINCIPAL");
        String quotationId = str(request.get("selectedQuotationId"), "");
        if (!quotationId.isBlank()) {
            Optional<Map<String, Object>> quote = quotationMapOptional(quotationId);
            quote.ifPresent(value -> jdbc.sql("""
                    UPDATE firefighting_requests
                    SET winner_vendor = :winnerVendor, winner_amount = :winnerAmount
                    WHERE code = :code
                    """)
                    .param("code", code)
                    .param("winnerVendor", value.get("vendorName"))
                    .param("winnerAmount", value.get("amount"))
                    .update());
        }
        jdbc.sql("""
                UPDATE firefighting_requests
                SET principal_note = :note, principal_approved_at = :approvedAt, status = 'APPROVED'
                WHERE code = :code
                """)
                .param("code", code)
                .param("note", trimToNull(str(request.get("note"), "")))
                .param("approvedAt", OffsetDateTime.now())
                .update();
        emitUpserted(code);
        return detailRow(code);
    }

    @Transactional
    public Map<String, Object> approveCustoking(String code) {
        Map<String, Object> current = requestMap(code);
        requireStatus(current, "APPROVED");
        jdbc.sql("UPDATE firefighting_requests SET status='CUSTOKING_APPROVED', custoking_approved_at=:at WHERE code=:code")
                .param("code", code)
                .param("at", OffsetDateTime.now())
                .update();
        emitUpserted(code);
        return detailRow(code);
    }

    @Transactional
    public Map<String, Object> reject(String code, Map<String, Object> request) {
        Map<String, Object> current = requestMap(code);
        if (List.of("REJECTED", "FULFILLED").contains(str(current.get("status"), "").toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Request is already " + current.get("status"));
        }
        jdbc.sql("""
                UPDATE firefighting_requests
                SET rejected_by = :rejectedBy, rejected_reason = :reason, status = 'REJECTED', rejected_at = :at
                WHERE code = :code
                """)
                .param("code", code)
                .param("rejectedBy", str(request.get("rejectedBy"), str(request.get("actorName"), "")))
                .param("reason", str(firstPresent(request, "reason", "rejectedReason"), ""))
                .param("at", OffsetDateTime.now())
                .update();
        emitUpserted(code);
        return detailRow(code);
    }

    @Transactional
    public Map<String, Object> fulfill(String code) {
        Map<String, Object> current = requestMap(code);
        requireStatus(current, "CUSTOKING_APPROVED");
        jdbc.sql("UPDATE firefighting_requests SET status='FULFILLED', fulfilled_at=:at WHERE code=:code")
                .param("code", code)
                .param("at", OffsetDateTime.now())
                .update();
        emitUpserted(code);
        return detailRow(code);
    }

    @Transactional
    public Map<String, Object> markVendorPaid(String code, Map<String, Object> request) {
        Map<String, Object> current = requestMap(code);
        Long schoolId = longValue(request.get("schoolId"), null);
        if (schoolId != null && !schoolId.equals(longValue(current.get("schoolId"), null))) {
            throw new IllegalArgumentException("Cross-school access denied");
        }
        if (current.get("vendorPaidAt") != null) {
            throw new IllegalArgumentException("Request already marked as vendor-paid");
        }
        String status = str(current.get("status"), "").toUpperCase(Locale.ROOT);
        if (!List.of("CUSTOKING_APPROVED", "FULFILLED").contains(status)) {
            throw new IllegalStateException("Only approved/fulfilled requests can be marked vendor-paid");
        }
        jdbc.sql("""
                UPDATE firefighting_requests
                SET vendor_paid_at = :paidAt,
                    vendor_paid_by = :paidBy,
                    vendor_payment_notes = :notes
                WHERE code = :code
                """)
                .param("code", code)
                .param("paidAt", OffsetDateTime.now())
                .param("paidBy", longValue(request.get("paidBy"), null))
                .param("notes", trimToNull(str(request.get("notes"), "")))
                .update();
        emitUpserted(code);
        return detailRow(code);
    }

    private void emitUpserted(String code) {
        Map<String, Object> current = requestMap(code);
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", current.get("code"));
        payload.put("title", current.get("title"));
        payload.put("category", current.get("category"));
        payload.put("urgency", current.get("urgency"));
        payload.put("status", current.get("status"));
        payload.put("estimatedBudget", current.get("estimatedBudget"));
        payload.put("schoolId", current.get("schoolId"));
        payload.put("winnerVendor", current.get("winnerVendor"));
        payload.put("winnerAmount", current.get("winnerAmount"));
        payload.put("createdAt", current.get("createdAt") == null ? null : current.get("createdAt").toString());
        payload.put("bursarApprovedAt", current.get("bursarApprovedAt") == null ? null : current.get("bursarApprovedAt").toString());
        payload.put("principalApprovedAt", current.get("principalApprovedAt") == null ? null : current.get("principalApprovedAt").toString());
        payload.put("rejectedReason", current.get("rejectedReason"));
        payload.put("vendorPaidAt", current.get("vendorPaidAt") == null ? null : current.get("vendorPaidAt").toString());
        payload.put("vendorPaidBy", current.get("vendorPaidBy"));
        payload.put("vendorPaymentNotes", current.get("vendorPaymentNotes"));
        Long schoolId = longValue(current.get("schoolId"), null);
        outboxWriter.append(FIREFIGHTING_REQUEST_UPSERTED, "FirefightingRequestUpserted:" + code,
                "FirefightingRequest", code, schoolId, payload);
    }

    private String requestSelect() {
        return """
                SELECT code, title, category, urgency, required_by_date, estimated_budget,
                       description, reference_file_url, raised_by, status, bursar_note,
                       principal_note, bursar_approved_at, principal_approved_at, rejected_by,
                       rejected_reason, custoking_criteria_json, winner_vendor, winner_amount,
                       created_at, school_id, version, created_by, updated_by, vendor_paid_at,
                       vendor_paid_by, vendor_payment_notes, custoking_approved_at, fulfilled_at, rejected_at
                FROM firefighting_requests
                """;
    }

    private String nextCode() {
        Integer max = jdbc.sql("SELECT COALESCE(MAX(NULLIF(regexp_replace(code, '[^0-9]+', '', 'g'), '')::int), 2) FROM firefighting_requests")
                .query(Integer.class)
                .single();
        return String.format("FF-%03d", (max == null ? 2 : max) + 1);
    }

    private Map<String, Object> detailRow(String code) {
        Map<String, Object> request = requestMap(code);
        List<Map<String, Object>> quotes = quotations(code).stream().map(this::quotationRowMap).toList();
        long best = quotes.stream().mapToLong(q -> longValue(q.get("amount"), 0L)).min()
                .orElse(longValue(request.get("estimatedBudget"), 0L));
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("code", request.get("code"));
        row.put("title", request.get("title"));
        row.put("category", request.get("category"));
        row.put("summary", request.get("description"));
        row.put("description", request.get("description"));
        row.put("amount", best);
        row.put("estimatedBudget", request.get("estimatedBudget"));
        row.put("quotesCount", quotes.size());
        row.put("winner", request.get("winnerVendor"));
        row.put("winnerVendor", request.get("winnerVendor"));
        row.put("winnerAmount", request.get("winnerAmount"));
        row.put("status", request.get("status"));
        row.put("schoolId", request.get("schoolId"));
        OffsetDateTime createdAt = (OffsetDateTime) request.get("createdAt");
        row.put("date", createdAt == null ? null : createdAt.toLocalDate().toString());
        row.put("urgency", request.get("urgency"));
        LocalDate requiredBy = (LocalDate) request.get("requiredByDate");
        row.put("requiredByDate", requiredBy == null ? null : requiredBy.toString());
        row.put("quotations", quotes);
        row.put("bursarNote", request.get("bursarNote"));
        row.put("principalNote", request.get("principalNote"));
        row.put("rejectedReason", request.get("rejectedReason"));
        row.put("vendorPaidAt", request.get("vendorPaidAt"));
        row.put("vendorPaidBy", request.get("vendorPaidBy"));
        row.put("vendorPaymentNotes", request.get("vendorPaymentNotes"));
        row.put("custokingCriteria", parseCriteria(str(request.get("custokingCriteriaJson"), "")));
        return row;
    }

    private Map<String, Object> requestMap(String code) {
        return jdbc.sql(requestSelect() + " WHERE code = :code")
                .param("code", code)
                .query((rs, rowNum) -> row(
                        "code", rs.getString("code"),
                        "title", rs.getString("title"),
                        "category", rs.getString("category"),
                        "urgency", rs.getString("urgency"),
                        "requiredByDate", rs.getObject("required_by_date", LocalDate.class),
                        "estimatedBudget", rs.getLong("estimated_budget"),
                        "description", rs.getString("description"),
                        "referenceFileUrl", rs.getString("reference_file_url"),
                        "raisedBy", rs.getLong("raised_by"),
                        "status", rs.getString("status"),
                        "bursarNote", rs.getString("bursar_note"),
                        "principalNote", rs.getString("principal_note"),
                        "bursarApprovedAt", rs.getObject("bursar_approved_at", OffsetDateTime.class),
                        "principalApprovedAt", rs.getObject("principal_approved_at", OffsetDateTime.class),
                        "rejectedReason", rs.getString("rejected_reason"),
                        "winnerVendor", rs.getString("winner_vendor"),
                        "winnerAmount", rs.getObject("winner_amount") == null ? null : rs.getLong("winner_amount"),
                        "createdAt", rs.getObject("created_at", OffsetDateTime.class),
                        "schoolId", rs.getObject("school_id") == null ? null : rs.getLong("school_id"),
                        "vendorPaidAt", rs.getObject("vendor_paid_at", OffsetDateTime.class),
                        "vendorPaidBy", rs.getObject("vendor_paid_by") == null ? null : rs.getLong("vendor_paid_by"),
                        "vendorPaymentNotes", rs.getString("vendor_payment_notes"),
                        "custokingCriteriaJson", rs.getString("custoking_criteria_json"),
                        "custokingApprovedAt", rs.getObject("custoking_approved_at", OffsetDateTime.class),
                        "fulfilledAt", rs.getObject("fulfilled_at", OffsetDateTime.class),
                        "rejectedAt", rs.getObject("rejected_at", OffsetDateTime.class)))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));
    }

    private Map<String, Object> quotationMap(String id) {
        return quotationMapOptional(id).orElseThrow(() -> new IllegalArgumentException("Quotation not found"));
    }

    private Optional<Map<String, Object>> quotationMapOptional(String id) {
        return jdbc.sql("""
                SELECT id, vendor_name, amount, delivery_timeline, notes, document_url,
                       is_custoking, is_recommended, created_at, request_id
                FROM ff_quotations
                WHERE id = :id
                """)
                .param("id", id)
                .query((rs, rowNum) -> row(
                        "id", rs.getString("id"),
                        "vendorName", rs.getString("vendor_name"),
                        "amount", rs.getLong("amount"),
                        "deliveryTimeline", rs.getString("delivery_timeline"),
                        "notes", rs.getString("notes"),
                        "documentUrl", rs.getString("document_url"),
                        "isCustoking", rs.getBoolean("is_custoking"),
                        "isRecommended", rs.getBoolean("is_recommended"),
                        "createdAt", rs.getObject("created_at", OffsetDateTime.class),
                        "requestId", rs.getString("request_id")))
                .optional();
    }

    private Map<String, Object> quotationRowMap(QuotationRow row) {
        return row("id", row.id(), "vendorName", row.vendorName(), "amount", row.amount(),
                "deliveryTimeline", row.deliveryTimeline(), "notes", row.notes(), "documentUrl", row.documentUrl(),
                "isCustoking", row.isCustoking(), "isRecommended", row.isRecommended(),
                "createdAt", row.createdAt() == null ? null : row.createdAt().toString());
    }

    private void requireStatus(Map<String, Object> request, String... allowed) {
        String current = str(request.get("status"), "").toUpperCase(Locale.ROOT);
        for (String status : allowed) {
            if (current.equals(status)) return;
        }
        throw new IllegalArgumentException("Request is in " + request.get("status") + " status and cannot perform this action");
    }

    private void updateStatus(String code, String status) {
        jdbc.sql("UPDATE firefighting_requests SET status = :status WHERE code = :code")
                .param("code", code)
                .param("status", status)
                .update();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseCriteria(String json) {
        if (json == null || json.isBlank()) return Map.of();
        return objectMapper.readValue(json, Map.class);
    }

    private String criteriaJson(String category) {
        boolean met = List.of("Furniture & fixtures", "Lab equipment", "Sports & playground",
                "Services & AMC", "Events & occasions", "Health").contains(category);
        return "{\"met\":" + met + "}";
    }

    private Object firstPresent(Map<String, Object> request, String... keys) {
        for (String key : keys) if (request.containsKey(key) && request.get(key) != null) return request.get(key);
        return null;
    }

    private String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private String textOrNull(Object value) {
        String text = str(value, "").trim();
        return text.isBlank() ? null : text;
    }

    private String trimToNull(String value) {
        String text = value == null ? "" : value.trim();
        return text.isBlank() ? null : text;
    }

    private Long longValue(Object value, Long fallback) {
        if (value == null) return fallback;
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value).replace(",", "").trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private LocalDate parseDate(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }

    private Map<String, Object> row(Object... kv) {
        if (kv.length % 2 != 0) throw new IllegalArgumentException("row requires key/value pairs");
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put(String.valueOf(kv[i]), kv[i + 1]);
        return map;
    }

    private void addEvent(List<Map<String, Object>> events, String status, OffsetDateTime at) {
        if (at != null) {
            LinkedHashMap<String, Object> event = new LinkedHashMap<>();
            event.put("status", status);
            event.put("at", at);
            events.add(event);
        }
    }

    public record FirefightingRequestRow(
            String code,
            String title,
            String category,
            String urgency,
            LocalDate requiredByDate,
            Long estimatedBudget,
            String description,
            String referenceFileUrl,
            Long raisedBy,
            String status,
            String bursarNote,
            String principalNote,
            OffsetDateTime bursarApprovedAt,
            OffsetDateTime principalApprovedAt,
            String rejectedBy,
            String rejectedReason,
            String custokingCriteriaJson,
            String winnerVendor,
            Long winnerAmount,
            OffsetDateTime createdAt,
            Long schoolId,
            Long version,
            String createdBy,
            String updatedBy,
            OffsetDateTime vendorPaidAt,
            Long vendorPaidBy,
            String vendorPaymentNotes,
            OffsetDateTime custokingApprovedAt,
            OffsetDateTime fulfilledAt,
            OffsetDateTime rejectedAt) {
    }

    public record QuotationRow(
            String id,
            String vendorName,
            Long amount,
            String deliveryTimeline,
            String notes,
            String documentUrl,
            Boolean isCustoking,
            Boolean isRecommended,
            OffsetDateTime createdAt,
            String requestId) {
    }
}
