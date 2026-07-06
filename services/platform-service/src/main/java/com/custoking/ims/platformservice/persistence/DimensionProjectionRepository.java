package com.custoking.ims.platformservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reference dimension read models projected from tenant_school (school-core) outbox events
 * ({@code school.upserted.v1}, {@code school-section.upserted.v1},
 * {@code academic-year.upserted.v1}), per Reporting Decoupling SP1. Rows are upserted
 * idempotently by {@code id} so replaying the same or a later event never duplicates state;
 * last-writer-wins is acceptable for reference data.
 */
@Repository
public class DimensionProjectionRepository {

    private final JdbcClient jdbc;

    public DimensionProjectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void upsertSchool(long id, String name, String shortCode, String city, String state, boolean active) {
        jdbc.sql("""
                        INSERT INTO reporting.dim_school (
                            id, name, short_code, city, state, active, updated_at
                        ) VALUES (
                            :id, :name, :shortCode, :city, :state, :active, now()
                        )
                        ON CONFLICT (id) DO UPDATE SET
                            name = EXCLUDED.name,
                            short_code = EXCLUDED.short_code,
                            city = EXCLUDED.city,
                            state = EXCLUDED.state,
                            active = EXCLUDED.active,
                            updated_at = now()
                        """)
                .param("id", id)
                .param("name", name)
                .param("shortCode", shortCode)
                .param("city", city)
                .param("state", state)
                .param("active", active)
                .update();
    }

    @Transactional
    public void upsertSection(String id, String name, Long schoolId, String classId, String className,
                               boolean active, String teacherName) {
        jdbc.sql("""
                        INSERT INTO reporting.dim_section (
                            id, name, school_id, class_id, class_name, active, teacher_name, updated_at
                        ) VALUES (
                            :id, :name, :schoolId, :classId, :className, :active, :teacherName, now()
                        )
                        ON CONFLICT (id) DO UPDATE SET
                            name = EXCLUDED.name,
                            school_id = EXCLUDED.school_id,
                            class_id = EXCLUDED.class_id,
                            class_name = EXCLUDED.class_name,
                            active = EXCLUDED.active,
                            teacher_name = EXCLUDED.teacher_name,
                            updated_at = now()
                        """)
                .param("id", id)
                .param("name", name)
                .param("schoolId", schoolId)
                .param("classId", classId)
                .param("className", className)
                .param("active", active)
                .param("teacherName", teacherName)
                .update();
    }

    @Transactional
    public void upsertAcademicYear(String id, String label, boolean active) {
        jdbc.sql("""
                        INSERT INTO reporting.dim_academic_year (
                            id, label, active, updated_at
                        ) VALUES (
                            :id, :label, :active, now()
                        )
                        ON CONFLICT (id) DO UPDATE SET
                            label = EXCLUDED.label,
                            active = EXCLUDED.active,
                            updated_at = now()
                        """)
                .param("id", id)
                .param("label", label)
                .param("active", active)
                .update();
    }
}
