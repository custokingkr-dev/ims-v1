package com.custoking.ims.schoolcoreservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public class ModuleEntitlementReadRepository {

    private final JdbcClient jdbc;

    public ModuleEntitlementReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ModuleEntitlementRow> list(Long schoolId, Boolean enabled) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, school_id, module_code, enabled, plan, start_date, end_date,
                       notes, created_at, updated_at, created_by
                FROM tenant_school.school_module_entitlements
                WHERE school_id = :schoolId
                """);
        if (enabled != null) sql.append(" AND enabled = :enabled");
        sql.append(" ORDER BY module_code");
        var spec = jdbc.sql(sql.toString()).param("schoolId", schoolId);
        if (enabled != null) spec = spec.param("enabled", enabled);
        return spec.query(ModuleEntitlementRow.class).list();
    }

    @Transactional
    public ModuleEntitlementRow upsert(Long schoolId, String moduleCode, Boolean enabled, String plan,
                                       LocalDate startDate, LocalDate endDate, String notes, Long actorId) {
        String code = normalizeModule(moduleCode);
        jdbc.sql("""
                        INSERT INTO tenant_school.school_module_entitlements
                            (school_id, module_code, enabled, plan, start_date, end_date, notes,
                             created_at, updated_at, created_by)
                        VALUES
                            (:schoolId, :moduleCode, :enabled, :plan, :startDate, :endDate, :notes,
                             now(), now(), :actorId)
                        ON CONFLICT (school_id, module_code)
                        DO UPDATE SET
                            enabled = EXCLUDED.enabled,
                            plan = EXCLUDED.plan,
                            start_date = EXCLUDED.start_date,
                            end_date = EXCLUDED.end_date,
                            notes = EXCLUDED.notes,
                            updated_at = now()
                        """)
                .param("schoolId", schoolId)
                .param("moduleCode", code)
                .param("enabled", enabled == null || enabled)
                .param("plan", blankToNull(plan))
                .param("startDate", startDate)
                .param("endDate", endDate)
                .param("notes", blankToNull(notes))
                .param("actorId", actorId)
                .update();
        return get(schoolId, code);
    }

    @Transactional
    public void disable(Long schoolId, String moduleCode) {
        jdbc.sql("""
                        UPDATE tenant_school.school_module_entitlements
                        SET enabled = false, updated_at = now()
                        WHERE school_id = :schoolId AND module_code = :moduleCode
                        """)
                .param("schoolId", schoolId)
                .param("moduleCode", normalizeModule(moduleCode))
                .update();
    }

    public ModuleEntitlementRow get(Long schoolId, String moduleCode) {
        return jdbc.sql("""
                        SELECT id, school_id, module_code, enabled, plan, start_date, end_date,
                               notes, created_at, updated_at, created_by
                        FROM tenant_school.school_module_entitlements
                        WHERE school_id = :schoolId AND module_code = :moduleCode
                        """)
                .param("schoolId", schoolId)
                .param("moduleCode", normalizeModule(moduleCode))
                .query(ModuleEntitlementRow.class)
                .single();
    }

    public boolean anyEnabled(Long schoolId, Collection<String> moduleCodes) {
        if (schoolId == null || moduleCodes == null || moduleCodes.isEmpty()) {
            return false;
        }
        List<String> normalizedCodes = moduleCodes.stream()
                .map(this::normalizeModule)
                .distinct()
                .toList();
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM tenant_school.school_module_entitlements
                            WHERE school_id = :schoolId
                              AND module_code IN (:moduleCodes)
                              AND enabled = true
                        )
                        """)
                .param("schoolId", schoolId)
                .param("moduleCodes", normalizedCodes)
                .query(Boolean.class)
                .single());
    }

    private String normalizeModule(String moduleCode) {
        if (moduleCode == null || moduleCode.isBlank()) {
            throw new IllegalArgumentException("moduleCode is required");
        }
        String code = moduleCode.trim().toUpperCase();
        return switch (code) {
            case "STUDENTS", "ATTENDANCE", "FEES", "INVOICES", "PAYMENTS", "ORDERS", "FIREFIGHTING", "REPORTS" -> code;
            default -> throw new IllegalArgumentException("Unknown module code: " + code);
        };
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public record ModuleEntitlementRow(
            Long id,
            Long schoolId,
            String moduleCode,
            Boolean enabled,
            String plan,
            LocalDate startDate,
            LocalDate endDate,
            String notes,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            Long createdBy) {}
}
