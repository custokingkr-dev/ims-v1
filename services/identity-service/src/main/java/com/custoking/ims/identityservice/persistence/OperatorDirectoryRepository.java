package com.custoking.ims.identityservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Read-only lookups over active operator assignments: who covers a school, and a school plus catalogue item. */
@Repository
public class OperatorDirectoryRepository {
    /** An operator is a non-deleted user holding an active, in-window OPERATIONS assignment with a school. */
    private static final String ACTIVE_OPERATOR_ASSIGNMENTS = """
            FROM identity.user_role_assignments ura
            JOIN identity.roles r ON r.id = ura.role_id AND UPPER(r.name) = 'OPERATIONS'
            JOIN identity.app_users u ON u.id = ura.user_id AND u.deleted_at IS NULL
            WHERE ura.active = true
              AND ura.revoked_at IS NULL
              AND ura.school_id IS NOT NULL
              AND (ura.valid_from IS NULL OR ura.valid_from <= now())
              AND (ura.valid_until IS NULL OR ura.valid_until >= now())
            """;

    private final JdbcClient jdbc;

    public OperatorDirectoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record OperatorRow(long assignmentId, long userId, String fullName, String email, long schoolId, String catalogCategory) { }

    /** Operators scoped to exactly this catalogue item at this school. */
    public List<Long> itemSpecific(long schoolId, String category) {
        return jdbc.sql("SELECT DISTINCT ura.user_id " + ACTIVE_OPERATOR_ASSIGNMENTS
                        + " AND ura.school_id = :school AND ura.catalog_category = :category ORDER BY ura.user_id")
                .param("school", schoolId).param("category", category).query(Long.class).list();
    }

    /** Operators covering the whole school, whatever the catalogue item. */
    public List<Long> schoolWide(long schoolId) {
        return jdbc.sql("SELECT DISTINCT ura.user_id " + ACTIVE_OPERATOR_ASSIGNMENTS
                        + " AND ura.school_id = :school AND ura.catalog_category IS NULL ORDER BY ura.user_id")
                .param("school", schoolId).query(Long.class).list();
    }

    /** True when the user is school-wide for the school or scoped to this very catalogue item there. */
    public boolean eligible(long userId, long schoolId, String category) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 " + ACTIVE_OPERATOR_ASSIGNMENTS
                        + " AND ura.user_id = :user AND ura.school_id = :school"
                        + " AND (ura.catalog_category IS NULL OR ura.catalog_category = :category))")
                .param("user", userId).param("school", schoolId).param("category", category)
                .query(Boolean.class).single());
    }

    /** Active operator assignments for the superadmin screen, optionally narrowed by school and catalogue item. */
    public List<OperatorRow> list(Long schoolId, String category) {
        StringBuilder sql = new StringBuilder("SELECT ura.id AS assignment_id, ura.user_id, u.full_name, u.email, ura.school_id, ura.catalog_category ")
                .append(ACTIVE_OPERATOR_ASSIGNMENTS);
        if (schoolId != null) sql.append(" AND ura.school_id = :school");
        if (category != null) sql.append(" AND ura.catalog_category = :category");
        sql.append(" ORDER BY ura.school_id, ura.catalog_category NULLS FIRST, u.full_name, ura.id");
        var spec = jdbc.sql(sql.toString());
        if (schoolId != null) spec = spec.param("school", schoolId);
        if (category != null) spec = spec.param("category", category);
        return spec.query((rs, n) -> new OperatorRow(rs.getLong("assignment_id"), rs.getLong("user_id"), rs.getString("full_name"),
                rs.getString("email"), rs.getLong("school_id"), rs.getString("catalog_category"))).list();
    }
}
