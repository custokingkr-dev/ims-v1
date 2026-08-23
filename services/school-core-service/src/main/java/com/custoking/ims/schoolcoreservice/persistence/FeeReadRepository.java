package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Repository
public class FeeReadRepository {

    private final JdbcClient jdbc;
    private final OutboxWriter outbox;
    private final GuardianCommunicationPolicy guardianCommunicationPolicy;

    public FeeReadRepository(JdbcClient jdbc, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.guardianCommunicationPolicy = new GuardianCommunicationPolicy(jdbc);
    }

    public List<FeeBandRow> bands(String academicYearId, Long schoolId) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, name, class_from, class_to, discount, active_schedules_csv,
                       created_at, updated_at, academic_year_id
                FROM fee.fee_bands
                WHERE 1=1
                """);
        if (academicYearId != null && !academicYearId.isBlank()) sql.append(" AND academic_year_id = :academicYearId");
        if (schoolId != null) sql.append(" AND school_id = :schoolId");
        sql.append(" ORDER BY class_from, class_to, name");

        var spec = jdbc.sql(sql.toString());
        if (academicYearId != null && !academicYearId.isBlank()) spec = spec.param("academicYearId", academicYearId);
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        return spec.query(FeeBandRow.class).list();
    }

    public List<FeeItemRow> items(String bandId) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, name, frequency, amount, created_at, updated_at, band_id
                FROM fee.fee_items
                WHERE 1=1
                """);
        if (bandId != null && !bandId.isBlank()) sql.append(" AND band_id = :bandId");
        sql.append(" ORDER BY name");

        var spec = jdbc.sql(sql.toString());
        if (bandId != null && !bandId.isBlank()) spec = spec.param("bandId", bandId);
        return spec.query(FeeItemRow.class).list();
    }

    public Map<String, Object> feeStructure(String academicYearId, Long schoolId) {
        Map<String, Object> year = academicYear(academicYearId, schoolId);
        StringBuilder sql = new StringBuilder("SELECT id FROM fee.fee_bands WHERE academic_year_id = :academicYearId");
        if (schoolId != null) sql.append(" AND school_id = :schoolId");
        sql.append(" ORDER BY class_from ASC, name ASC");
        var spec = jdbc.sql(sql.toString()).param("academicYearId", year.get("id"));
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        List<Map<String, Object>> bands = spec.query(String.class).list().stream().map(this::bandWithItems).toList();
        return row("academicYearId", year.get("id"), "academicYear", year.get("label"), "bands", bands);
    }

    public Map<String, Object> matchBand(String classId, Long schoolId) {
        int sort = classSortOrder(classId);
        String academicYearId = currentAcademicYearId(schoolId);
        String schoolPredicate = schoolId == null ? "" : " AND school_id = :schoolId";
        var spec = jdbc.sql("""
                        SELECT id
                        FROM fee.fee_bands
                        WHERE academic_year_id = :academicYearId
                          AND class_from <= :sort
                          AND class_to >= :sort
                          %s
                        ORDER BY class_from ASC, name ASC
                        LIMIT 1
                        """.formatted(schoolPredicate))
                .param("academicYearId", academicYearId)
                .param("sort", sort);
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        return spec.query(String.class)
                .optional()
                .map(this::bandWithItems)
                .orElseGet(this::row);
    }

    public byte[] feeStructurePdf(String academicYearId) {
        Map<String, Object> year = academicYear(academicYearId);
        Long bandCount = jdbc.sql("SELECT COUNT(*) FROM fee.fee_bands WHERE academic_year_id = :academicYearId")
                .param("academicYearId", year.get("id"))
                .query(Long.class)
                .single();
        return simplePdf("Fee structure " + year.get("label") + " | bands " + (bandCount == null ? 0 : bandCount));
    }

    @Transactional
    public Map<String, Object> createBand(Map<String, Object> request) {
        String name = requireText(request.get("name"), "Band name is required");
        int classFrom = intValue(request.get("classFrom"), 1);
        int classTo = intValue(request.get("classTo"), classFrom);
        validateClassRange(classFrom, classTo);
        String schedules = schedulesCsv(request.get("schedules"), true);
        String id = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now();
        Long schoolId = requireSchool(request.get("schoolId"));
        String academicYearId = currentAcademicYearId(schoolId);

        jdbc.sql("""
                INSERT INTO fee.fee_bands(id, name, class_from, class_to, discount, active_schedules_csv,
                                      created_at, updated_at, academic_year_id, school_id, status,
                                      grace_period_days, late_fee_type, late_fee_amount, late_fee_interval_days)
                VALUES (:id, :name, :classFrom, :classTo, :discount, :schedules, :createdAt, :updatedAt,
                        :academicYearId, :schoolId, 'DRAFT', :gracePeriodDays, :lateFeeType,
                        :lateFeeAmount, :lateFeeIntervalDays)
                """)
                .param("id", id)
                .param("name", name)
                .param("classFrom", classFrom)
                .param("classTo", classTo)
                .param("discount", doubleValue(request.get("discount"), 0))
                .param("schedules", schedules)
                .param("createdAt", now)
                .param("updatedAt", now)
                .param("academicYearId", academicYearId)
                .param("schoolId", schoolId)
                .param("gracePeriodDays", intValue(request.get("gracePeriodDays"), 0))
                .param("lateFeeType", feeType(request.get("lateFeeType")))
                .param("lateFeeAmount", moneyFieldToPaise(request.get("lateFeeAmount")))
                .param("lateFeeIntervalDays", intValue(request.get("lateFeeIntervalDays"), 0))
                .update();
        return bandWithItems(id);
    }

    @Transactional
    public Map<String, Object> createBandRevision(String sourceBandId) {
        Map<String, Object> source = bandRecord(sourceBandId);
        if (!"PUBLISHED".equals(source.get("status"))) {
            throw new IllegalArgumentException("Only a published fee plan can start a new revision");
        }
        String existingDraft = jdbc.sql("""
                SELECT id
                FROM fee.fee_bands
                WHERE supersedes_band_id = :sourceBandId AND status = 'DRAFT'
                ORDER BY revision DESC
                LIMIT 1
                """)
                .param("sourceBandId", sourceBandId)
                .query(String.class)
                .optional()
                .orElse(null);
        if (existingDraft != null) return bandWithItems(existingDraft);

        String revisionId = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.sql("""
                INSERT INTO fee.fee_bands(
                    id, name, class_from, class_to, discount, active_schedules_csv,
                    created_at, updated_at, academic_year_id, school_id, status, revision,
                    grace_period_days, late_fee_type, late_fee_amount, late_fee_interval_days,
                    supersedes_band_id)
                SELECT :revisionId, name, class_from, class_to, discount, active_schedules_csv,
                       :now, :now, academic_year_id, school_id, 'DRAFT', revision + 1,
                       grace_period_days, late_fee_type, late_fee_amount, late_fee_interval_days,
                       id
                FROM fee.fee_bands
                WHERE id = :sourceBandId
                """)
                .param("revisionId", revisionId)
                .param("now", now)
                .param("sourceBandId", sourceBandId)
                .update();

        List<Map<String, Object>> items = jdbc.sql("""
                SELECT name, frequency, amount, optional
                FROM fee.fee_items
                WHERE band_id = :sourceBandId
                ORDER BY created_at, id
                """)
                .param("sourceBandId", sourceBandId)
                .query((rs, rowNum) -> row(
                        "name", rs.getString("name"),
                        "frequency", rs.getString("frequency"),
                        "amount", rs.getLong("amount"),
                        "optional", rs.getBoolean("optional")))
                .list();
        for (Map<String, Object> item : items) {
            jdbc.sql("""
                    INSERT INTO fee.fee_items(
                        id, name, frequency, amount, optional, created_at, updated_at, band_id, school_id)
                    VALUES (:id, :name, :frequency, :amount, :optional, :now, :now, :bandId, :schoolId)
                    """)
                    .param("id", UUID.randomUUID().toString())
                    .param("name", item.get("name"))
                    .param("frequency", item.get("frequency"))
                    .param("amount", item.get("amount"))
                    .param("optional", item.get("optional"))
                    .param("now", now)
                    .param("bandId", revisionId)
                    .param("schoolId", source.get("schoolId"))
                    .update();
        }
        jdbc.sql("""
                INSERT INTO fee.fee_installments(
                    school_id, band_id, label, due_date, share_percent, sort_order)
                SELECT school_id, :revisionId, label, due_date, share_percent, sort_order
                FROM fee.fee_installments
                WHERE band_id = :sourceBandId
                """)
                .param("revisionId", revisionId)
                .param("sourceBandId", sourceBandId)
                .update();
        return bandWithItems(revisionId);
    }

    @Transactional
    public Map<String, Object> updateBand(String id, Map<String, Object> request) {
        Map<String, Object> current = bandRecord(id);
        requireDraftBand(current);
        String name = textOrDefault(request.get("name"), String.valueOf(current.get("name")));
        int classFrom = request.containsKey("classFrom") ? intValue(request.get("classFrom"), 1)
                : ((Number) current.get("classFrom")).intValue();
        int classTo = request.containsKey("classTo") ? intValue(request.get("classTo"), classFrom)
                : ((Number) current.get("classTo")).intValue();
        validateClassRange(classFrom, classTo);
        String schedules = schedulesCsv(request.get("schedules"), false);
        String scheduleValue = schedules.isBlank() ? (String) current.get("activeSchedulesCsv") : schedules;

        jdbc.sql("""
                UPDATE fee.fee_bands
                SET name = :name, class_from = :classFrom, class_to = :classTo, discount = :discount,
                    active_schedules_csv = :schedules, grace_period_days = :gracePeriodDays,
                    late_fee_type = :lateFeeType, late_fee_amount = :lateFeeAmount,
                    late_fee_interval_days = :lateFeeIntervalDays, updated_at = :updatedAt
                WHERE id = :id
                """)
                .param("id", id)
                .param("name", name)
                .param("classFrom", classFrom)
                .param("classTo", classTo)
                .param("discount", request.containsKey("discount")
                        ? doubleValue(request.get("discount"), 0)
                        : ((Number) current.get("discount")).doubleValue())
                .param("schedules", scheduleValue)
                .param("gracePeriodDays", request.containsKey("gracePeriodDays")
                        ? intValue(request.get("gracePeriodDays"), 0)
                        : ((Number) current.get("gracePeriodDays")).intValue())
                .param("lateFeeType", request.containsKey("lateFeeType")
                        ? feeType(request.get("lateFeeType"))
                        : current.get("lateFeeType"))
                .param("lateFeeAmount", request.containsKey("lateFeeAmount")
                        ? moneyFieldToPaise(request.get("lateFeeAmount"))
                        : current.get("lateFeeAmount"))
                .param("lateFeeIntervalDays", request.containsKey("lateFeeIntervalDays")
                        ? intValue(request.get("lateFeeIntervalDays"), 0)
                        : ((Number) current.get("lateFeeIntervalDays")).intValue())
                .param("updatedAt", OffsetDateTime.now())
                .update();
        return bandWithItems(id);
    }

    @Transactional
    public Map<String, Object> patchBand(String id, Map<String, Object> request) {
        Map<String, Object> band = bandRecord(id);
        requireDraftBand(band);
        String schedules = schedulesCsv(request.get("schedules"), false);
        if (request.containsKey("discount") || request.containsKey("bandDiscount")) {
            jdbc.sql("UPDATE fee.fee_bands SET discount = :discount, updated_at = :updatedAt WHERE id = :id")
                    .param("id", id)
                    .param("discount", doubleValue(firstPresent(request, "discount", "bandDiscount"), 0))
                    .param("updatedAt", OffsetDateTime.now())
                    .update();
        }
        if (!schedules.isBlank()) {
            jdbc.sql("UPDATE fee.fee_bands SET active_schedules_csv = :schedules, updated_at = :updatedAt WHERE id = :id")
                    .param("id", id)
                    .param("schedules", schedules)
                    .param("updatedAt", OffsetDateTime.now())
                    .update();
        }
        if (request.containsKey("gracePeriodDays") || request.containsKey("lateFeeType")
                || request.containsKey("lateFeeAmount") || request.containsKey("lateFeeIntervalDays")) {
            Map<String, Object> current = bandRecord(id);
            jdbc.sql("""
                    UPDATE fee.fee_bands
                    SET grace_period_days = :gracePeriodDays, late_fee_type = :lateFeeType,
                        late_fee_amount = :lateFeeAmount, late_fee_interval_days = :lateFeeIntervalDays,
                        updated_at = :updatedAt
                    WHERE id = :id
                    """)
                    .param("id", id)
                    .param("gracePeriodDays", request.containsKey("gracePeriodDays")
                            ? intValue(request.get("gracePeriodDays"), 0)
                            : current.get("gracePeriodDays"))
                    .param("lateFeeType", request.containsKey("lateFeeType")
                            ? feeType(request.get("lateFeeType"))
                            : current.get("lateFeeType"))
                    .param("lateFeeAmount", request.containsKey("lateFeeAmount")
                            ? moneyFieldToPaise(request.get("lateFeeAmount"))
                            : current.get("lateFeeAmount"))
                    .param("lateFeeIntervalDays", request.containsKey("lateFeeIntervalDays")
                            ? intValue(request.get("lateFeeIntervalDays"), 0)
                            : current.get("lateFeeIntervalDays"))
                    .param("updatedAt", OffsetDateTime.now())
                    .update();
        }
        return bandWithItems(id);
    }

    @Transactional
    public void deleteBand(String id) {
        bandRecord(id);
        long assignments = jdbc.sql("SELECT count(*) FROM fee.fee_assignments WHERE band_id = :id")
                .param("id", id)
                .query(Long.class)
                .single();
        if (assignments > 0) {
            throw new IllegalArgumentException(
                    "Cannot delete this fee plan: " + assignments + " student(s) are assigned to it. "
                            + "Reassign or remove their fee plans first.");
        }
        jdbc.sql("DELETE FROM fee.fee_items WHERE band_id = :id").param("id", id).update();
        try {
            jdbc.sql("DELETE FROM fee.fee_bands WHERE id = :id").param("id", id).update();
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            // Backstop for the count guard above: fee_assignments is RLS-scoped, so the count can
            // read 0 rows the current tenant cannot see while the FK constraint (which bypasses RLS)
            // still blocks the delete. Convert the raw FK violation into the same clear 400.
            throw new IllegalArgumentException(
                    "Cannot delete this fee plan: student(s) are still assigned to it. "
                            + "Reassign or remove their fee plans first.");
        }
    }

    @Transactional
    public Map<String, Object> createItem(Map<String, Object> request) {
        String bandId = requireText(request.get("bandId"), "Band id is required");
        Map<String, Object> band = bandRecord(bandId);
        requireDraftBand(band);
        String id = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.sql("""
                INSERT INTO fee.fee_items(id, name, frequency, amount, optional, created_at, updated_at, band_id, school_id)
                VALUES (:id, :name, :frequency, :amount, :optional, :createdAt, :updatedAt, :bandId, :schoolId)
                """)
                .param("id", id)
                .param("name", requireText(firstPresent(request, "itemName", "name"), "Item name is required"))
                .param("frequency", textOrDefault(request.get("frequency"), "Annual"))
                .param("amount", moneyToPaise(request))
                .param("optional", booleanValue(request.get("optional")))
                .param("createdAt", now)
                .param("updatedAt", now)
                .param("bandId", bandId)
                .param("schoolId", band.get("schoolId"))
                .update();
        return bandWithItems(bandId);
    }

    @Transactional
    public Map<String, Object> updateItem(String id, Map<String, Object> request) {
        Map<String, Object> item = itemRecord(id);
        requireDraftBand(bandRecord(String.valueOf(item.get("bandId"))));
        jdbc.sql("""
                UPDATE fee.fee_items
                SET name = :name, frequency = :frequency, amount = :amount, optional = :optional,
                    updated_at = :updatedAt
                WHERE id = :id
                """)
                .param("id", id)
                .param("name", request.containsKey("itemName") || request.containsKey("name")
                        ? requireText(firstPresent(request, "itemName", "name"), "Item name is required")
                        : item.get("name"))
                .param("frequency", request.containsKey("frequency")
                        ? textOrDefault(request.get("frequency"), "Annual")
                        : item.get("frequency"))
                .param("amount", request.containsKey("amount") || request.containsKey("amountPaise") ? moneyToPaise(request) : item.get("amount"))
                .param("optional", request.containsKey("optional")
                        ? booleanValue(request.get("optional"))
                        : item.get("optional"))
                .param("updatedAt", OffsetDateTime.now())
                .update();
        return bandWithItems(String.valueOf(item.get("bandId")));
    }

    @Transactional
    public Map<String, Object> saveInstallments(String bandId, List<Map<String, Object>> requestRows) {
        Map<String, Object> band = bandRecord(bandId);
        requireDraftBand(band);
        if (requestRows == null || requestRows.isEmpty()) {
            throw new IllegalArgumentException("At least one installment is required");
        }
        double total = requestRows.stream()
                .mapToDouble(row -> doubleValue(row.get("sharePercent"), 0))
                .sum();
        if (Math.abs(total - 100.0) > 0.01) {
            throw new IllegalArgumentException("Installment percentages must total 100");
        }
        jdbc.sql("DELETE FROM fee.fee_installments WHERE band_id = :bandId")
                .param("bandId", bandId)
                .update();
        int order = 0;
        for (Map<String, Object> installment : requestRows) {
            String label = requireText(installment.get("label"), "Installment label is required");
            LocalDate dueDate = parseDate(installment.get("dueDate"), "Installment due date is required");
            double share = doubleValue(installment.get("sharePercent"), 0);
            if (share <= 0 || share > 100) {
                throw new IllegalArgumentException("Installment percentage must be between 0 and 100");
            }
            jdbc.sql("""
                    INSERT INTO fee.fee_installments
                        (school_id, band_id, label, due_date, share_percent, sort_order)
                    VALUES (:schoolId, :bandId, :label, :dueDate, :share, :sortOrder)
                    """)
                    .param("schoolId", band.get("schoolId"))
                    .param("bandId", bandId)
                    .param("label", label)
                    .param("dueDate", dueDate)
                    .param("share", share)
                    .param("sortOrder", order++)
                    .update();
        }
        touchDraftBand(bandId);
        return bandWithItems(bandId);
    }

    @Transactional
    public Map<String, Object> publishBand(String bandId, Long actorId) {
        Map<String, Object> band = bandRecord(bandId);
        if ("PUBLISHED".equals(band.get("status"))) {
            return bandWithItems(bandId);
        }
        long itemCount = jdbc.sql("SELECT COUNT(*) FROM fee.fee_items WHERE band_id = :bandId")
                .param("bandId", bandId)
                .query(Long.class)
                .single();
        if (itemCount == 0) {
            throw new IllegalArgumentException("Add at least one fee head before publishing");
        }
        Double installmentTotal = jdbc.sql("""
                SELECT COALESCE(SUM(share_percent), 0)
                FROM fee.fee_installments
                WHERE band_id = :bandId
                """)
                .param("bandId", bandId)
                .query(Double.class)
                .single();
        if (installmentTotal == null || Math.abs(installmentTotal - 100.0) > 0.01) {
            throw new IllegalArgumentException("Configure installments totaling 100 before publishing");
        }
        String supersedesBandId = textOrDefault(band.get("supersedesBandId"), "");
        long overlapping = jdbc.sql("""
                SELECT COUNT(*)
                FROM fee.fee_bands
                WHERE school_id = :schoolId
                  AND academic_year_id = :academicYearId
                  AND id <> :bandId
                  AND id <> :supersedesBandId
                  AND status = 'PUBLISHED'
                  AND class_from <= :classTo
                  AND class_to >= :classFrom
                """)
                .param("schoolId", band.get("schoolId"))
                .param("academicYearId", band.get("academicYearId"))
                .param("bandId", bandId)
                .param("supersedesBandId", supersedesBandId)
                .param("classFrom", band.get("classFrom"))
                .param("classTo", band.get("classTo"))
                .query(Long.class)
                .single();
        if (overlapping > 0) {
            throw new IllegalArgumentException("Another published fee plan already covers part of this class range");
        }
        if (!supersedesBandId.isBlank()) {
            int archived = jdbc.sql("""
                    UPDATE fee.fee_bands
                    SET status = 'ARCHIVED', updated_at = :updatedAt
                    WHERE id = :supersedesBandId AND school_id = :schoolId
                      AND academic_year_id = :academicYearId AND status = 'PUBLISHED'
                    """)
                    .param("updatedAt", OffsetDateTime.now())
                    .param("supersedesBandId", supersedesBandId)
                    .param("schoolId", band.get("schoolId"))
                    .param("academicYearId", band.get("academicYearId"))
                    .update();
            if (archived == 0) {
                throw new IllegalArgumentException("The fee plan being revised is no longer published");
            }
        }
        jdbc.sql("""
                UPDATE fee.fee_bands
                SET status = 'PUBLISHED', published_at = :publishedAt, updated_at = :publishedAt
                WHERE id = :bandId
                """)
                .param("bandId", bandId)
                .param("publishedAt", OffsetDateTime.now())
                .update();
        return row("ok", true, "actorId", actorId, "band", bandWithItems(bandId));
    }

    public List<Map<String, Object>> discountRules(Long schoolId, String academicYearId) {
        String yearId = resolveAcademicYearId(academicYearId, schoolId);
        return jdbc.sql("""
                SELECT id, name, rule_type, percentage, priority, active, academic_year_id
                FROM fee.fee_discount_rules
                WHERE school_id = :schoolId AND academic_year_id = :yearId
                ORDER BY priority, name
                """)
                .param("schoolId", schoolId)
                .param("yearId", yearId)
                .query((rs, rowNum) -> row(
                        "id", rs.getLong("id"),
                        "name", rs.getString("name"),
                        "ruleType", rs.getString("rule_type"),
                        "percentage", rs.getDouble("percentage"),
                        "priority", rs.getInt("priority"),
                        "active", rs.getBoolean("active"),
                        "academicYearId", rs.getString("academic_year_id")))
                .list();
    }

    @Transactional
    public Map<String, Object> saveDiscountRule(Long schoolId, Map<String, Object> request) {
        String academicYearId = resolveAcademicYearId(textOrDefault(request.get("academicYearId"), ""), schoolId);
        String name = requireText(request.get("name"), "Rule name is required");
        String ruleType = discountRuleType(request.get("ruleType"));
        double percentage = doubleValue(request.get("percentage"), 0);
        if (percentage < 0 || percentage > 100) {
            throw new IllegalArgumentException("Discount percentage must be between 0 and 100");
        }
        return jdbc.sql("""
                INSERT INTO fee.fee_discount_rules
                    (school_id, academic_year_id, name, rule_type, percentage, priority, active)
                VALUES (:schoolId, :yearId, :name, :ruleType, :percentage, :priority, :active)
                ON CONFLICT (school_id, academic_year_id, name) DO UPDATE
                SET rule_type = EXCLUDED.rule_type, percentage = EXCLUDED.percentage,
                    priority = EXCLUDED.priority, active = EXCLUDED.active, updated_at = now()
                RETURNING id, name, rule_type, percentage, priority, active, academic_year_id
                """)
                .param("schoolId", schoolId)
                .param("yearId", academicYearId)
                .param("name", name)
                .param("ruleType", ruleType)
                .param("percentage", percentage)
                .param("priority", intValue(request.get("priority"), 100))
                .param("active", !request.containsKey("active") || booleanValue(request.get("active")))
                .query((rs, rowNum) -> row(
                        "id", rs.getLong("id"),
                        "name", rs.getString("name"),
                        "ruleType", rs.getString("rule_type"),
                        "percentage", rs.getDouble("percentage"),
                        "priority", rs.getInt("priority"),
                        "active", rs.getBoolean("active"),
                        "academicYearId", rs.getString("academic_year_id")))
                .single();
    }

    public Map<String, Object> configurationHealth(Long schoolId, String academicYearId) {
        String yearId = resolveAcademicYearId(academicYearId, schoolId);
        Map<String, Object> counts = jdbc.sql("""
                SELECT
                  COUNT(*) AS total_count,
                  COUNT(*) FILTER (WHERE status = 'PUBLISHED') AS published_count,
                  COUNT(*) FILTER (WHERE status = 'DRAFT') AS draft_count,
                  COUNT(*) FILTER (
                    WHERE NOT EXISTS (SELECT 1 FROM fee.fee_items i WHERE i.band_id = b.id)
                  ) AS missing_items,
                  COUNT(*) FILTER (
                    WHERE status = 'DRAFT' AND
                      COALESCE((SELECT SUM(share_percent) FROM fee.fee_installments x WHERE x.band_id = b.id), 0) <> 100
                  ) AS invalid_installments
                FROM fee.fee_bands b
                WHERE b.school_id = :schoolId AND b.academic_year_id = :yearId
                """)
                .param("schoolId", schoolId)
                .param("yearId", yearId)
                .query((rs, rowNum) -> row(
                        "totalPlans", rs.getLong("total_count"),
                        "publishedPlans", rs.getLong("published_count"),
                        "draftPlans", rs.getLong("draft_count"),
                        "missingFeeHeads", rs.getLong("missing_items"),
                        "invalidInstallments", rs.getLong("invalid_installments")))
                .single();
        long blocking = ((Number) counts.get("missingFeeHeads")).longValue()
                + ((Number) counts.get("invalidInstallments")).longValue();
        if (((Number) counts.get("totalPlans")).longValue() == 0) blocking++;
        counts.put("academicYearId", yearId);
        counts.put("ready", blocking == 0);
        counts.put("blockingIssues", blocking);
        return counts;
    }

    @Transactional
    public String deleteItem(String id) {
        Map<String, Object> item = itemRecord(id);
        requireDraftBand(bandRecord(String.valueOf(item.get("bandId"))));
        jdbc.sql("DELETE FROM fee.fee_items WHERE id = :id").param("id", id).update();
        return String.valueOf(item.get("bandId"));
    }

    public List<FeeAssignmentRow> assignments(Long studentId, String academicYearId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, schedule, band_discount, manual_discount, surcharge, net_payable,
                       paid_amount, assigned_by, assigned_at, updated_by, updated_at,
                       student_id, band_id, academic_year_id
                FROM fee.fee_assignments
                WHERE 1=1
                """);
        if (studentId != null) sql.append(" AND student_id = :studentId");
        if (academicYearId != null && !academicYearId.isBlank()) sql.append(" AND academic_year_id = :academicYearId");
        sql.append(" ORDER BY assigned_at DESC LIMIT :limit");

        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 500)));
        if (studentId != null) spec = spec.param("studentId", studentId);
        if (academicYearId != null && !academicYearId.isBlank()) spec = spec.param("academicYearId", academicYearId);
        return spec.query(FeeAssignmentRow.class).list();
    }

    public List<PaymentRow> payments(Long studentId, String assignmentId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT pr.id, pr.amount, pr.mode, pr.notes, pr.paid_at, pr.recorded_by, pr.receipt_number,
                       pr.created_at, pr.student_id, pr.assignment_id, COALESCE(s.full_name, '') AS student
                FROM fee.payment_records pr
                LEFT JOIN student.students s ON s.id = pr.student_id
                WHERE 1=1
                """);
        if (studentId != null) sql.append(" AND pr.student_id = :studentId");
        if (assignmentId != null && !assignmentId.isBlank()) sql.append(" AND pr.assignment_id = :assignmentId");
        sql.append(" ORDER BY pr.paid_at DESC, pr.created_at DESC LIMIT :limit");

        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 500)));
        if (studentId != null) spec = spec.param("studentId", studentId);
        if (assignmentId != null && !assignmentId.isBlank()) spec = spec.param("assignmentId", assignmentId);
        return spec.query(PaymentRow.class).list();
    }

    public Map<String, Object> feeReport(String classId, String sectionId, String academicYearId, Long schoolId) {
        academicYearId = resolveAcademicYearId(academicYearId, schoolId);
        return row("content", feeReportRows(classId, sectionId, academicYearId, schoolId, false));
    }

    public Map<String, Object> feeOverdue(String classId, String sectionId, String academicYearId, Long schoolId) {
        academicYearId = resolveAcademicYearId(academicYearId, schoolId);
        return row("content", feeReportRows(classId, sectionId, academicYearId, schoolId, true).stream()
                .map(row -> row(
                        "student", row.get("student"),
                        "schedule", row.get("schedule"),
                        "dueAmount", row.get("due"),
                        "daysOverdue", row.get("daysOverdue"),
                        "installments", row.get("installments")))
                .toList());
    }

    public Map<String, Object> feeReminderRequests(
            String classId, String sectionId, String academicYearId, Long schoolId, Long actorId) {
        academicYearId = resolveAcademicYearId(academicYearId, schoolId);
        List<String> overdueAssignmentIds = feeReportRows(
                classId, sectionId, academicYearId, schoolId, true).stream()
                .map(row -> String.valueOf(row.get("assignmentId")))
                .toList();
        if (overdueAssignmentIds.isEmpty()) {
            return row("ok", true, "queued", 0, "classId", classId, "sectionId", sectionId,
                    "content", List.of(), "suppressedCount", 0, "suppressed", List.of());
        }
        List<Map<String, Object>> candidates = jdbc.sql("""
                        SELECT fa.id AS assignment_id, fa.academic_year_id,
                               GREATEST(fa.net_payable - fa.paid_amount, 0) AS due_amount,
                               s.id AS student_id, s.full_name AS student_name,
                               s.school_id, s.class_id, s.section_id
                        FROM fee.fee_assignments fa
                        JOIN student.students s ON s.id = fa.student_id
                        WHERE s.school_id = :schoolId
                          AND s.class_id = :classId
                          AND s.section_id = :sectionId
                          AND s.deleted_at IS NULL
                          AND fa.academic_year_id = :academicYearId
                          AND GREATEST(fa.net_payable - fa.paid_amount, 0) > 0
                        ORDER BY s.full_name ASC
                        """)
                .param("schoolId", schoolId)
                .param("classId", classId)
                .param("sectionId", sectionId)
                .param("academicYearId", academicYearId)
                .query((rs, rowNum) -> row(
                            "assignmentId", rs.getString("assignment_id"),
                            "studentId", rs.getLong("student_id"),
                            "schoolId", rs.getLong("school_id"),
                            "academicYearId", rs.getString("academic_year_id"),
                            "classId", rs.getString("class_id"),
                            "sectionId", rs.getString("section_id"),
                            "dueAmount", rs.getLong("due_amount"),
                            "recipientName", rs.getString("student_name"),
                            "studentName", rs.getString("student_name")))
                .list()
                .stream()
                .filter(request -> overdueAssignmentIds.contains(String.valueOf(request.get("assignmentId"))))
                .toList();
        List<Map<String, Object>> requests = new ArrayList<>();
        List<Map<String, Object>> suppressed = new ArrayList<>();
        for (Map<String, Object> candidate : candidates) {
            long studentId = longValue(candidate.get("studentId"), 0);
            var decision = guardianCommunicationPolicy.evaluate(schoolId, studentId, "SMS");
            if (!decision.allowed()) {
                suppressed.add(row(
                        "assignmentId", candidate.get("assignmentId"),
                        "studentId", studentId,
                        "reason", decision.reason()));
                continue;
            }
            String requestId = UUID.randomUUID().toString();
            requests.add(row(
                    "reminderRequestId", requestId,
                    "assignmentId", candidate.get("assignmentId"),
                    "studentId", studentId,
                    "schoolId", candidate.get("schoolId"),
                    "academicYearId", candidate.get("academicYearId"),
                    "classId", candidate.get("classId"),
                    "sectionId", candidate.get("sectionId"),
                    "dueAmount", candidate.get("dueAmount"),
                    "actorId", actorId,
                    "sourceEventType", "fees.fee-reminder-requested.v1",
                    "sourceEventId", requestId,
                    "notificationType", "FEE_REMINDER",
                    "channel", decision.channel(),
                    "destination", decision.destination(),
                    "recipientType", "GUARDIAN",
                    "recipientId", decision.guardianId(),
                    "recipientName", candidate.get("recipientName"),
                    "subject", "Fee payment reminder",
                    "template", "fee-reminder.v1",
                    "policyEvidence", decision.evidence(requestId),
                    "variables", row(
                            "assignmentId", candidate.get("assignmentId"),
                            "studentId", studentId,
                            "studentName", candidate.get("studentName"),
                            "academicYearId", candidate.get("academicYearId"),
                            "dueAmount", candidate.get("dueAmount"))));
        }
        return row("ok", true, "queued", requests.size(), "classId", classId, "sectionId", sectionId,
                "content", requests, "suppressedCount", suppressed.size(), "suppressed", suppressed);
    }

    public Map<String, Object> feesModule(String academicYearId, Long schoolId) {
        academicYearId = resolveAcademicYearId(academicYearId, schoolId);
        refreshLateFeesForScope(schoolId, academicYearId);
        Long collected = jdbc.sql("""
                        SELECT COALESCE(SUM(p.amount), 0)
                        FROM fee.payment_records p
                        JOIN fee.fee_assignments fa ON fa.id = p.assignment_id
                        JOIN student.students s ON s.id = p.student_id
                        WHERE fa.academic_year_id = :academicYearId
                          AND fa.school_id = :schoolId
                          AND s.school_id = :schoolId
                          AND s.deleted_at IS NULL
                        """)
                .param("academicYearId", academicYearId)
                .param("schoolId", schoolId)
                .query(Long.class)
                .single();
        Long target = jdbc.sql("""
                        SELECT COALESCE(SUM(net_payable), 0)
                        FROM fee.fee_assignments fa
                        JOIN student.students s ON s.id = fa.student_id
                        WHERE fa.academic_year_id = :academicYearId
                          AND s.school_id = :schoolId
                          AND s.deleted_at IS NULL
                        """)
                .param("academicYearId", academicYearId)
                .param("schoolId", schoolId)
                .query(Long.class)
                .single();
        List<Map<String, Object>> records = jdbc.sql("""
                        SELECT s.id AS student_id, s.full_name AS student_name, fb.name AS plan_name,
                               fa.schedule, GREATEST(fa.net_payable - fa.paid_amount, 0) AS due_amount,
                               COALESCE(NULLIF(fa.gross_fee, 0), fi.total_annual_fee, 0) AS total_annual_fee,
                               fa.paid_amount
                        FROM fee.fee_assignments fa
                        JOIN student.students s ON s.id = fa.student_id
                        JOIN fee.fee_bands fb ON fb.id = fa.band_id
                        LEFT JOIN (
                            SELECT band_id, SUM(amount) AS total_annual_fee
                            FROM fee.fee_items
                            GROUP BY band_id
                        ) fi ON fi.band_id = fb.id
                        WHERE fa.academic_year_id = :academicYearId
                          AND s.school_id = :schoolId
                          AND s.deleted_at IS NULL
                        ORDER BY s.full_name ASC
                        """)
                .param("academicYearId", academicYearId)
                .param("schoolId", schoolId)
                .query((rs, rowNum) -> row(
                        "studentId", rs.getLong("student_id"),
                        "studentName", rs.getString("student_name"),
                        "planName", rs.getString("plan_name"),
                        "schedule", rs.getString("schedule"),
                        "dueAmount", rs.getLong("due_amount"),
                        "totalAnnualFee", rs.getLong("total_annual_fee"),
                        "paidAmount", rs.getLong("paid_amount")))
                .list();
        return row("summary", row("collected", collected == null ? 0 : collected, "target", target == null ? 0 : target),
                "records", records);
    }

    public Map<String, Object> feeOverdueCount(String academicYearId, Long schoolId) {
        academicYearId = resolveAcademicYearId(academicYearId, schoolId);
        refreshLateFeesForScope(schoolId, academicYearId);
        List<String> assignmentIds = jdbc.sql("""
                        SELECT fa.id
                        FROM fee.fee_assignments fa
                        JOIN student.students s ON s.id = fa.student_id
                        WHERE fa.academic_year_id = :academicYearId
                          AND s.school_id = :schoolId
                          AND s.deleted_at IS NULL
                        """)
                .param("academicYearId", academicYearId)
                .param("schoolId", schoolId)
                .query(String.class)
                .list();
        long count = assignmentIds.stream()
                .filter(id -> assignmentInstallments(id, false).stream()
                        .anyMatch(installment -> "Overdue".equals(installment.get("status"))))
                .count();
        return row("count", count);
    }

    public Map<String, Object> receiptByPaymentId(String paymentId) {
        return receipt("p.id = :paymentId", spec -> spec.param("paymentId", paymentId), "Payment not found");
    }

    public Map<String, Object> receiptByReceiptNumber(String receiptNumber) {
        return receipt("p.receipt_number = :receiptNumber", spec -> spec.param("receiptNumber", receiptNumber), "Receipt not found");
    }

    public byte[] receiptPdfByPaymentId(String paymentId) {
        return receiptPdf(receiptByPaymentId(paymentId));
    }

    public byte[] receiptPdfByReceiptNumber(String receiptNumber) {
        return receiptPdf(receiptByReceiptNumber(receiptNumber));
    }

    @Transactional
    public Map<String, Object> assignFeePlan(Map<String, Object> request) {
        long studentId = longValue(request.get("studentId"), -1);
        if (studentId <= 0) {
            throw new IllegalArgumentException("Student id is required");
        }
        String bandId = requireText(request.get("bandId"), "Band id is required");
        String schedule = requireText(request.get("schedule"), "Payment schedule is required");
        Long schoolId = studentSchoolId(studentId);
        String academicYearId = resolveAcademicYearId(textOrDefault(request.get("academicYearId"), ""), schoolId);
        Map<String, Object> band = bandRecord(bandId);
        if (!schoolId.equals(((Number) band.get("schoolId")).longValue())) {
            throw new IllegalArgumentException("The fee plan belongs to a different school");
        }
        if (!academicYearId.equals(band.get("academicYearId"))) {
            throw new IllegalArgumentException("The fee plan belongs to a different academic year");
        }
        if (!"PUBLISHED".equals(band.get("status"))) {
            throw new IllegalArgumentException("Publish the fee plan before assigning it to students");
        }
        List<String> allowedSchedules = splitCsv(String.valueOf(band.get("activeSchedulesCsv")));
        if (!allowedSchedules.isEmpty() && !allowedSchedules.contains(schedule)) {
            throw new IllegalArgumentException("The selected payment schedule is not enabled for this fee plan");
        }
        Map<String, Object> student = jdbc.sql("""
                        SELECT class_id, academic_year_id
                        FROM student.students
                        WHERE id = :studentId AND deleted_at IS NULL
                        """)
                .param("studentId", studentId)
                .query((rs, rowNum) -> row(
                        "classId", rs.getString("class_id"),
                        "academicYearId", rs.getString("academic_year_id")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Assign the student to a class before assigning fees"));
        String studentClassId = String.valueOf(student.get("classId"));
        if (!academicYearId.equals(student.get("academicYearId"))) {
            throw new IllegalArgumentException("The student enrollment belongs to a different academic year");
        }
        int studentClassOrder = classSortOrder(studentClassId);
        int classFrom = ((Number) band.get("classFrom")).intValue();
        int classTo = ((Number) band.get("classTo")).intValue();
        if (studentClassOrder < classFrom || studentClassOrder > classTo) {
            throw new IllegalArgumentException("The selected fee plan does not cover the student's class");
        }

        String assignmentId = jdbc.sql("""
                SELECT id FROM fee.fee_assignments
                WHERE student_id = :studentId AND academic_year_id = :academicYearId
                """)
                .param("studentId", studentId)
                .param("academicYearId", academicYearId)
                .query(String.class)
                .optional()
                .orElse(UUID.randomUUID().toString());
        boolean exists = jdbc.sql("SELECT COUNT(*) FROM fee.fee_assignments WHERE id = :id")
                .param("id", assignmentId)
                .query(Long.class)
                .single() > 0;
        List<String> selectedOptionalItemIds = request.get("optionalItemIds") instanceof List<?> values
                ? values.stream()
                        .map(String::valueOf)
                        .filter(value -> !value.isBlank())
                        .distinct()
                        .toList()
                : List.of();
        long bandTotal = bandTotal(bandId, selectedOptionalItemIds);
        String optionalItemIdsCsv = String.join(",", selectedOptionalItemIds);
        double bandDiscount = request.containsKey("bandDiscount")
                ? doubleValue(request.get("bandDiscount"), 0)
                : ((Number) band.get("discount")).doubleValue();
        double manualDiscount = doubleValue(request.get("manualDiscount"), 0);
        Long discountRuleId = request.get("discountRuleId") == null
                || String.valueOf(request.get("discountRuleId")).isBlank()
                ? null
                : longValue(request.get("discountRuleId"), -1);
        double ruleDiscount = 0;
        if (discountRuleId != null) {
            Map<String, Object> rule = jdbc.sql("""
                    SELECT id, percentage
                    FROM fee.fee_discount_rules
                    WHERE id = :id AND school_id = :schoolId AND academic_year_id = :academicYearId
                      AND active = true
                    """)
                    .param("id", discountRuleId)
                    .param("schoolId", schoolId)
                    .param("academicYearId", academicYearId)
                    .query((rs, rowNum) -> row(
                            "id", rs.getLong("id"),
                            "percentage", rs.getDouble("percentage")))
                    .optional()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "The selected concession rule is not active for this academic year"));
            ruleDiscount = ((Number) rule.get("percentage")).doubleValue();
        }
        double surcharge = "Annual".equalsIgnoreCase(schedule) ? 0 : doubleValue(request.get("surcharge"), 0);
        if (bandDiscount < 0 || manualDiscount < 0 || ruleDiscount < 0 || surcharge < 0
                || bandDiscount > 100 || manualDiscount > 100 || ruleDiscount > 100 || surcharge > 100) {
            throw new IllegalArgumentException("Discount and surcharge percentages must be between 0 and 100");
        }
        if (bandDiscount + manualDiscount + ruleDiscount > 100) {
            throw new IllegalArgumentException("Combined discounts cannot exceed 100 percent");
        }
        long netPayable = calculateNetPayable(
                bandTotal, bandDiscount, manualDiscount + ruleDiscount, surcharge, schedule);
        Long actorId = request.get("actorId") != null ? longValue(request.get("actorId"), 0) : null;
        OffsetDateTime now = OffsetDateTime.now();

        if (exists) {
            jdbc.sql("""
                    UPDATE fee.fee_assignments
                    SET schedule = :schedule, band_discount = :bandDiscount, manual_discount = :manualDiscount,
                        discount_rule_id = :discountRuleId, rule_discount = :ruleDiscount,
                        gross_fee = :grossFee, selected_optional_item_ids_csv = :optionalItemIds,
                        surcharge = :surcharge, base_payable = :netPayable, late_fee_accrued = 0,
                        net_payable = :netPayable, updated_by = :actorId,
                        updated_at = :updatedAt, band_id = :bandId, academic_year_id = :academicYearId
                    WHERE id = :id
                    """)
                    .param("id", assignmentId)
                    .param("schedule", schedule)
                    .param("bandDiscount", bandDiscount)
                    .param("manualDiscount", manualDiscount)
                    .param("discountRuleId", discountRuleId)
                    .param("ruleDiscount", ruleDiscount)
                    .param("grossFee", bandTotal)
                    .param("optionalItemIds", optionalItemIdsCsv)
                    .param("surcharge", surcharge)
                    .param("netPayable", netPayable)
                    .param("actorId", actorId)
                    .param("updatedAt", now)
                    .param("bandId", bandId)
                    .param("academicYearId", academicYearId)
                    .update();
        } else {
            jdbc.sql("""
                    INSERT INTO fee.fee_assignments(id, schedule, band_discount, manual_discount,
                                                discount_rule_id, rule_discount, surcharge,
                                                gross_fee, selected_optional_item_ids_csv,
                                                base_payable, late_fee_accrued, net_payable,
                                                paid_amount, assigned_by, assigned_at,
                                                updated_by, updated_at, student_id, band_id, academic_year_id, version,
                                                school_id)
                    VALUES (:id, :schedule, :bandDiscount, :manualDiscount,
                            :discountRuleId, :ruleDiscount, :surcharge,
                            :grossFee, :optionalItemIds,
                            :netPayable, 0, :netPayable, 0,
                            :actorId, :assignedAt, :actorId, :updatedAt, :studentId, :bandId, :academicYearId, 0,
                            :schoolId)
                    """)
                    .param("id", assignmentId)
                    .param("schedule", schedule)
                    .param("bandDiscount", bandDiscount)
                    .param("manualDiscount", manualDiscount)
                    .param("discountRuleId", discountRuleId)
                    .param("ruleDiscount", ruleDiscount)
                    .param("grossFee", bandTotal)
                    .param("optionalItemIds", optionalItemIdsCsv)
                    .param("surcharge", surcharge)
                    .param("netPayable", netPayable)
                    .param("actorId", actorId)
                    .param("assignedAt", now)
                    .param("updatedAt", now)
                    .param("studentId", studentId)
                    .param("bandId", bandId)
                    .param("academicYearId", academicYearId)
                    .param("schoolId", schoolId)
                    .update();
        }
        updateStudentFeeStatus(studentId, assignmentId);
        emitFeeAssignmentUpserted(assignmentId);
        return row("ok", true, "assignment", assignmentRecord(assignmentId));
    }

    @Transactional
    public Map<String, Object> recordPayment(Map<String, Object> request) {
        long studentId = longValue(request.get("studentId"), -1);
        long amount = paymentAmountToPaise(request);
        if (studentId <= 0) {
            throw new IllegalArgumentException("Student id is required");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }
        Long schoolId = studentSchoolId(studentId);
        String academicYearId = currentAcademicYearId(schoolId);
        refreshLateFeesForStudent(schoolId, academicYearId, studentId);
        Map<String, Object> assignment = jdbc.sql("""
                SELECT id, net_payable, paid_amount FROM fee.fee_assignments
                WHERE student_id = :studentId AND academic_year_id = :academicYearId
                ORDER BY assigned_at DESC
                LIMIT 1
                """)
                .param("studentId", studentId)
                .param("academicYearId", academicYearId)
                .query((rs, rowNum) -> row(
                        "id", rs.getString("id"),
                        "netPayable", rs.getLong("net_payable"),
                        "paidAmount", rs.getLong("paid_amount")))
                .optional()
                .or(() -> jdbc.sql("""
                        SELECT id, net_payable, paid_amount FROM fee.fee_assignments
                        WHERE student_id = :studentId
                        ORDER BY assigned_at DESC
                        LIMIT 1
                        """)
                        .param("studentId", studentId)
                        .query((rs, rowNum) -> row(
                                "id", rs.getString("id"),
                                "netPayable", rs.getLong("net_payable"),
                                "paidAmount", rs.getLong("paid_amount")))
                        .optional())
                .orElseThrow(() -> new IllegalArgumentException("Fee assignment not found. Assign a fee plan first."));

        String paymentId = UUID.randomUUID().toString();
        OffsetDateTime paidAt = parsePaidAt(request.get("paidAt"));
        OffsetDateTime now = OffsetDateTime.now();
        Long actorId = request.get("actorId") != null ? longValue(request.get("actorId"), 0) : null;
        String receiptNumber = "RCPT-" + System.currentTimeMillis();
        String assignmentId = String.valueOf(assignment.get("id"));
        long netPayable = longValue(assignment.get("netPayable"), 0);
        long paidAmount = longValue(assignment.get("paidAmount"), 0);
        long remainingDue = Math.max(0, netPayable - paidAmount);
        if (amount > remainingDue) {
            throw new IllegalArgumentException("Amount exceeds the remaining due");
        }

        jdbc.sql("""
                INSERT INTO fee.payment_records(id, amount, mode, notes, paid_at, recorded_by, receipt_number,
                                            created_at, student_id, assignment_id, version, school_id)
                VALUES (:id, :amount, :mode, :notes, :paidAt, :recordedBy, :receiptNumber,
                        :createdAt, :studentId, :assignmentId, 0, :schoolId)
                """)
                .param("id", paymentId)
                .param("amount", amount)
                .param("mode", textOrDefault(request.get("mode"), "UPI"))
                .param("notes", textOrDefault(request.get("notes"), ""))
                .param("paidAt", paidAt)
                .param("recordedBy", actorId)
                .param("receiptNumber", receiptNumber)
                .param("createdAt", now)
                .param("studentId", studentId)
                .param("assignmentId", assignmentId)
                .param("schoolId", schoolId)
                .update();

        jdbc.sql("""
                UPDATE fee.fee_assignments
                SET paid_amount = paid_amount + :amount, updated_by = :actorId, updated_at = :updatedAt,
                    academic_year_id = :academicYearId
                WHERE id = :assignmentId
                """)
                .param("amount", amount)
                .param("actorId", actorId)
                .param("updatedAt", now)
                .param("academicYearId", academicYearId)
                .param("assignmentId", assignmentId)
                .update();
        updateStudentFeeStatus(studentId, assignmentId);
        Map<String, Object> updatedAssignment = jdbc.sql("""
                        SELECT paid_amount, net_payable
                        FROM fee.fee_assignments
                        WHERE id = :assignmentId
                        """)
                .param("assignmentId", assignmentId)
                .query((rs, rowNum) -> row(
                        "paidAmount", rs.getLong("paid_amount"),
                        "netPayable", rs.getLong("net_payable")))
                .single();
        emitFeeAssignmentUpserted(assignmentId);
        emitPaymentRecorded(paymentId, assignmentId, schoolId, studentId, amount, paidAt);
        return row(
                "paymentId", paymentId,
                "receiptNumber", receiptNumber,
                "receiptUrl", "/api/v1/receipts/" + paymentId + "/pdf",
                "studentId", studentId,
                "schoolId", schoolId,
                "assignmentId", assignmentId,
                "academicYearId", academicYearId,
                "amount", amount,
                "mode", textOrDefault(request.get("mode"), "UPI"),
                "paidAmount", updatedAssignment.get("paidAmount"),
                "netPayable", updatedAssignment.get("netPayable"),
                "actorId", actorId,
                "paidAt", paidAt);
    }

    private Map<String, Object> receipt(String predicate, ParamBinder binder, String notFoundMessage) {
        var spec = jdbc.sql("""
                        SELECT p.id, p.amount, p.mode, p.paid_at, p.receipt_number,
                               s.id AS student_id, s.full_name AS student_name
                        FROM fee.payment_records p
                        LEFT JOIN student.students s ON s.id = p.student_id
                        WHERE """ + " " + predicate + " " + """
                        LIMIT 1
                        """);
        return binder.bind(spec)
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

    private List<Map<String, Object>> feeReportRows(
            String classId, String sectionId, String academicYearId, Long schoolId, boolean overdueOnly) {
        refreshLateFeesForClass(schoolId, academicYearId, classId, sectionId);
        StringBuilder sql = new StringBuilder("""
                SELECT fa.id, fa.schedule, fa.band_discount, fa.manual_discount, fa.rule_discount,
                       dr.name AS discount_rule_name, fa.surcharge,
                       fa.gross_fee, fa.selected_optional_item_ids_csv,
                       fa.base_payable, fa.late_fee_accrued, fa.net_payable, fa.paid_amount,
                       s.id AS student_id, s.full_name AS student_name,
                       s.admission_no AS admission_no,
                       fb.id AS band_id, fb.name AS plan_name,
                       COALESCE(fi.total_annual_fee, 0) AS total_annual_fee,
                       latest_payment.id AS latest_payment_id
                FROM fee.fee_assignments fa
                JOIN student.students s ON s.id = fa.student_id
                JOIN fee.fee_bands fb ON fb.id = fa.band_id
                LEFT JOIN fee.fee_discount_rules dr ON dr.id = fa.discount_rule_id
                LEFT JOIN (
                    SELECT band_id, SUM(amount) AS total_annual_fee
                    FROM fee.fee_items
                    GROUP BY band_id
                ) fi ON fi.band_id = fb.id
                LEFT JOIN LATERAL (
                    SELECT id
                    FROM fee.payment_records p
                    WHERE p.student_id = s.id
                    ORDER BY p.paid_at DESC NULLS LAST, p.created_at DESC NULLS LAST
                    LIMIT 1
                ) latest_payment ON true
                WHERE s.school_id = :schoolId
                  AND s.class_id = :classId
                  AND s.section_id = :sectionId
                  AND s.deleted_at IS NULL
                  AND fa.academic_year_id = :academicYearId
                """);
        sql.append(" ORDER BY s.full_name ASC");

        List<Map<String, Object>> rows = jdbc.sql(sql.toString())
                .param("schoolId", schoolId)
                .param("classId", classId)
                .param("sectionId", sectionId)
                .param("academicYearId", academicYearId)
                .query((rs, rowNum) -> {
                    long totalAnnualFee = rs.getLong("gross_fee");
                    if (totalAnnualFee == 0) totalAnnualFee = rs.getLong("total_annual_fee");
                    double bandDiscountPercent = rs.getDouble("band_discount");
                    double manualDiscountPercent = rs.getDouble("manual_discount");
                    double ruleDiscountPercent = rs.getDouble("rule_discount");
                    double surchargePercent = rs.getDouble("surcharge");
                    long bandDiscountAmount = percentageAmount(totalAnnualFee, bandDiscountPercent);
                    long manualDiscountAmount = percentageAmount(totalAnnualFee, manualDiscountPercent);
                    long ruleDiscountAmount = percentageAmount(totalAnnualFee, ruleDiscountPercent);
                    long approvedDiscount = bandDiscountAmount + manualDiscountAmount + ruleDiscountAmount;
                    long surchargeAmount = "Annual".equalsIgnoreCase(rs.getString("schedule"))
                            ? 0
                            : percentageAmount(totalAnnualFee, surchargePercent);
                    long due = Math.max(0, rs.getLong("net_payable") - rs.getLong("paid_amount"));
                    return row(
                            "assignmentId", rs.getString("id"),
                            "studentId", rs.getLong("student_id"),
                            "paymentId", textOrDefault(rs.getString("latest_payment_id"), ""),
                            "student", rs.getString("student_name"),
                            "admissionNumber", textOrDefault(rs.getString("admission_no"), ""),
                            "planName", rs.getString("plan_name"),
                            "schedule", rs.getString("schedule"),
                            "selectedOptionalItemIds", csvValues(rs.getString("selected_optional_item_ids_csv")),
                            "totalAnnualFee", totalAnnualFee,
                            "totalAnnualFeePaise", totalAnnualFee,
                            "approvedDiscount", approvedDiscount,
                            "approvedDiscountPaise", approvedDiscount,
                            "discounts", approvedDiscount,
                            "discountPercent", round(bandDiscountPercent + manualDiscountPercent + ruleDiscountPercent),
                            "concessionRule", textOrDefault(rs.getString("discount_rule_name"), ""),
                            "concessionDiscountPercent", round(ruleDiscountPercent),
                            "surchargeAmount", surchargeAmount,
                            "surchargeAmountPaise", surchargeAmount,
                            "surcharge", surchargeAmount,
                            "surchargePercent", round(surchargePercent),
                            "lateFee", rs.getLong("late_fee_accrued"),
                            "lateFeePaise", rs.getLong("late_fee_accrued"),
                            "paid", rs.getLong("paid_amount"),
                            "paidPaise", rs.getLong("paid_amount"),
                            "due", due,
                            "dueAmount", due,
                            "dueAmountPaise", due,
                            "status", due <= 0 ? "Paid" : rs.getLong("paid_amount") > 0 ? "Partial" : "Pending");
                })
                .list();
        for (Map<String, Object> reportRow : rows) {
            List<Map<String, Object>> breakdown = assignmentInstallments(String.valueOf(reportRow.get("assignmentId")), false);
            reportRow.put("installments", breakdown);
            long daysOverdue = breakdown.stream()
                    .mapToLong(row -> longValue(row.get("daysOverdue"), 0))
                    .max()
                    .orElse(0);
            reportRow.put("daysOverdue", daysOverdue);
            if (((Number) reportRow.get("due")).longValue() <= 0) {
                reportRow.put("status", "Paid");
            } else if (daysOverdue > 0) {
                reportRow.put("status", "Overdue");
            }
        }
        if (overdueOnly) {
            return rows.stream()
                    .filter(row -> "Overdue".equals(row.get("status")))
                    .toList();
        }
        return rows;
    }

    public record FeeBandRow(
            String id,
            String name,
            Integer classFrom,
            Integer classTo,
            Double discount,
            String activeSchedulesCsv,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String academicYearId) {
    }

    public record FeeItemRow(
            String id,
            String name,
            String frequency,
            Long amount,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String bandId) {
    }

    public record FeeAssignmentRow(
            String id,
            String schedule,
            Double bandDiscount,
            Double manualDiscount,
            Double surcharge,
            Long netPayable,
            Long paidAmount,
            Long assignedBy,
            OffsetDateTime assignedAt,
            Long updatedBy,
            OffsetDateTime updatedAt,
            Long studentId,
            String bandId,
            String academicYearId) {
    }

    public record PaymentRow(
            String id,
            Long amount,
            String mode,
            String notes,
            OffsetDateTime paidAt,
            Long recordedBy,
            String receiptNumber,
            OffsetDateTime createdAt,
            Long studentId,
            String assignmentId,
            String student) {
    }

    private interface ParamBinder {
        org.springframework.jdbc.core.simple.JdbcClient.StatementSpec bind(
                org.springframework.jdbc.core.simple.JdbcClient.StatementSpec spec);
    }

    private String currentAcademicYearId() {
        return AcademicCalendar.activeOrCurrentAcademicYearId(jdbc);
    }

    private String currentAcademicYearId(Long schoolId) {
        return schoolId == null ? currentAcademicYearId() : AcademicCalendar.currentAcademicYearId(jdbc, schoolId);
    }

    private String resolveAcademicYearId(String academicYearId) {
        return academicYearId == null || academicYearId.isBlank() ? currentAcademicYearId() : academicYearId;
    }

    private String resolveAcademicYearId(String academicYearId, Long schoolId) {
        return academicYearId == null || academicYearId.isBlank() ? currentAcademicYearId(schoolId) : academicYearId;
    }

    private Map<String, Object> academicYear(String academicYearId) {
        return academicYear(academicYearId, null);
    }

    private Map<String, Object> academicYear(String academicYearId, Long schoolId) {
        AcademicCalendar.AcademicYear year = AcademicCalendar.academicYear(jdbc, academicYearId)
                .orElseGet(() -> schoolId == null
                        ? AcademicCalendar.activeOrCurrentAcademicYear(jdbc)
                        : AcademicCalendar.currentAcademicYear(jdbc, schoolId));
        return row("id", year.id(), "label", year.label());
    }

    private Map<String, Object> bandWithItems(String id) {
        Map<String, Object> band = bandRecord(id);
        List<Map<String, Object>> items = jdbc.sql("""
                SELECT id, name, frequency, amount, optional, created_at, updated_at, band_id
                FROM fee.fee_items
                WHERE band_id = :id
                ORDER BY created_at
                """)
                .param("id", id)
                .query((rs, rowNum) -> row(
                        "id", rs.getString("id"),
                        "name", rs.getString("name"),
                        "frequency", rs.getString("frequency"),
                        "amount", rs.getLong("amount"),
                        "optional", rs.getBoolean("optional"),
                        "createdAt", rs.getObject("created_at", OffsetDateTime.class),
                        "updatedAt", rs.getObject("updated_at", OffsetDateTime.class),
                        "bandId", rs.getString("band_id")))
                .list();
        band.put("items", items);
        band.put("annualTotal", items.stream().mapToLong(item -> ((Number) item.get("amount")).longValue()).sum());
        band.put("installments", jdbc.sql("""
                        SELECT id, label, due_date, share_percent, sort_order
                        FROM fee.fee_installments
                        WHERE band_id = :bandId
                        ORDER BY sort_order, due_date
                        """)
                .param("bandId", id)
                .query((rs, rowNum) -> row(
                        "id", rs.getLong("id"),
                        "label", rs.getString("label"),
                        "dueDate", rs.getObject("due_date", LocalDate.class),
                        "sharePercent", rs.getDouble("share_percent"),
                        "sortOrder", rs.getInt("sort_order")))
                .list());
        Long assignmentCount = jdbc.sql("SELECT COUNT(*) FROM fee.fee_assignments WHERE band_id = :bandId")
                .param("bandId", id)
                .query(Long.class)
                .single();
        band.put("assignmentCount", assignmentCount == null ? 0 : assignmentCount);
        return band;
    }

    private Map<String, Object> bandRecord(String id) {
        return jdbc.sql("""
                SELECT b.id, b.name, b.class_from, b.class_to, b.discount, b.active_schedules_csv,
                       b.created_at, b.updated_at, b.academic_year_id, b.school_id, y.label AS academic_year,
                       b.status, b.revision, b.published_at, b.grace_period_days,
                       b.late_fee_type, b.late_fee_amount, b.late_fee_interval_days,
                       b.supersedes_band_id
                FROM fee.fee_bands b
                LEFT JOIN tenant_school.academic_years y ON y.id = b.academic_year_id
                WHERE b.id = :id
                """)
                .param("id", id)
                .query((rs, rowNum) -> row(
                        "id", rs.getString("id"),
                        "name", rs.getString("name"),
                        "groupName", rs.getString("name"),
                        "classFrom", rs.getInt("class_from"),
                        "classTo", rs.getInt("class_to"),
                        "discount", rs.getDouble("discount"),
                        "activeSchedulesCsv", rs.getString("active_schedules_csv"),
                        "activeSchedules", splitCsv(rs.getString("active_schedules_csv")),
                        "allowedSchedules", splitCsv(rs.getString("active_schedules_csv")),
                        "createdAt", rs.getObject("created_at", OffsetDateTime.class),
                        "updatedAt", rs.getObject("updated_at", OffsetDateTime.class),
                        "academicYearId", rs.getString("academic_year_id"),
                        "academicYear", rs.getString("academic_year"),
                        "schoolId", rs.getLong("school_id"),
                        "status", rs.getString("status"),
                        "revision", rs.getInt("revision"),
                        "publishedAt", rs.getObject("published_at", OffsetDateTime.class),
                        "gracePeriodDays", rs.getInt("grace_period_days"),
                        "lateFeeType", rs.getString("late_fee_type"),
                        "lateFeeAmount", rs.getLong("late_fee_amount"),
                        "lateFeeIntervalDays", rs.getInt("late_fee_interval_days"),
                        "supersedesBandId", rs.getString("supersedes_band_id")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Fee band not found"));
    }

    private Long requireSchool(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("A school must be selected to create a fee plan");
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid school id");
        }
    }

    private Map<String, Object> itemRecord(String id) {
        return jdbc.sql("SELECT id, name, frequency, amount, optional, band_id FROM fee.fee_items WHERE id = :id")
                .param("id", id)
                .query((rs, rowNum) -> row(
                        "id", rs.getString("id"),
                        "name", rs.getString("name"),
                        "frequency", rs.getString("frequency"),
                        "amount", rs.getLong("amount"),
                        "optional", rs.getBoolean("optional"),
                        "bandId", rs.getString("band_id")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Fee item not found"));
    }

    private Map<String, Object> assignmentRecord(String id) {
        return jdbc.sql("""
                SELECT fa.id, fa.schedule, fa.band_discount, fa.manual_discount, fa.rule_discount,
                       fa.discount_rule_id, dr.name AS discount_rule_name, fa.surcharge,
                       fa.gross_fee, fa.selected_optional_item_ids_csv,
                       fa.base_payable, fa.late_fee_accrued, fa.net_payable, fa.paid_amount,
                       fa.assigned_by, fa.assigned_at,
                       fa.updated_by, fa.updated_at, fa.student_id, fa.band_id,
                       fa.academic_year_id, s.school_id
                FROM fee.fee_assignments fa
                JOIN student.students s ON s.id = fa.student_id
                LEFT JOIN fee.fee_discount_rules dr ON dr.id = fa.discount_rule_id
                WHERE fa.id = :id
                """)
                .param("id", id)
                .query((rs, rowNum) -> row(
                        "id", rs.getString("id"),
                        "schedule", rs.getString("schedule"),
                        "bandDiscount", rs.getDouble("band_discount"),
                        "manualDiscount", rs.getDouble("manual_discount"),
                        "ruleDiscount", rs.getDouble("rule_discount"),
                        "discountRuleId", rs.getObject("discount_rule_id"),
                        "discountRuleName", textOrDefault(rs.getString("discount_rule_name"), ""),
                        "surcharge", rs.getDouble("surcharge"),
                        "grossFee", rs.getLong("gross_fee"),
                        "selectedOptionalItemIds", csvValues(rs.getString("selected_optional_item_ids_csv")),
                        "basePayable", rs.getLong("base_payable"),
                        "lateFeeAccrued", rs.getLong("late_fee_accrued"),
                        "netPayable", rs.getLong("net_payable"),
                        "paidAmount", rs.getLong("paid_amount"),
                        "assignedBy", rs.getLong("assigned_by"),
                        "assignedAt", rs.getObject("assigned_at", OffsetDateTime.class),
                        "updatedBy", rs.getLong("updated_by"),
                        "updatedAt", rs.getObject("updated_at", OffsetDateTime.class),
                        "studentId", rs.getLong("student_id"),
                        "schoolId", rs.getLong("school_id"),
                        "bandId", rs.getString("band_id"),
                        "academicYearId", rs.getString("academic_year_id")))
                .single();
    }

    private void refreshLateFeesForClass(
            Long schoolId, String academicYearId, String classId, String sectionId) {
        List<String> assignmentIds = jdbc.sql("""
                SELECT fa.id
                FROM fee.fee_assignments fa
                JOIN student.students s ON s.id = fa.student_id
                WHERE fa.school_id = :schoolId
                  AND fa.academic_year_id = :academicYearId
                  AND s.class_id = :classId
                  AND s.section_id = :sectionId
                  AND s.deleted_at IS NULL
                """)
                .param("schoolId", schoolId)
                .param("academicYearId", academicYearId)
                .param("classId", classId)
                .param("sectionId", sectionId)
                .query(String.class)
                .list();
        assignmentIds.forEach(id -> assignmentInstallments(id, true));
    }

    private void refreshLateFeesForStudent(Long schoolId, String academicYearId, long studentId) {
        jdbc.sql("""
                SELECT id
                FROM fee.fee_assignments
                WHERE school_id = :schoolId AND academic_year_id = :academicYearId
                  AND student_id = :studentId
                """)
                .param("schoolId", schoolId)
                .param("academicYearId", academicYearId)
                .param("studentId", studentId)
                .query(String.class)
                .list()
                .forEach(id -> assignmentInstallments(id, true));
    }

    private void refreshLateFeesForScope(Long schoolId, String academicYearId) {
        jdbc.sql("""
                SELECT id
                FROM fee.fee_assignments
                WHERE school_id = :schoolId AND academic_year_id = :academicYearId
                """)
                .param("schoolId", schoolId)
                .param("academicYearId", academicYearId)
                .query(String.class)
                .list()
                .forEach(id -> assignmentInstallments(id, true));
    }

    private List<Map<String, Object>> assignmentInstallments(String assignmentId, boolean accrueLateFee) {
        Map<String, Object> assignment = jdbc.sql("""
                SELECT fa.base_payable, fa.paid_amount, fa.late_fee_accrued, fa.assigned_at,
                       fb.grace_period_days, fb.late_fee_type, fb.late_fee_amount,
                       fb.late_fee_interval_days, fb.id AS band_id
                FROM fee.fee_assignments fa
                JOIN fee.fee_bands fb ON fb.id = fa.band_id
                WHERE fa.id = :assignmentId
                """)
                .param("assignmentId", assignmentId)
                .query((rs, rowNum) -> row(
                        "basePayable", rs.getLong("base_payable"),
                        "paidAmount", rs.getLong("paid_amount"),
                        "lateFeeAccrued", rs.getLong("late_fee_accrued"),
                        "assignedAt", rs.getObject("assigned_at", OffsetDateTime.class),
                        "gracePeriodDays", rs.getInt("grace_period_days"),
                        "lateFeeType", rs.getString("late_fee_type"),
                        "lateFeeAmount", rs.getLong("late_fee_amount"),
                        "lateFeeIntervalDays", rs.getInt("late_fee_interval_days"),
                        "bandId", rs.getString("band_id")))
                .optional()
                .orElseGet(LinkedHashMap::new);
        if (assignment.isEmpty()) return List.of();

        List<Map<String, Object>> configured = jdbc.sql("""
                SELECT label, due_date, share_percent, sort_order
                FROM fee.fee_installments
                WHERE band_id = :bandId
                ORDER BY sort_order, due_date, id
                """)
                .param("bandId", assignment.get("bandId"))
                .query((rs, rowNum) -> row(
                        "label", rs.getString("label"),
                        "dueDate", rs.getObject("due_date", LocalDate.class),
                        "sharePercent", rs.getDouble("share_percent")))
                .list();
        if (configured.isEmpty()) {
            OffsetDateTime assignedAt = (OffsetDateTime) assignment.get("assignedAt");
            configured = new ArrayList<>();
            configured.add(row(
                    "label", "Annual balance",
                    "dueDate", assignedAt == null ? LocalDate.now() : assignedAt.toLocalDate(),
                    "sharePercent", 100.0));
        }

        long basePayable = longValue(assignment.get("basePayable"), 0);
        long paidAmount = longValue(assignment.get("paidAmount"), 0);
        long currentLateFee = longValue(assignment.get("lateFeeAccrued"), 0);
        int graceDays = intValue(assignment.get("gracePeriodDays"), 0);
        String lateFeeType = textOrDefault(assignment.get("lateFeeType"), "NONE");
        long lateFeeAmount = longValue(assignment.get("lateFeeAmount"), 0);
        int intervalDays = Math.max(1, intValue(assignment.get("lateFeeIntervalDays"), 1));
        LocalDate today = LocalDate.now();

        List<Map<String, Object>> calculated = new ArrayList<>();
        long allocatedBase = 0;
        long remainingBasePayment = Math.min(paidAmount, basePayable);
        long calculatedLateFee = 0;
        for (int index = 0; index < configured.size(); index++) {
            Map<String, Object> source = configured.get(index);
            double share = doubleValue(source.get("sharePercent"), 0);
            long amount = index == configured.size() - 1
                    ? Math.max(0, basePayable - allocatedBase)
                    : percentageAmount(basePayable, share);
            allocatedBase += amount;
            long basePaid = Math.min(amount, remainingBasePayment);
            remainingBasePayment -= basePaid;
            long outstandingBase = Math.max(0, amount - basePaid);
            LocalDate dueDate = (LocalDate) source.get("dueDate");
            long daysOverdue = dueDate == null ? 0
                    : Math.max(0, ChronoUnit.DAYS.between(dueDate.plusDays(graceDays), today));
            long installmentLateFee = 0;
            if (outstandingBase > 0 && daysOverdue > 0 && lateFeeAmount > 0) {
                if ("FIXED".equals(lateFeeType)) {
                    installmentLateFee = lateFeeAmount;
                } else if ("DAILY".equals(lateFeeType)) {
                    long intervals = Math.max(1, (daysOverdue + intervalDays - 1) / intervalDays);
                    installmentLateFee = Math.multiplyExact(lateFeeAmount, intervals);
                }
            }
            calculatedLateFee = Math.addExact(calculatedLateFee, installmentLateFee);
            calculated.add(row(
                    "label", source.get("label"),
                    "dueDate", dueDate,
                    "sharePercent", round(share),
                    "baseAmount", amount,
                    "lateFee", installmentLateFee,
                    "daysOverdue", daysOverdue));
        }

        long effectiveLateFee = Math.max(currentLateFee, calculatedLateFee);
        if (effectiveLateFee > calculatedLateFee && !calculated.isEmpty()) {
            Map<String, Object> firstOutstanding = calculated.stream()
                    .filter(row -> longValue(row.get("daysOverdue"), 0) > 0)
                    .findFirst()
                    .orElse(calculated.get(0));
            firstOutstanding.put("lateFee",
                    longValue(firstOutstanding.get("lateFee"), 0) + effectiveLateFee - calculatedLateFee);
        }
        if (accrueLateFee && effectiveLateFee != currentLateFee) {
            jdbc.sql("""
                    UPDATE fee.fee_assignments
                    SET late_fee_accrued = GREATEST(late_fee_accrued, :lateFee),
                        net_payable = base_payable + GREATEST(late_fee_accrued, :lateFee),
                        updated_at = :updatedAt
                    WHERE id = :assignmentId
                    """)
                    .param("lateFee", effectiveLateFee)
                    .param("updatedAt", OffsetDateTime.now())
                    .param("assignmentId", assignmentId)
                    .update();
            emitFeeAssignmentUpserted(assignmentId);
        }

        long remainingPayment = paidAmount;
        for (Map<String, Object> installment : calculated) {
            long charge = longValue(installment.get("baseAmount"), 0)
                    + longValue(installment.get("lateFee"), 0);
            long paid = Math.min(charge, remainingPayment);
            remainingPayment -= paid;
            long due = Math.max(0, charge - paid);
            long daysOverdue = longValue(installment.get("daysOverdue"), 0);
            installment.put("amount", charge);
            installment.put("paid", paid);
            installment.put("dueAmount", due);
            installment.put("status", due <= 0 ? "Paid" : daysOverdue > 0 ? "Overdue" : "Upcoming");
        }
        return calculated;
    }

    private void requireStudent(long studentId) {
        studentSchoolId(studentId);
    }

    private Long studentSchoolId(long studentId) {
        return jdbc.sql("SELECT school_id FROM student.students WHERE id = :studentId AND deleted_at IS NULL")
                .param("studentId", studentId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));
    }

    private long bandTotal(String bandId, List<String> selectedOptionalItemIds) {
        List<Map<String, Object>> items = jdbc.sql("""
                SELECT id, amount, optional
                FROM fee.fee_items
                WHERE band_id = :bandId
                """)
                .param("bandId", bandId)
                .query((rs, rowNum) -> row(
                        "id", rs.getString("id"),
                        "amount", rs.getLong("amount"),
                        "optional", rs.getBoolean("optional")))
                .list();
        List<String> allowedOptionalIds = items.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("optional")))
                .map(item -> String.valueOf(item.get("id")))
                .toList();
        if (!allowedOptionalIds.containsAll(selectedOptionalItemIds)) {
            throw new IllegalArgumentException("An optional fee head does not belong to the selected plan");
        }
        return items.stream()
                .filter(item -> !Boolean.TRUE.equals(item.get("optional"))
                        || selectedOptionalItemIds.contains(String.valueOf(item.get("id"))))
                .mapToLong(item -> longValue(item.get("amount"), 0))
                .sum();
    }

    private long calculateNetPayable(long total, double bandDiscount, double manualDiscount, double surcharge, String schedule) {
        long bandAmount = percentageAmount(total, bandDiscount);
        long manualAmount = percentageAmount(total, manualDiscount);
        long surchargeAmount = "Annual".equalsIgnoreCase(schedule) ? 0 : percentageAmount(total, surcharge);
        return Math.max(total - bandAmount - manualAmount + surchargeAmount, 0);
    }

    private long percentageAmount(long total, double percent) {
        if (total <= 0 || !Double.isFinite(percent) || percent == 0) {
            return 0;
        }
        return Math.round(total * percent / 100.0);
    }

    private void updateStudentFeeStatus(long studentId, String assignmentId) {
        Map<String, Object> assignment = jdbc.sql("SELECT net_payable, paid_amount FROM fee.fee_assignments WHERE id = :id")
                .param("id", assignmentId)
                .query((rs, rowNum) -> row(
                        "netPayable", rs.getLong("net_payable"),
                        "paidAmount", rs.getLong("paid_amount")))
                .single();
        String status = ((Number) assignment.get("paidAmount")).longValue() >= ((Number) assignment.get("netPayable")).longValue()
                ? "Paid"
                : "Overdue";
        jdbc.sql("UPDATE student.students SET fee_status = :status, updated_at = :updatedAt WHERE id = :studentId")
                .param("status", status)
                .param("updatedAt", OffsetDateTime.now())
                .param("studentId", studentId)
                .update();
    }

    private void emitFeeAssignmentUpserted(String assignmentId) {
        Map<String, Object> assignment = jdbc.sql("""
                        SELECT fa.id, fa.student_id, fa.school_id, fa.academic_year_id,
                               fa.net_payable, fa.paid_amount, fa.assigned_at
                        FROM fee.fee_assignments fa
                        WHERE fa.id = :id
                        """)
                .param("id", assignmentId)
                .query((rs, rowNum) -> row(
                        "id", rs.getString("id"),
                        "studentId", rs.getLong("student_id"),
                        "schoolId", rs.getLong("school_id"),
                        "academicYearId", rs.getString("academic_year_id"),
                        "netPayable", rs.getLong("net_payable"),
                        "paidAmount", rs.getLong("paid_amount"),
                        "assignedAt", rs.getObject("assigned_at", OffsetDateTime.class)))
                .single();
        long netPayable = ((Number) assignment.get("netPayable")).longValue();
        long paidAmount = ((Number) assignment.get("paidAmount")).longValue();
        long dueAmount = Math.max(0, netPayable - paidAmount);
        Long schoolId = ((Number) assignment.get("schoolId")).longValue();
        OffsetDateTime assignedAt = (OffsetDateTime) assignment.get("assignedAt");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", assignmentId);
        payload.put("studentId", assignment.get("studentId"));
        payload.put("schoolId", schoolId);
        payload.put("academicYearId", assignment.get("academicYearId"));
        payload.put("netPayable", netPayable);
        payload.put("paidAmount", paidAmount);
        payload.put("dueAmount", dueAmount);
        payload.put("status", dueAmount <= 0 ? "Paid" : "Overdue");
        payload.put("assignedAt", assignedAt == null ? null : assignedAt.toString());
        outbox.append("fee-assignment.upserted.v1", "FeeAssignmentUpserted:" + assignmentId, "FeeAssignment",
                assignmentId, schoolId, payload);
    }

    private void emitPaymentRecorded(String paymentId, String assignmentId, Long schoolId, long studentId,
                                      long amount, OffsetDateTime paidAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", paymentId);
        payload.put("assignmentId", assignmentId);
        payload.put("schoolId", schoolId);
        payload.put("studentId", studentId);
        payload.put("amount", amount);
        payload.put("paidAt", paidAt == null ? null : paidAt.toString());
        outbox.append("payment.recorded.v1", "PaymentRecorded:" + paymentId, "Payment",
                paymentId, schoolId, payload);
    }

    private Object firstPresent(Map<String, Object> request, String... keys) {
        for (String key : keys) {
            if (request.containsKey(key) && request.get(key) != null) {
                return request.get(key);
            }
        }
        return null;
    }

    private String requireText(Object value, String message) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return text;
    }

    private String textOrDefault(Object value, String fallback) {
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.isBlank() ? fallback : text;
    }

    private int intValue(Object value, int fallback) {
        if (value == null) return fallback;
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value).replace(",", "").trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private long longValue(Object value, long fallback) {
        if (value == null) return fallback;
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value).replace(",", "").trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private double doubleValue(Object value, double fallback) {
        if (value == null) return fallback;
        if (value instanceof Number number) return number.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value).replace(",", "").trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private LocalDate parseDate(Object value, String message) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isBlank()) throw new IllegalArgumentException(message);
        try {
            return LocalDate.parse(text);
        } catch (java.time.format.DateTimeParseException ex) {
            throw new IllegalArgumentException("Date must use YYYY-MM-DD format", ex);
        }
    }

    private String feeType(Object value) {
        String type = textOrDefault(value, "NONE").toUpperCase(Locale.ENGLISH);
        if (!List.of("NONE", "FIXED", "DAILY").contains(type)) {
            throw new IllegalArgumentException("Late fee type must be NONE, FIXED, or DAILY");
        }
        return type;
    }

    private String discountRuleType(Object value) {
        String type = textOrDefault(value, "MANUAL").toUpperCase(Locale.ENGLISH);
        if (!List.of("SIBLING", "STAFF_WARD", "EARLY_PAYMENT", "RTE", "MERIT", "MANUAL").contains(type)) {
            throw new IllegalArgumentException("Unsupported discount rule type");
        }
        return type;
    }

    private long moneyFieldToPaise(Object value) {
        if (value == null) return 0;
        return rupeesToPaise(value);
    }

    private void requireDraftBand(Map<String, Object> band) {
        if (!"DRAFT".equals(band.get("status"))) {
            throw new IllegalArgumentException("Create a draft revision before changing a published fee plan");
        }
    }

    private void touchDraftBand(String bandId) {
        jdbc.sql("UPDATE fee.fee_bands SET updated_at = :updatedAt WHERE id = :bandId")
                .param("bandId", bandId)
                .param("updatedAt", OffsetDateTime.now())
                .update();
    }

    private double round(double value) {
        if (!Double.isFinite(value)) return 0;
        return Math.round(value * 10.0) / 10.0;
    }

    private long moneyToPaise(Map<String, Object> request) {
        Object explicitPaise = firstPresent(request, "amountPaise", "paise");
        if (explicitPaise != null) {
            return Math.max(0, longValue(explicitPaise, 0));
        }
        return rupeesToPaise(request.get("amount"));
    }

    private long rupeesToPaise(Object value) {
        double amount = doubleValue(value, 0);
        if (!Double.isFinite(amount) || amount < 0) {
            return 0;
        }
        return Math.round(amount * 100.0);
    }

    private long paymentAmountToPaise(Map<String, Object> request) {
        Object explicitPaise = firstPresent(request, "amountPaise", "paise");
        if (explicitPaise != null) {
            return Math.max(0, longValue(explicitPaise, 0));
        }
        Object explicitRupees = firstPresent(request, "amountRupees", "rupees");
        if (explicitRupees != null) {
            return rupeesToPaise(explicitRupees);
        }
        return Math.max(0, longValue(request.get("amount"), 0));
    }

    private OffsetDateTime parsePaidAt(Object value) {
        if (value == null) {
            return OffsetDateTime.now();
        }
        try {
            return OffsetDateTime.parse(String.valueOf(value));
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("paidAt must be an ISO-8601 offset date-time", ex);
        }
    }

    private void validateClassRange(int classFrom, int classTo) {
        if (classTo < classFrom) {
            throw new IllegalArgumentException("Class to must be >= class from");
        }
    }

    private String schedulesCsv(Object value, boolean required) {
        if (!(value instanceof List<?> list)) {
            if (required) {
                throw new IllegalArgumentException("At least one payment schedule is required");
            }
            return "";
        }
        String csv = list.stream()
                .map(String::valueOf)
                .map(String::trim)
                .filter(text -> !text.isBlank())
                .distinct()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        if (required && csv.isBlank()) {
            throw new IllegalArgumentException("At least one payment schedule is required");
        }
        return csv;
    }

    private List<String> splitCsv(String csv) {
        return csv == null || csv.isBlank() ? List.of()
                : Arrays.stream(csv.split(",")).map(String::trim).filter(value -> !value.isBlank()).toList();
    }

    private List<String> csvValues(String csv) {
        return splitCsv(csv).stream().distinct().toList();
    }

    private int classSortOrder(String classId) {
        String value = String.valueOf(classId);
        Integer catalogLevel = jdbc.sql("""
                        SELECT
                            CASE
                                WHEN lower(id) = 'pre-primary' THEN -2
                                WHEN lower(id) = 'lkg' THEN -1
                                WHEN lower(id) = 'ukg' THEN 0
                                WHEN name ~ '^[0-9]+$' THEN name::int
                                WHEN id ~ '^[0-9]+$' THEN id::int
                                ELSE sort_order
                            END
                        FROM tenant_school.school_classes
                        WHERE id = :id
                        """)
                .param("id", value)
                .query(Integer.class)
                .optional()
                .orElse(null);
        if (catalogLevel != null) {
            return catalogLevel;
        }
        long digits = 0;
        boolean foundDigit = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!Character.isDigit(ch)) continue;
            foundDigit = true;
            int digit = Character.digit(ch, 10);
            if (digits > (Integer.MAX_VALUE - digit) / 10L) return Integer.MAX_VALUE;
            digits = digits * 10L + digit;
        }
        return foundDigit ? (int) digits : 0;
    }

    private byte[] simplePdf(String content) {
        String safe = escapePdfText(content);
        String stream = "BT /F1 12 Tf 36 740 Td (" + safe + ") Tj ET\n";
        List<String> objects = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Count 1 /Kids [3 0 R] >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                "<< /Length " + stream.getBytes(StandardCharsets.US_ASCII).length + " >>stream\n"
                        + stream + "endstream",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        );

        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>(objects.size());
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(pdf.length());
            pdf.append(i + 1).append(" 0 obj").append(objects.get(i)).append("endobj\n");
        }
        int xrefOffset = pdf.length();
        pdf.append("xref\n0 ").append(objects.size() + 1).append('\n')
                .append("0000000000 65535 f \n");
        for (Integer offset : offsets) {
            pdf.append(String.format(Locale.ENGLISH, "%010d 00000 n \n", offset));
        }
        pdf.append("trailer<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\n")
                .append("startxref\n").append(xrefOffset).append("\n%%EOF");
        return pdf.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private byte[] receiptPdf(Map<String, Object> payment) {
        return simplePdf("Receipt " + textOrDefault(payment.get("receiptNumber"), "")
                + " | Student: " + textOrDefault(payment.get("studentName"), textOrDefault(payment.get("student"), ""))
                + " | Amount: " + textOrDefault(payment.get("amount"), "0")
                + " | Mode: " + textOrDefault(payment.get("mode"), "")
                + " | Paid at: " + textOrDefault(payment.get("paidAt"), ""));
    }

    private String escapePdfText(String content) {
        if (content == null || content.isBlank()) return "";
        StringBuilder escaped = new StringBuilder(content.length());
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch == '(' || ch == ')' || ch == '\\') {
                escaped.append('\\').append(ch);
            } else if (ch >= 32 && ch <= 126) {
                escaped.append(ch);
            } else {
                escaped.append(' ');
            }
        }
        return escaped.toString();
    }

    private Map<String, Object> row(Object... kv) {
        if (kv.length % 2 != 0) throw new IllegalArgumentException("row requires key/value pairs");
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put(String.valueOf(kv[i]), kv[i + 1]);
        return map;
    }
}
