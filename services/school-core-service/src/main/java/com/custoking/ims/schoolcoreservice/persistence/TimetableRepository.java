package com.custoking.ims.schoolcoreservice.persistence;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class TimetableRepository {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final JdbcClient jdbc;

    public TimetableRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> bellSchedules(Long schoolId) {
        List<Map<String, Object>> schedules = jdbc.sql("""
                SELECT id, name
                FROM tenant_school.school_bell_schedules
                WHERE school_id = :s
                ORDER BY name
                """)
                .param("s", schoolId)
                .query((rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("name", rs.getString("name"));
                    return m;
                })
                .list();
        for (Map<String, Object> schedule : schedules) {
            long scheduleId = ((Number) schedule.get("id")).longValue();
            schedule.put("periods", periods(scheduleId));
        }
        return schedules;
    }

    private List<Map<String, Object>> periods(long scheduleId) {
        return jdbc.sql("""
                SELECT id, sort_order, label, start_time, end_time, is_break
                FROM tenant_school.school_bell_periods
                WHERE schedule_id = :sid
                ORDER BY sort_order
                """)
                .param("sid", scheduleId)
                .query((rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("label", rs.getString("label"));
                    m.put("start", rs.getObject("start_time", LocalTime.class).format(TIME_FMT));
                    m.put("end", rs.getObject("end_time", LocalTime.class).format(TIME_FMT));
                    m.put("isBreak", rs.getBoolean("is_break"));
                    m.put("sortOrder", rs.getInt("sort_order"));
                    return m;
                })
                .list();
    }

    @Transactional
    public Map<String, Object> createSchedule(Long schoolId, String name) {
        try {
            return jdbc.sql("""
                    INSERT INTO tenant_school.school_bell_schedules (school_id, name)
                    VALUES (:s, :n)
                    RETURNING id, name
                    """)
                    .param("s", schoolId)
                    .param("n", name)
                    .query((rs, rowNum) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getLong("id"));
                        m.put("name", rs.getString("name"));
                        return m;
                    })
                    .single();
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException("A schedule named '" + name + "' already exists");
        }
    }

    @Transactional
    public void renameSchedule(Long schoolId, long id, String name) {
        jdbc.sql("""
                UPDATE tenant_school.school_bell_schedules
                SET name = :n
                WHERE school_id = :s AND id = :id
                """)
                .param("n", name)
                .param("s", schoolId)
                .param("id", id)
                .update();
    }

    @Transactional
    public void deleteSchedule(Long schoolId, long id) {
        // Deleting a schedule cascades to its periods and, in turn, to every timetable entry that
        // references those periods (ON DELETE CASCADE). Refuse when any past-year (archived)
        // timetable depends on it — those years are read-only everywhere else in this module.
        boolean usedByArchive = jdbc.sql("""
                SELECT 1
                FROM tenant_school.school_timetable_entries e
                JOIN tenant_school.school_bell_periods p ON p.id = e.bell_period_id
                WHERE e.school_id = :s AND p.schedule_id = :id
                  AND e.academic_year_id <> :activeYear
                LIMIT 1
                """)
                .param("s", schoolId)
                .param("id", id)
                .param("activeYear", activeYearOrBlank(schoolId))
                .query(Integer.class)
                .optional()
                .isPresent();
        if (usedByArchive) {
            throw new YearLockedException("This schedule is used by a past-year timetable, which is read-only. "
                    + "Create a new schedule for the current year instead of deleting it.");
        }
        jdbc.sql("""
                DELETE FROM tenant_school.school_bell_schedules
                WHERE school_id = :s AND id = :id
                """)
                .param("s", schoolId)
                .param("id", id)
                .update();
    }

    @Transactional
    public Map<String, Object> addPeriod(Long schoolId, long scheduleId, String label, String start, String end,
                                          boolean isBreak, int sortOrder) {
        requireScheduleInSchool(schoolId, scheduleId);
        try {
            LocalTime startTime = LocalTime.parse(start, TIME_FMT);
            LocalTime endTime = LocalTime.parse(end, TIME_FMT);
            return jdbc.sql("""
                    INSERT INTO tenant_school.school_bell_periods
                        (school_id, schedule_id, sort_order, label, start_time, end_time, is_break)
                    VALUES (:s, :schedId, :sortOrder, :label, :start, :end, :isBreak)
                    RETURNING id, label, start_time, end_time, is_break, sort_order
                    """)
                    .param("s", schoolId)
                    .param("schedId", scheduleId)
                    .param("sortOrder", sortOrder)
                    .param("label", label)
                    .param("start", startTime)
                    .param("end", endTime)
                    .param("isBreak", isBreak)
                    .query((rs, rowNum) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getLong("id"));
                        m.put("label", rs.getString("label"));
                        m.put("start", rs.getObject("start_time", LocalTime.class).format(TIME_FMT));
                        m.put("end", rs.getObject("end_time", LocalTime.class).format(TIME_FMT));
                        m.put("isBreak", rs.getBoolean("is_break"));
                        m.put("sortOrder", rs.getInt("sort_order"));
                        return m;
                    })
                    .single();
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException("A period with sort order " + sortOrder + " already exists");
        }
    }

    @Transactional
    public void updatePeriod(Long schoolId, long periodId, String label, String start, String end,
                              boolean isBreak, int sortOrder) {
        try {
            LocalTime startTime = LocalTime.parse(start, TIME_FMT);
            LocalTime endTime = LocalTime.parse(end, TIME_FMT);
            jdbc.sql("""
                    UPDATE tenant_school.school_bell_periods
                    SET label = :label, start_time = :start, end_time = :end,
                        is_break = :isBreak, sort_order = :sortOrder
                    WHERE school_id = :s AND id = :id
                    """)
                    .param("label", label)
                    .param("start", startTime)
                    .param("end", endTime)
                    .param("isBreak", isBreak)
                    .param("sortOrder", sortOrder)
                    .param("s", schoolId)
                    .param("id", periodId)
                    .update();
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException("A period with sort order " + sortOrder + " already exists");
        }
    }

    @Transactional
    public void swapPeriodOrder(Long schoolId, long scheduleId, long idA, long idB) {
        Integer orderA = jdbc.sql("""
                SELECT sort_order FROM tenant_school.school_bell_periods
                WHERE id = :id AND school_id = :s AND schedule_id = :sc
                """)
                .param("id", idA)
                .param("s", schoolId)
                .param("sc", scheduleId)
                .query(Integer.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Period not found"));
        Integer orderB = jdbc.sql("""
                SELECT sort_order FROM tenant_school.school_bell_periods
                WHERE id = :id AND school_id = :s AND schedule_id = :sc
                """)
                .param("id", idB)
                .param("s", schoolId)
                .param("sc", scheduleId)
                .query(Integer.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Period not found"));

        jdbc.sql("""
                UPDATE tenant_school.school_bell_periods
                SET sort_order = :neg
                WHERE id = :idA AND school_id = :s
                """)
                .param("neg", -idA)
                .param("idA", idA)
                .param("s", schoolId)
                .update();
        jdbc.sql("""
                UPDATE tenant_school.school_bell_periods
                SET sort_order = :orderA
                WHERE id = :idB AND school_id = :s
                """)
                .param("orderA", orderA)
                .param("idB", idB)
                .param("s", schoolId)
                .update();
        jdbc.sql("""
                UPDATE tenant_school.school_bell_periods
                SET sort_order = :orderB
                WHERE id = :idA AND school_id = :s
                """)
                .param("orderB", orderB)
                .param("idA", idA)
                .param("s", schoolId)
                .update();
    }

    @Transactional
    public void deletePeriod(Long schoolId, long periodId) {
        // A period FK-cascades to timetable entries across ALL years. Refuse when a past-year
        // (archived, read-only) timetable references it; current-year entries cascade as intended.
        boolean usedByArchive = jdbc.sql("""
                SELECT 1 FROM tenant_school.school_timetable_entries
                WHERE school_id = :s AND bell_period_id = :id
                  AND academic_year_id <> :activeYear
                LIMIT 1
                """)
                .param("s", schoolId)
                .param("id", periodId)
                .param("activeYear", activeYearOrBlank(schoolId))
                .query(Integer.class)
                .optional()
                .isPresent();
        if (usedByArchive) {
            throw new YearLockedException("This period is used by a past-year timetable, which is read-only. "
                    + "Create a new schedule for the current year instead of deleting it.");
        }
        jdbc.sql("""
                DELETE FROM tenant_school.school_bell_periods
                WHERE school_id = :s AND id = :id
                """)
                .param("s", schoolId)
                .param("id", periodId)
                .update();
    }

    // Active academic year id, or "" when none is active. The blank sentinel makes the guard
    // conservative: `academic_year_id <> ''` matches every real year, so if no year is active
    // any timetable-referenced period/schedule is treated as archived and protected.
    private String activeYearOrBlank(Long schoolId) {
        String activeYear = activeYearId(schoolId);
        return activeYear == null ? "" : activeYear;
    }

    public List<Map<String, Object>> classSchedules(Long schoolId) {
        return jdbc.sql("""
                SELECT DISTINCT sc.id AS class_id, sc.name AS class_name, m.schedule_id, sc.sort_order
                FROM tenant_school.school_sections ss
                JOIN tenant_school.school_classes sc ON sc.id = ss.school_class_id
                LEFT JOIN tenant_school.school_class_bell_map m
                    ON m.school_id = ss.school_id AND m.class_id = sc.id
                WHERE ss.school_id = :s
                ORDER BY sc.sort_order
                """)
                .param("s", schoolId)
                .query((rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("classId", rs.getString("class_id"));
                    m.put("className", rs.getString("class_name"));
                    long scheduleId = rs.getLong("schedule_id");
                    m.put("scheduleId", rs.wasNull() ? null : scheduleId);
                    return m;
                })
                .list();
    }

    @Transactional
    public void setClassSchedule(Long schoolId, String classId, long scheduleId) {
        requireScheduleInSchool(schoolId, scheduleId);
        jdbc.sql("""
                INSERT INTO tenant_school.school_class_bell_map (school_id, class_id, schedule_id)
                VALUES (:s, :c, :sid)
                ON CONFLICT (school_id, class_id) DO UPDATE SET schedule_id = EXCLUDED.schedule_id
                """)
                .param("s", schoolId)
                .param("c", classId)
                .param("sid", scheduleId)
                .update();
    }

    @Transactional
    public void deleteClassSchedule(Long schoolId, String classId) {
        jdbc.sql("""
                DELETE FROM tenant_school.school_class_bell_map
                WHERE school_id = :s AND class_id = :c
                """)
                .param("s", schoolId)
                .param("c", classId)
                .update();
    }

    private void requireScheduleInSchool(Long schoolId, long scheduleId) {
        boolean exists = jdbc.sql("""
                SELECT 1 FROM tenant_school.school_bell_schedules
                WHERE id = :scheduleId AND school_id = :schoolId
                """)
                .param("scheduleId", scheduleId)
                .param("schoolId", schoolId)
                .query(Integer.class)
                .optional()
                .isPresent();
        if (!exists) {
            throw new IllegalArgumentException("Schedule not found");
        }
    }

    public String activeYearId(Long schoolId) {
        return jdbc.sql("""
                SELECT id FROM tenant_school.academic_years
                ORDER BY active DESC, id DESC LIMIT 1
                """)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    public Map<String, Object> classSubjects(Long schoolId, String classId, String yearId) {
        List<Map<String, Object>> subjects = jdbc.sql("""
                SELECT id, subject_name, sort_order
                FROM tenant_school.school_class_subjects
                WHERE school_id = :s AND class_id = :c AND academic_year_id = :y
                ORDER BY sort_order, subject_name
                """)
                .param("s", schoolId)
                .param("c", classId)
                .param("y", yearId)
                .query((rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("subjectName", rs.getString("subject_name"));
                    m.put("sortOrder", rs.getInt("sort_order"));
                    return m;
                })
                .list();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("editable", yearId != null && yearId.equals(activeYearId(schoolId)));
        result.put("yearId", yearId);
        result.put("subjects", subjects);
        return result;
    }

    @Transactional
    public Map<String, Object> addSubject(Long schoolId, String classId, String yearId, String subjectName) {
        if (yearId == null || !yearId.equals(activeYearId(schoolId))) {
            throw new YearLockedException("Subjects for " + yearId + " are locked — the year has ended");
        }
        try {
            return jdbc.sql("""
                    INSERT INTO tenant_school.school_class_subjects
                        (school_id, class_id, academic_year_id, subject_name)
                    VALUES (:s, :c, :y, :n)
                    RETURNING id, subject_name, sort_order
                    """)
                    .param("s", schoolId)
                    .param("c", classId)
                    .param("y", yearId)
                    .param("n", subjectName)
                    .query((rs, rowNum) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getLong("id"));
                        m.put("subjectName", rs.getString("subject_name"));
                        m.put("sortOrder", rs.getInt("sort_order"));
                        return m;
                    })
                    .single();
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException("'" + subjectName + "' already exists for this class/year");
        }
    }

    private static final List<String> DAYS = List.of("Mon", "Tue", "Wed", "Thu", "Fri", "Sat");

    public Map<String, Object> timetable(Long schoolId, String sectionId, String yearId) {
        String classId = jdbc.sql("""
                SELECT school_class_id FROM tenant_school.school_sections
                WHERE id = :sec AND school_id = :s
                """)
                .param("sec", sectionId)
                .param("s", schoolId)
                .query(String.class)
                .optional()
                .orElse(null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("editable", yearId != null && yearId.equals(activeYearId(schoolId)));
        result.put("yearId", yearId);
        result.put("sectionId", sectionId);
        result.put("days", DAYS);

        Long scheduleId = classId == null ? null : jdbc.sql("""
                SELECT schedule_id FROM tenant_school.school_class_bell_map
                WHERE school_id = :s AND class_id = :c
                """)
                .param("s", schoolId)
                .param("c", classId)
                .query(Long.class)
                .optional()
                .orElse(null);

        if (scheduleId == null) {
            result.put("periods", List.of());
            result.put("entries", List.of());
            result.put("noSchedule", true);
            return result;
        }

        result.put("periods", periods(scheduleId));
        result.put("entries", jdbc.sql("""
                SELECT e.day_name, e.bell_period_id, e.subject_name, e.teacher_id, st.name AS teacher_name
                FROM tenant_school.school_timetable_entries e
                LEFT JOIN tenant_school.staff_members st ON st.id = e.teacher_id
                WHERE e.school_id = :s AND e.academic_year_id = :y AND e.section_id = :sec
                """)
                .param("s", schoolId)
                .param("y", yearId)
                .param("sec", sectionId)
                .query((rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("day", rs.getString("day_name"));
                    m.put("periodId", rs.getLong("bell_period_id"));
                    m.put("subjectName", rs.getString("subject_name"));
                    long teacherId = rs.getLong("teacher_id");
                    m.put("teacherId", rs.wasNull() ? null : teacherId);
                    m.put("teacherName", rs.getString("teacher_name"));
                    return m;
                })
                .list());
        return result;
    }

    @Transactional
    public Map<String, Object> upsertEntry(Long schoolId, String sectionId, String day, long periodId,
                                            String subjectName, Long teacherId) {
        String year = activeYearId(schoolId);
        if (year == null) {
            throw new YearLockedException("No active academic year configured");
        }

        String classId = jdbc.sql("""
                SELECT school_class_id FROM tenant_school.school_sections
                WHERE id = :sec AND school_id = :s
                """)
                .param("sec", sectionId)
                .param("s", schoolId)
                .query(String.class)
                .optional()
                .orElse(null);
        if (classId == null) {
            throw new IllegalArgumentException("Section not found");
        }

        Long classScheduleId = jdbc.sql("""
                SELECT schedule_id FROM tenant_school.school_class_bell_map
                WHERE school_id = :s AND class_id = :c
                """)
                .param("s", schoolId)
                .param("c", classId)
                .query(Long.class)
                .optional()
                .orElse(null);

        Map<String, Object> period = jdbc.sql("""
                SELECT is_break, schedule_id, label FROM tenant_school.school_bell_periods
                WHERE id = :pid AND school_id = :s
                """)
                .param("pid", periodId)
                .param("s", schoolId)
                .query((rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("isBreak", rs.getBoolean("is_break"));
                    m.put("scheduleId", rs.getLong("schedule_id"));
                    m.put("label", rs.getString("label"));
                    return m;
                })
                .optional()
                .orElse(null);
        if (period == null
                || (Boolean) period.get("isBreak")
                || classScheduleId == null
                || !classScheduleId.equals(period.get("scheduleId"))) {
            throw new IllegalArgumentException("Period is a break or not part of this class's schedule");
        }

        boolean subjectExists = jdbc.sql("""
                SELECT 1 FROM tenant_school.school_class_subjects
                WHERE school_id = :s AND class_id = :c AND academic_year_id = :y AND subject_name = :n
                """)
                .param("s", schoolId)
                .param("c", classId)
                .param("y", year)
                .param("n", subjectName)
                .query(Integer.class)
                .optional()
                .isPresent();
        if (!subjectExists) {
            throw new IllegalArgumentException("'" + subjectName + "' is not in this class's subject list for " + year);
        }

        jdbc.sql("""
                INSERT INTO tenant_school.school_timetable_entries
                    (school_id, academic_year_id, section_id, day_name, bell_period_id, subject_name, teacher_id, updated_at)
                VALUES (:s, :y, :sec, :day, :pid, :subj, :teacher, now())
                ON CONFLICT (school_id, academic_year_id, section_id, day_name, bell_period_id)
                DO UPDATE SET subject_name = EXCLUDED.subject_name, teacher_id = EXCLUDED.teacher_id, updated_at = now()
                """)
                .param("s", schoolId)
                .param("y", year)
                .param("sec", sectionId)
                .param("day", day)
                .param("pid", periodId)
                .param("subj", subjectName)
                .param("teacher", teacherId)
                .update();

        String conflict = null;
        if (teacherId != null) {
            conflict = jdbc.sql("""
                    SELECT st.name || ' already teaches ' || sec2.name || ' · ' || :day || ' ' || :label
                    FROM tenant_school.school_timetable_entries e2
                    JOIN tenant_school.school_sections sec2 ON sec2.id = e2.section_id
                    LEFT JOIN tenant_school.staff_members st ON st.id = e2.teacher_id
                    WHERE e2.school_id = :s AND e2.academic_year_id = :y AND e2.day_name = :day
                      AND e2.bell_period_id = :pid AND e2.teacher_id = :teacher AND e2.section_id <> :sec
                    LIMIT 1
                    """)
                    .param("day", day)
                    .param("label", period.get("label"))
                    .param("s", schoolId)
                    .param("y", year)
                    .param("pid", periodId)
                    .param("teacher", teacherId)
                    .param("sec", sectionId)
                    .query(String.class)
                    .optional()
                    .orElse(null);
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("day", day);
        entry.put("periodId", periodId);
        entry.put("subjectName", subjectName);
        entry.put("teacherId", teacherId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entry", entry);
        result.put("conflict", conflict);
        return result;
    }

    // Bulk upsert: loops the same per-row validation/write as upsertEntry (break-period check,
    // subject-master check, year-locked check) so "same every day" / "copy day -> all" fire one
    // call instead of N single upserts. One invalid/locked row aborts the whole batch — the
    // surrounding @Transactional rolls back everything written so far in this call.
    @Transactional
    public Map<String, Object> upsertEntries(Long schoolId, String sectionId, List<Map<String, Object>> entries) {
        // Collect any teacher double-booking warnings from the per-row upserts (deduped, order-kept)
        // so the returned grid surfaces them — the bulk callers ("same every day" / "copy day") would
        // otherwise lose the conflict warning that single-cell edits show.
        java.util.LinkedHashSet<String> conflicts = new java.util.LinkedHashSet<>();
        for (Map<String, Object> row : entries) {
            String day = String.valueOf(row.get("day"));
            long periodId = numberValue(row.get("periodId"), "periodId is required");
            String subjectName = String.valueOf(row.get("subjectName"));
            Object teacherRaw = row.get("teacherId");
            Long teacherId = teacherRaw == null ? null : numberValue(teacherRaw, "teacherId is invalid");
            Map<String, Object> rowResult = upsertEntry(schoolId, sectionId, day, periodId, subjectName, teacherId);
            Object conflict = rowResult.get("conflict");
            if (conflict != null) {
                conflicts.add(String.valueOf(conflict));
            }
        }
        String year = activeYearId(schoolId);
        Map<String, Object> grid = timetable(schoolId, sectionId, year);
        if (!conflicts.isEmpty()) {
            grid.put("conflict", String.join("; ", conflicts));
        }
        return grid;
    }

    private long numberValue(Object value, String errorMessage) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                // fall through to IllegalArgumentException below
            }
        }
        throw new IllegalArgumentException(errorMessage);
    }

    @Transactional
    public void deleteEntry(Long schoolId, String sectionId, String day, long periodId) {
        String year = activeYearId(schoolId);
        if (year == null) {
            throw new YearLockedException("No active academic year configured");
        }
        jdbc.sql("""
                DELETE FROM tenant_school.school_timetable_entries
                WHERE school_id = :s AND academic_year_id = :y AND section_id = :sec
                  AND day_name = :day AND bell_period_id = :pid
                """)
                .param("s", schoolId)
                .param("y", year)
                .param("sec", sectionId)
                .param("day", day)
                .param("pid", periodId)
                .update();
    }

    @Transactional
    public void deleteSubject(Long schoolId, long subjectId) {
        String subjectYearId = jdbc.sql("""
                SELECT academic_year_id FROM tenant_school.school_class_subjects
                WHERE school_id = :s AND id = :id
                """)
                .param("s", schoolId)
                .param("id", subjectId)
                .query(String.class)
                .optional()
                .orElse(null);
        if (subjectYearId == null) {
            return;
        }
        if (!subjectYearId.equals(activeYearId(schoolId))) {
            throw new YearLockedException("Subjects for " + subjectYearId + " are locked — the year has ended");
        }
        jdbc.sql("""
                DELETE FROM tenant_school.school_class_subjects
                WHERE school_id = :s AND id = :id
                """)
                .param("s", schoolId)
                .param("id", subjectId)
                .update();
    }
}
