package com.custoking.ims.schoolcoreservice.persistence;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.OffsetDateTime;
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
        return schoolId == null
                ? AcademicCalendar.activeOrCurrentAcademicYearId(jdbc)
                : AcademicCalendar.currentAcademicYearId(jdbc, schoolId);
    }

    public Map<String, Object> classSubjects(Long schoolId, String classId, String yearId) {
        List<Map<String, Object>> subjects = jdbc.sql("""
                SELECT id, subject_name, sort_order, weekly_periods, preferred_part_of_day,
                       required_room_type, double_period
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
                    m.put("weeklyPeriods", rs.getInt("weekly_periods"));
                    m.put("preferredPartOfDay", rs.getString("preferred_part_of_day"));
                    m.put("requiredRoomType", rs.getString("required_room_type"));
                    m.put("doublePeriod", rs.getBoolean("double_period"));
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

    @Transactional
    public Map<String, Object> updateSubjectPolicy(
            Long schoolId,
            long subjectId,
            int weeklyPeriods,
            String preferredPartOfDay,
            String requiredRoomType,
            boolean doublePeriod) {
        if (weeklyPeriods < 0) {
            throw new IllegalArgumentException("Weekly periods cannot be negative");
        }
        String preference = preferredPartOfDay == null || preferredPartOfDay.isBlank()
                ? "ANY"
                : preferredPartOfDay.trim().toUpperCase(java.util.Locale.ENGLISH);
        if (!List.of("ANY", "MORNING", "AFTERNOON").contains(preference)) {
            throw new IllegalArgumentException("Preferred part of day must be ANY, MORNING, or AFTERNOON");
        }
        String normalizedRoomType = requiredRoomType == null || requiredRoomType.isBlank()
                ? null
                : requiredRoomType.trim().toUpperCase(java.util.Locale.ENGLISH);
        String yearId = jdbc.sql("""
                SELECT academic_year_id
                FROM tenant_school.school_class_subjects
                WHERE id = :id AND school_id = :schoolId
                """)
                .param("id", subjectId)
                .param("schoolId", schoolId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Subject not found"));
        if (!yearId.equals(activeYearId(schoolId))) {
            throw new YearLockedException("Subjects for " + yearId + " are locked");
        }
        return jdbc.sql("""
                UPDATE tenant_school.school_class_subjects
                SET weekly_periods = :weeklyPeriods, preferred_part_of_day = :preference,
                    required_room_type = :roomType, double_period = :doublePeriod
                WHERE id = :id AND school_id = :schoolId
                RETURNING id, subject_name, sort_order, weekly_periods, preferred_part_of_day,
                          required_room_type, double_period
                """)
                .param("weeklyPeriods", weeklyPeriods)
                .param("preference", preference)
                .param("roomType", normalizedRoomType)
                .param("doublePeriod", doublePeriod)
                .param("id", subjectId)
                .param("schoolId", schoolId)
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("subjectName", rs.getString("subject_name"));
                    row.put("sortOrder", rs.getInt("sort_order"));
                    row.put("weeklyPeriods", rs.getInt("weekly_periods"));
                    row.put("preferredPartOfDay", rs.getString("preferred_part_of_day"));
                    row.put("requiredRoomType", rs.getString("required_room_type"));
                    row.put("doublePeriod", rs.getBoolean("double_period"));
                    return row;
                })
                .single();
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
        result.put("classId", classId);
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
        result.put("scheduleId", scheduleId);
        result.put("entries", jdbc.sql("""
                SELECT e.day_name, e.bell_period_id, e.subject_name, e.teacher_id, st.name AS teacher_name,
                       e.room_id, room.name AS room_name, room.room_type
                FROM tenant_school.school_timetable_entries e
                LEFT JOIN tenant_school.staff_members st ON st.id = e.teacher_id
                LEFT JOIN tenant_school.school_rooms room ON room.id = e.room_id
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
                    long roomId = rs.getLong("room_id");
                    m.put("roomId", rs.wasNull() ? null : roomId);
                    m.put("roomName", rs.getString("room_name"));
                    m.put("roomType", rs.getString("room_type"));
                    return m;
                })
                .list());
        result.put("publication", currentPublication(schoolId, yearId));
        return result;
    }

    @Transactional
    public Map<String, Object> upsertEntry(Long schoolId, String sectionId, String day, long periodId,
                                             String subjectName, Long teacherId) {
        return upsertEntry(schoolId, sectionId, day, periodId, subjectName, teacherId, null);
    }

    @Transactional
    public Map<String, Object> upsertEntry(Long schoolId, String sectionId, String day, long periodId,
                                             String subjectName, Long teacherId, Long roomId) {
        String year = activeYearId(schoolId);
        if (year == null) {
            throw new YearLockedException("No active academic year configured");
        }
        requireDay(day);

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

        if (teacherId != null) {
            requireTeacherInSchool(schoolId, teacherId);
        }
        if (roomId != null) {
            requireRoomInSchool(schoolId, roomId);
        }

        jdbc.sql("""
                INSERT INTO tenant_school.school_timetable_entries
                    (school_id, academic_year_id, section_id, day_name, bell_period_id, subject_name,
                     teacher_id, room_id, updated_at)
                VALUES (:s, :y, :sec, :day, :pid, :subj, :teacher, :roomId, now())
                ON CONFLICT (school_id, academic_year_id, section_id, day_name, bell_period_id)
                DO UPDATE SET subject_name = EXCLUDED.subject_name, teacher_id = EXCLUDED.teacher_id,
                              room_id = EXCLUDED.room_id, updated_at = now()
                """)
                .param("s", schoolId)
                .param("y", year)
                .param("sec", sectionId)
                .param("day", day)
                .param("pid", periodId)
                .param("subj", subjectName)
                .param("teacher", teacherId)
                .param("roomId", roomId)
                .update();

        String conflict = teacherId == null
                ? null
                : teacherConflict(schoolId, year, sectionId, day, periodId, teacherId);

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("day", day);
        entry.put("periodId", periodId);
        entry.put("subjectName", subjectName);
        entry.put("teacherId", teacherId);
        entry.put("roomId", roomId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entry", entry);
        String roomConflict = roomId == null ? null : roomConflict(schoolId, year, sectionId, day, periodId, roomId);
        result.put("conflict", conflict != null ? conflict : roomConflict);
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
            Object roomRaw = row.get("roomId");
            Long roomId = roomRaw == null ? null : numberValue(roomRaw, "roomId is invalid");
            Map<String, Object> rowResult = upsertEntry(
                    schoolId, sectionId, day, periodId, subjectName, teacherId, roomId);
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

    public List<Map<String, Object>> rooms(Long schoolId) {
        return jdbc.sql("""
                SELECT id, name, room_type, capacity, active
                FROM tenant_school.school_rooms
                WHERE school_id = :schoolId
                ORDER BY active DESC, room_type, name
                """)
                .param("schoolId", schoolId)
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("name", rs.getString("name"));
                    row.put("roomType", rs.getString("room_type"));
                    row.put("capacity", rs.getInt("capacity"));
                    row.put("active", rs.getBoolean("active"));
                    return row;
                })
                .list();
    }

    @Transactional
    public Map<String, Object> createRoom(Long schoolId, String name, String roomType, int capacity) {
        if (capacity < 0) throw new IllegalArgumentException("Room capacity cannot be negative");
        String type = roomType == null || roomType.isBlank()
                ? "CLASSROOM"
                : roomType.trim().toUpperCase(java.util.Locale.ENGLISH);
        try {
            return jdbc.sql("""
                    INSERT INTO tenant_school.school_rooms (school_id, name, room_type, capacity)
                    VALUES (:schoolId, :name, :roomType, :capacity)
                    RETURNING id, name, room_type, capacity, active
                    """)
                    .param("schoolId", schoolId)
                    .param("name", name)
                    .param("roomType", type)
                    .param("capacity", capacity)
                    .query((rs, rowNum) -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", rs.getLong("id"));
                        row.put("name", rs.getString("name"));
                        row.put("roomType", rs.getString("room_type"));
                        row.put("capacity", rs.getInt("capacity"));
                        row.put("active", rs.getBoolean("active"));
                        return row;
                    })
                    .single();
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException("A room named '" + name + "' already exists");
        }
    }

    public List<Map<String, Object>> teacherAvailability(Long schoolId, String yearId, Long teacherId) {
        StringBuilder sql = new StringBuilder("""
                SELECT a.id, a.teacher_id, st.name AS teacher_name, a.day_name, a.bell_period_id,
                       p.label AS period_label, a.available, a.note
                FROM tenant_school.school_teacher_availability a
                JOIN tenant_school.staff_members st ON st.id = a.teacher_id
                JOIN tenant_school.school_bell_periods p ON p.id = a.bell_period_id
                WHERE a.school_id = :schoolId AND a.academic_year_id = :yearId
                """);
        if (teacherId != null) sql.append(" AND a.teacher_id = :teacherId");
        sql.append(" ORDER BY st.name, a.day_name, p.sort_order");
        var spec = jdbc.sql(sql.toString())
                .param("schoolId", schoolId)
                .param("yearId", yearId);
        if (teacherId != null) spec = spec.param("teacherId", teacherId);
        return spec.query((rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("teacherId", rs.getLong("teacher_id"));
            row.put("teacherName", rs.getString("teacher_name"));
            row.put("day", rs.getString("day_name"));
            row.put("periodId", rs.getLong("bell_period_id"));
            row.put("periodLabel", rs.getString("period_label"));
            row.put("available", rs.getBoolean("available"));
            row.put("note", rs.getString("note"));
            return row;
        }).list();
    }

    @Transactional
    public Map<String, Object> saveTeacherAvailability(
            Long schoolId,
            String yearId,
            Long teacherId,
            String day,
            long periodId,
            boolean available,
            String note) {
        if (!yearId.equals(activeYearId(schoolId))) {
            throw new YearLockedException("Teacher availability for " + yearId + " is locked");
        }
        requireDay(day);
        requireTeacherInSchool(schoolId, teacherId);
        requirePeriodInSchool(schoolId, periodId);
        return jdbc.sql("""
                INSERT INTO tenant_school.school_teacher_availability
                    (school_id, academic_year_id, teacher_id, day_name, bell_period_id, available, note)
                VALUES (:schoolId, :yearId, :teacherId, :day, :periodId, :available, :note)
                ON CONFLICT (school_id, academic_year_id, teacher_id, day_name, bell_period_id)
                DO UPDATE SET available = EXCLUDED.available, note = EXCLUDED.note, updated_at = now()
                RETURNING id, teacher_id, day_name, bell_period_id, available, note
                """)
                .param("schoolId", schoolId)
                .param("yearId", yearId)
                .param("teacherId", teacherId)
                .param("day", day)
                .param("periodId", periodId)
                .param("available", available)
                .param("note", note)
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("teacherId", rs.getLong("teacher_id"));
                    row.put("day", rs.getString("day_name"));
                    row.put("periodId", rs.getLong("bell_period_id"));
                    row.put("available", rs.getBoolean("available"));
                    row.put("note", rs.getString("note"));
                    return row;
                })
                .single();
    }

    public List<Map<String, Object>> overview(Long schoolId, String yearId) {
        return jdbc.sql("""
                SELECT e.id, e.day_name, e.subject_name, e.section_id, sec.name AS section_name,
                       cls.id AS class_id, cls.name AS class_name, e.teacher_id, st.name AS teacher_name,
                       e.room_id, room.name AS room_name, room.room_type,
                       e.bell_period_id, p.label AS period_label, p.start_time, p.end_time
                FROM tenant_school.school_timetable_entries e
                JOIN tenant_school.school_sections sec ON sec.id = e.section_id
                JOIN tenant_school.school_classes cls ON cls.id = sec.school_class_id
                JOIN tenant_school.school_bell_periods p ON p.id = e.bell_period_id
                LEFT JOIN tenant_school.staff_members st ON st.id = e.teacher_id
                LEFT JOIN tenant_school.school_rooms room ON room.id = e.room_id
                WHERE e.school_id = :schoolId AND e.academic_year_id = :yearId
                ORDER BY cls.sort_order, sec.name, e.day_name, p.start_time
                """)
                .param("schoolId", schoolId)
                .param("yearId", yearId)
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("day", rs.getString("day_name"));
                    row.put("subjectName", rs.getString("subject_name"));
                    row.put("sectionId", rs.getString("section_id"));
                    row.put("sectionName", rs.getString("section_name"));
                    row.put("classId", rs.getString("class_id"));
                    row.put("className", rs.getString("class_name"));
                    long teacher = rs.getLong("teacher_id");
                    row.put("teacherId", rs.wasNull() ? null : teacher);
                    row.put("teacherName", rs.getString("teacher_name"));
                    long room = rs.getLong("room_id");
                    row.put("roomId", rs.wasNull() ? null : room);
                    row.put("roomName", rs.getString("room_name"));
                    row.put("roomType", rs.getString("room_type"));
                    row.put("periodId", rs.getLong("bell_period_id"));
                    row.put("periodLabel", rs.getString("period_label"));
                    row.put("start", rs.getObject("start_time", LocalTime.class).format(TIME_FMT));
                    row.put("end", rs.getObject("end_time", LocalTime.class).format(TIME_FMT));
                    return row;
                })
                .list();
    }

    public Map<String, Object> health(Long schoolId, String yearId, String sectionId) {
        String conflictSectionPredicate = sectionId == null || sectionId.isBlank()
                ? ""
                : " AND (e1.section_id = :sectionId OR e2.section_id = :sectionId)";
        String sectionPredicate = sectionId == null || sectionId.isBlank() ? "" : " AND e1.section_id = :sectionId";
        long teacherConflicts = conflictCount(schoolId, yearId, sectionId, "teacher_id", conflictSectionPredicate);
        long roomConflicts = conflictCount(schoolId, yearId, sectionId, "room_id", conflictSectionPredicate);
        long unavailableTeachers = countWithOptionalSection("""
                SELECT COUNT(DISTINCT e1.id)
                FROM tenant_school.school_timetable_entries e1
                JOIN tenant_school.school_teacher_availability a
                  ON a.school_id = e1.school_id
                 AND a.academic_year_id = e1.academic_year_id
                 AND a.teacher_id = e1.teacher_id
                 AND a.day_name = e1.day_name
                JOIN tenant_school.school_bell_periods entry_period
                  ON entry_period.id = e1.bell_period_id
                JOIN tenant_school.school_bell_periods availability_period
                  ON availability_period.id = a.bell_period_id
                WHERE e1.school_id = :schoolId AND e1.academic_year_id = :yearId
                  AND a.available = false
                  AND entry_period.start_time < availability_period.end_time
                  AND availability_period.start_time < entry_period.end_time
                """ + sectionPredicate, schoolId, yearId, sectionId);
        long missingRooms = countWithOptionalSection("""
                SELECT COUNT(*)
                FROM tenant_school.school_timetable_entries e1
                JOIN tenant_school.school_sections sec ON sec.id = e1.section_id
                JOIN tenant_school.school_class_subjects subj
                  ON subj.school_id = e1.school_id
                 AND subj.academic_year_id = e1.academic_year_id
                 AND subj.class_id = sec.school_class_id
                 AND subj.subject_name = e1.subject_name
                LEFT JOIN tenant_school.school_rooms room ON room.id = e1.room_id
                WHERE e1.school_id = :schoolId AND e1.academic_year_id = :yearId
                  AND subj.required_room_type IS NOT NULL
                  AND (room.id IS NULL OR room.room_type <> subj.required_room_type)
                """ + sectionPredicate, schoolId, yearId, sectionId);
        long unassignedTeachers = countWithOptionalSection("""
                SELECT COUNT(*)
                FROM tenant_school.school_timetable_entries e1
                WHERE e1.school_id = :schoolId AND e1.academic_year_id = :yearId
                  AND e1.teacher_id IS NULL
                """ + sectionPredicate, schoolId, yearId, sectionId);
        long roomCapacityIssues = countWithOptionalSection("""
                SELECT COUNT(*)
                FROM tenant_school.school_timetable_entries e1
                JOIN tenant_school.school_rooms room ON room.id = e1.room_id
                WHERE e1.school_id = :schoolId AND e1.academic_year_id = :yearId
                  AND room.capacity > 0
                  AND room.capacity < (
                    SELECT COUNT(*)
                    FROM student.students student
                    WHERE student.school_id = e1.school_id
                      AND student.academic_year_id = e1.academic_year_id
                      AND student.section_id = e1.section_id
                      AND student.deleted_at IS NULL
                  )
                """ + sectionPredicate, schoolId, yearId, sectionId);
        String activeSectionPredicate = sectionId == null || sectionId.isBlank()
                ? ""
                : " AND sec.id = :sectionId";
        long unscheduledSections = countWithOptionalSection("""
                SELECT COUNT(*)
                FROM tenant_school.school_sections sec
                WHERE sec.school_id = :schoolId AND sec.active = true
                  AND (
                    NOT EXISTS (
                      SELECT 1
                      FROM tenant_school.school_class_bell_map class_schedule
                      WHERE class_schedule.school_id = sec.school_id
                        AND class_schedule.class_id = sec.school_class_id
                    )
                    OR NOT EXISTS (
                      SELECT 1
                      FROM tenant_school.school_timetable_entries entry
                      WHERE entry.school_id = sec.school_id
                        AND entry.academic_year_id = :yearId
                        AND entry.section_id = sec.id
                    )
                  )
                """ + activeSectionPredicate, schoolId, yearId, sectionId);
        String quotaSectionPredicate = sectionId == null || sectionId.isBlank() ? "" : " AND sec.id = :sectionId";
        long quotaIssues = countWithOptionalSection("""
                SELECT COUNT(*)
                FROM tenant_school.school_sections sec
                JOIN tenant_school.school_class_subjects subj
                  ON subj.school_id = sec.school_id
                 AND subj.class_id = sec.school_class_id
                 AND subj.academic_year_id = :yearId
                LEFT JOIN (
                    SELECT section_id, subject_name, COUNT(*) AS actual
                    FROM tenant_school.school_timetable_entries
                    WHERE school_id = :schoolId AND academic_year_id = :yearId
                    GROUP BY section_id, subject_name
                ) placed ON placed.section_id = sec.id AND placed.subject_name = subj.subject_name
                WHERE sec.school_id = :schoolId
                  AND subj.weekly_periods > 0
                  AND COALESCE(placed.actual, 0) <> subj.weekly_periods
                """ + quotaSectionPredicate, schoolId, yearId, sectionId);
        long preferredTimeIssues = countWithOptionalSection("""
                SELECT COUNT(*)
                FROM tenant_school.school_timetable_entries e1
                JOIN tenant_school.school_bell_periods period ON period.id = e1.bell_period_id
                JOIN tenant_school.school_sections sec ON sec.id = e1.section_id
                JOIN tenant_school.school_class_subjects subj
                  ON subj.school_id = e1.school_id
                 AND subj.academic_year_id = e1.academic_year_id
                 AND subj.class_id = sec.school_class_id
                 AND subj.subject_name = e1.subject_name
                WHERE e1.school_id = :schoolId AND e1.academic_year_id = :yearId
                  AND (
                    (subj.preferred_part_of_day = 'MORNING' AND period.start_time >= TIME '12:00')
                    OR
                    (subj.preferred_part_of_day = 'AFTERNOON' AND period.start_time < TIME '12:00')
                  )
                """ + sectionPredicate, schoolId, yearId, sectionId);
        long doublePeriodIssues = countWithOptionalSection("""
                SELECT COUNT(*)
                FROM tenant_school.school_timetable_entries e1
                JOIN tenant_school.school_bell_periods period ON period.id = e1.bell_period_id
                JOIN tenant_school.school_sections sec ON sec.id = e1.section_id
                JOIN tenant_school.school_class_subjects subj
                  ON subj.school_id = e1.school_id
                 AND subj.academic_year_id = e1.academic_year_id
                 AND subj.class_id = sec.school_class_id
                 AND subj.subject_name = e1.subject_name
                WHERE e1.school_id = :schoolId AND e1.academic_year_id = :yearId
                  AND subj.double_period = true
                  AND NOT EXISTS (
                    SELECT 1
                    FROM tenant_school.school_timetable_entries adjacent
                    JOIN tenant_school.school_bell_periods adjacent_period
                      ON adjacent_period.id = adjacent.bell_period_id
                    WHERE adjacent.school_id = e1.school_id
                      AND adjacent.academic_year_id = e1.academic_year_id
                      AND adjacent.section_id = e1.section_id
                      AND adjacent.day_name = e1.day_name
                      AND adjacent.subject_name = e1.subject_name
                      AND ABS(adjacent_period.sort_order - period.sort_order) = 1
                  )
                """ + sectionPredicate, schoolId, yearId, sectionId);

        long hardConflicts = teacherConflicts + roomConflicts + unavailableTeachers + missingRooms
                + unassignedTeachers + roomCapacityIssues + unscheduledSections;
        long preferenceIssues = quotaIssues + preferredTimeIssues + doublePeriodIssues;
        int score = Math.max(0, 100 - (int) Math.min(80, hardConflicts * 15 + preferenceIssues * 5));
        List<Map<String, Object>> issues = new java.util.ArrayList<>();
        addIssue(issues, "TEACHER_CONFLICT", "Teacher double-booking", teacherConflicts, true);
        addIssue(issues, "ROOM_CONFLICT", "Room double-booking", roomConflicts, true);
        addIssue(issues, "TEACHER_UNAVAILABLE", "Teacher assigned outside availability", unavailableTeachers, true);
        addIssue(issues, "TEACHER_UNASSIGNED", "Lesson has no assigned teacher", unassignedTeachers, true);
        addIssue(issues, "ROOM_REQUIREMENT", "Required room type not assigned", missingRooms, true);
        addIssue(issues, "ROOM_CAPACITY", "Room capacity is below section enrollment", roomCapacityIssues, true);
        addIssue(issues, "SECTION_UNSCHEDULED", "Active section has no timetable", unscheduledSections, true);
        addIssue(issues, "SUBJECT_QUOTA", "Subject weekly quota mismatch", quotaIssues, false);
        addIssue(issues, "SUBJECT_TIME_PREFERENCE", "Subject scheduled outside its preferred time", preferredTimeIssues, false);
        addIssue(issues, "DOUBLE_PERIOD", "Subject requires an adjacent double period", doublePeriodIssues, false);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("academicYearId", yearId);
        result.put("sectionId", sectionId);
        result.put("score", score);
        result.put("readyToPublish", hardConflicts == 0);
        result.put("hardConflicts", hardConflicts);
        result.put("teacherConflicts", teacherConflicts);
        result.put("roomConflicts", roomConflicts);
        result.put("unavailableTeachers", unavailableTeachers);
        result.put("unassignedTeachers", unassignedTeachers);
        result.put("missingRooms", missingRooms);
        result.put("roomCapacityIssues", roomCapacityIssues);
        result.put("unscheduledSections", unscheduledSections);
        result.put("quotaIssues", quotaIssues);
        result.put("preferredTimeIssues", preferredTimeIssues);
        result.put("doublePeriodIssues", doublePeriodIssues);
        result.put("preferenceIssues", preferenceIssues);
        result.put("issues", issues);
        result.put("publication", currentPublication(schoolId, yearId));
        return result;
    }

    @Transactional
    public Map<String, Object> publish(Long schoolId, String yearId, Long actorId, String label) {
        if (!yearId.equals(activeYearId(schoolId))) {
            throw new YearLockedException("Only the active academic year can be published");
        }
        Map<String, Object> health = health(schoolId, yearId, null);
        if (!Boolean.TRUE.equals(health.get("readyToPublish"))) {
            throw new IllegalArgumentException("Resolve all hard timetable conflicts before publishing");
        }
        int revision = jdbc.sql("""
                SELECT COALESCE(MAX(revision), 0) + 1
                FROM tenant_school.school_timetable_publications
                WHERE school_id = :schoolId AND academic_year_id = :yearId
                """)
                .param("schoolId", schoolId)
                .param("yearId", yearId)
                .query(Integer.class)
                .single();
        String snapshot = jdbc.sql("""
                SELECT COALESCE(jsonb_agg(jsonb_build_object(
                    'sectionId', e.section_id,
                    'day', e.day_name,
                    'periodId', e.bell_period_id,
                    'subjectName', e.subject_name,
                    'teacherId', e.teacher_id,
                    'roomId', e.room_id
                ) ORDER BY e.section_id, e.day_name, e.bell_period_id), '[]'::jsonb)::text
                FROM tenant_school.school_timetable_entries e
                WHERE e.school_id = :schoolId AND e.academic_year_id = :yearId
                """)
                .param("schoolId", schoolId)
                .param("yearId", yearId)
                .query(String.class)
                .single();
        return jdbc.sql("""
                INSERT INTO tenant_school.school_timetable_publications
                    (school_id, academic_year_id, revision, label, snapshot, published_by)
                VALUES (:schoolId, :yearId, :revision, :label, CAST(:snapshot AS jsonb), :actorId)
                RETURNING id, revision, label, published_at, published_by
                """)
                .param("schoolId", schoolId)
                .param("yearId", yearId)
                .param("revision", revision)
                .param("label", label == null || label.isBlank() ? "Published timetable v" + revision : label.trim())
                .param("snapshot", snapshot)
                .param("actorId", actorId)
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("revision", rs.getInt("revision"));
                    row.put("label", rs.getString("label"));
                    row.put("publishedAt", rs.getObject("published_at", OffsetDateTime.class));
                    long by = rs.getLong("published_by");
                    row.put("publishedBy", rs.wasNull() ? null : by);
                    return row;
                })
                .single();
    }

    private Map<String, Object> currentPublication(Long schoolId, String yearId) {
        if (yearId == null) return Map.of();
        return jdbc.sql("""
                SELECT id, revision, label, published_at, published_by
                FROM tenant_school.school_timetable_publications
                WHERE school_id = :schoolId AND academic_year_id = :yearId
                ORDER BY revision DESC
                LIMIT 1
                """)
                .param("schoolId", schoolId)
                .param("yearId", yearId)
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("revision", rs.getInt("revision"));
                    row.put("label", rs.getString("label"));
                    row.put("publishedAt", rs.getObject("published_at", OffsetDateTime.class));
                    long by = rs.getLong("published_by");
                    row.put("publishedBy", rs.wasNull() ? null : by);
                    return row;
                })
                .optional()
                .orElseGet(LinkedHashMap::new);
    }

    private long conflictCount(
            Long schoolId,
            String yearId,
            String sectionId,
            String resourceColumn,
            String sectionPredicate) {
        String sql = """
                SELECT COUNT(*)
                FROM tenant_school.school_timetable_entries e1
                JOIN tenant_school.school_bell_periods p1 ON p1.id = e1.bell_period_id
                JOIN tenant_school.school_timetable_entries e2
                  ON e2.school_id = e1.school_id
                 AND e2.academic_year_id = e1.academic_year_id
                 AND e2.day_name = e1.day_name
                 AND e2.%1$s = e1.%1$s
                 AND e2.id > e1.id
                JOIN tenant_school.school_bell_periods p2 ON p2.id = e2.bell_period_id
                WHERE e1.school_id = :schoolId AND e1.academic_year_id = :yearId
                  AND e1.%1$s IS NOT NULL
                  AND p1.start_time < p2.end_time
                  AND p2.start_time < p1.end_time
                %2$s
                """.formatted(resourceColumn, sectionPredicate);
        var spec = jdbc.sql(sql)
                .param("schoolId", schoolId)
                .param("yearId", yearId);
        if (sectionId != null && !sectionId.isBlank()) spec = spec.param("sectionId", sectionId);
        Long count = spec.query(Long.class).single();
        return count == null ? 0 : count;
    }

    private long countWithOptionalSection(
            String sql,
            Long schoolId,
            String yearId,
            String sectionId) {
        var spec = jdbc.sql(sql)
                .param("schoolId", schoolId)
                .param("yearId", yearId);
        if (sectionId != null && !sectionId.isBlank()) spec = spec.param("sectionId", sectionId);
        Long count = spec.query(Long.class).single();
        return count == null ? 0 : count;
    }

    private void addIssue(
            List<Map<String, Object>> issues,
            String code,
            String label,
            long count,
            boolean hard) {
        if (count <= 0) return;
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("code", code);
        issue.put("label", label);
        issue.put("count", count);
        issue.put("severity", hard ? "HARD" : "PREFERENCE");
        issues.add(issue);
    }

    private void requireTeacherInSchool(Long schoolId, Long teacherId) {
        boolean exists = jdbc.sql("""
                SELECT 1 FROM tenant_school.staff_members
                WHERE id = :teacherId AND school_id = :schoolId
                """)
                .param("teacherId", teacherId)
                .param("schoolId", schoolId)
                .query(Integer.class)
                .optional()
                .isPresent();
        if (!exists) throw new IllegalArgumentException("Teacher not found in this school");
    }

    private void requireRoomInSchool(Long schoolId, Long roomId) {
        boolean exists = jdbc.sql("""
                SELECT 1 FROM tenant_school.school_rooms
                WHERE id = :roomId AND school_id = :schoolId AND active = true
                """)
                .param("roomId", roomId)
                .param("schoolId", schoolId)
                .query(Integer.class)
                .optional()
                .isPresent();
        if (!exists) throw new IllegalArgumentException("Room not found in this school");
    }

    private void requirePeriodInSchool(Long schoolId, long periodId) {
        boolean exists = jdbc.sql("""
                SELECT 1 FROM tenant_school.school_bell_periods
                WHERE id = :periodId AND school_id = :schoolId AND is_break = false
                """)
                .param("periodId", periodId)
                .param("schoolId", schoolId)
                .query(Integer.class)
                .optional()
                .isPresent();
        if (!exists) throw new IllegalArgumentException("Period not found in this school");
    }

    private void requireDay(String day) {
        if (day == null || !DAYS.contains(day)) {
            throw new IllegalArgumentException("Day must be one of " + String.join(", ", DAYS));
        }
    }

    private String teacherConflict(
            Long schoolId,
            String yearId,
            String sectionId,
            String day,
            long periodId,
            Long teacherId) {
        return jdbc.sql("""
                SELECT teacher.name || ' already teaches ' || cls.name || ' ' || sec.name
                FROM tenant_school.school_timetable_entries current_entry
                JOIN tenant_school.school_bell_periods current_period
                  ON current_period.id = current_entry.bell_period_id
                JOIN tenant_school.school_timetable_entries other
                  ON other.school_id = current_entry.school_id
                 AND other.academic_year_id = current_entry.academic_year_id
                 AND other.day_name = current_entry.day_name
                 AND other.teacher_id = current_entry.teacher_id
                 AND other.id <> current_entry.id
                JOIN tenant_school.school_bell_periods other_period
                  ON other_period.id = other.bell_period_id
                JOIN tenant_school.school_sections sec ON sec.id = other.section_id
                JOIN tenant_school.school_classes cls ON cls.id = sec.school_class_id
                JOIN tenant_school.staff_members teacher ON teacher.id = current_entry.teacher_id
                WHERE current_entry.school_id = :schoolId
                  AND current_entry.academic_year_id = :yearId
                  AND current_entry.section_id = :sectionId
                  AND current_entry.day_name = :day
                  AND current_entry.bell_period_id = :periodId
                  AND current_entry.teacher_id = :teacherId
                  AND current_period.start_time < other_period.end_time
                  AND other_period.start_time < current_period.end_time
                LIMIT 1
                """)
                .param("schoolId", schoolId)
                .param("yearId", yearId)
                .param("sectionId", sectionId)
                .param("day", day)
                .param("periodId", periodId)
                .param("teacherId", teacherId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private String roomConflict(
            Long schoolId,
            String yearId,
            String sectionId,
            String day,
            long periodId,
            Long roomId) {
        return jdbc.sql("""
                SELECT room.name || ' is already assigned to ' || cls.name || ' ' || sec.name
                FROM tenant_school.school_timetable_entries current_entry
                JOIN tenant_school.school_bell_periods current_period ON current_period.id = current_entry.bell_period_id
                JOIN tenant_school.school_timetable_entries other
                  ON other.school_id = current_entry.school_id
                 AND other.academic_year_id = current_entry.academic_year_id
                 AND other.day_name = current_entry.day_name
                 AND other.room_id = current_entry.room_id
                 AND other.id <> current_entry.id
                JOIN tenant_school.school_bell_periods other_period ON other_period.id = other.bell_period_id
                JOIN tenant_school.school_sections sec ON sec.id = other.section_id
                JOIN tenant_school.school_classes cls ON cls.id = sec.school_class_id
                JOIN tenant_school.school_rooms room ON room.id = current_entry.room_id
                WHERE current_entry.school_id = :schoolId
                  AND current_entry.academic_year_id = :yearId
                  AND current_entry.section_id = :sectionId
                  AND current_entry.day_name = :day
                  AND current_entry.bell_period_id = :periodId
                  AND current_entry.room_id = :roomId
                  AND current_period.start_time < other_period.end_time
                  AND other_period.start_time < current_period.end_time
                LIMIT 1
                """)
                .param("schoolId", schoolId)
                .param("yearId", yearId)
                .param("sectionId", sectionId)
                .param("day", day)
                .param("periodId", periodId)
                .param("roomId", roomId)
                .query(String.class)
                .optional()
                .orElse(null);
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
