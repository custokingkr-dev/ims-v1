package com.custoking.ims.platformservice.persistence;

import com.custoking.ims.platformservice.security.ProjectorRls;
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
    public void upsertSchool(long id, String name, String shortCode, String city, String state, boolean active,
                             Integer academicYearStartMonth, Integer financialYearStartMonth) {
        jdbc.sql("""
                        INSERT INTO reporting.dim_school (
                            id, name, short_code, city, state, active,
                            academic_year_start_month, financial_year_start_month, updated_at
                        ) VALUES (
                            :id, :name, :shortCode, :city, :state, :active,
                            :academicYearStartMonth, :financialYearStartMonth, now()
                        )
                        ON CONFLICT (id) DO UPDATE SET
                            name = EXCLUDED.name,
                            short_code = EXCLUDED.short_code,
                            city = EXCLUDED.city,
                            state = EXCLUDED.state,
                            active = EXCLUDED.active,
                            academic_year_start_month = EXCLUDED.academic_year_start_month,
                            financial_year_start_month = EXCLUDED.financial_year_start_month,
                            updated_at = now()
                        """)
                .param("id", id)
                .param("name", name)
                .param("shortCode", shortCode)
                .param("city", city)
                .param("state", state)
                .param("active", active)
                .param("academicYearStartMonth", normalizeMonth(academicYearStartMonth))
                .param("financialYearStartMonth", normalizeMonth(financialYearStartMonth))
                .update();
    }

    private static int normalizeMonth(Integer month) {
        return month != null && month >= 1 && month <= 12 ? month : 4;
    }

    @Transactional
    public void upsertSection(String id, String name, Long schoolId, String classId, String className,
                               boolean active, String teacherName) {
        ProjectorRls.allow(jdbc);
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
    public void upsertStudent(long id, Long schoolId, String admissionNo, String fullName, String rollNo,
                               String classId, String sectionId, String parentContact, String phone,
                               boolean active, java.math.BigDecimal attendancePercent, String fatherName) {
        ProjectorRls.allow(jdbc);
        jdbc.sql("""
                        INSERT INTO reporting.dim_student (
                            id, school_id, admission_no, full_name, roll_no, class_id, section_id,
                            parent_contact, phone, active, attendance_percent, father_name, updated_at
                        ) VALUES (
                            :id, :schoolId, :admissionNo, :fullName, :rollNo, :classId, :sectionId,
                            :parentContact, :phone, :active, :attendancePercent, :fatherName, now()
                        )
                        ON CONFLICT (id) DO UPDATE SET
                            school_id = EXCLUDED.school_id,
                            admission_no = EXCLUDED.admission_no,
                            full_name = EXCLUDED.full_name,
                            roll_no = EXCLUDED.roll_no,
                            class_id = EXCLUDED.class_id,
                            section_id = EXCLUDED.section_id,
                            parent_contact = EXCLUDED.parent_contact,
                            phone = EXCLUDED.phone,
                            active = EXCLUDED.active,
                            attendance_percent = EXCLUDED.attendance_percent,
                            father_name = EXCLUDED.father_name,
                            updated_at = now()
                        """)
                .param("id", id)
                .param("schoolId", schoolId)
                .param("admissionNo", admissionNo)
                .param("fullName", fullName)
                .param("rollNo", rollNo)
                .param("classId", classId)
                .param("sectionId", sectionId)
                .param("parentContact", parentContact)
                .param("phone", phone)
                .param("active", active)
                .param("attendancePercent", attendancePercent)
                .param("fatherName", fatherName)
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
