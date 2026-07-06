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
    public void deletePeriod(Long schoolId, long periodId) {
        jdbc.sql("""
                DELETE FROM tenant_school.school_bell_periods
                WHERE school_id = :s AND id = :id
                """)
                .param("s", schoolId)
                .param("id", periodId)
                .update();
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
}
