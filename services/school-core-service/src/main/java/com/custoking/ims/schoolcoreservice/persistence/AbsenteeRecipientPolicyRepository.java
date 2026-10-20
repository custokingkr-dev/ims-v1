package com.custoking.ims.schoolcoreservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Revalidates the exact queued absence and current guardian policy under one school. */
@Repository
public class AbsenteeRecipientPolicyRepository {
    private final JdbcClient jdbc;
    private final GuardianCommunicationPolicy policy;

    public AbsenteeRecipientPolicyRepository(JdbcClient jdbc) {
        this(jdbc, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AbsenteeRecipientPolicyRepository(JdbcClient jdbc,
            com.custoking.ims.schoolcoreservice.erasure.StudentErasureJournal erasureJournal) {
        this.jdbc = jdbc;
        this.policy = new GuardianCommunicationPolicy(jdbc, erasureJournal);
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> resolve(long schoolId, long studentId, String channel, String eventId,
            String notificationId, LocalDate date, String messageSha256) {
        if (schoolId <= 0 || studentId <= 0 || notificationId == null
                || !notificationId.matches("[A-Za-z0-9:_-]{1,255}")
                || !("school-core:absentee:" + notificationId).equals(eventId)
                || channel == null || !List.of("SMS", "WHATSAPP", "EMAIL").contains(channel)
                || date == null || messageSha256 == null || !messageSha256.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid bounded absentee request");
        jdbc.sql("SELECT set_config('app.bypass_rls', 'off', true)").query(String.class).single();
        jdbc.sql("SELECT set_config('app.current_school_id', :school, true)")
                .param("school", Long.toString(schoolId)).query(String.class).single();
        String originalMessage = jdbc.sql("""
                SELECT n.message FROM attendance.absentee_notifications n
                JOIN student.students s ON s.id=n.student_id AND s.school_id=n.school_id AND s.deleted_at IS NULL
                WHERE n.id=:id AND n.school_id=:school AND n.student_id=:student
                  AND n.channel=:channel AND n.attendance_date=:date
                  AND n.status IN ('QUEUED','FAILED')
                  AND EXISTS (SELECT 1 FROM attendance.attendance_student_records a
                    WHERE a.student_id=n.student_id AND a.school_id=n.school_id
                      AND a.attendance_date=n.attendance_date AND a.academic_year_id=n.academic_year_id
                      AND a.class_id=n.class_id AND a.section_id=n.section_id AND a.status='ABSENT')
                """).param("id",notificationId).param("school",schoolId).param("student",studentId)
                .param("channel",channel).param("date",date).query(String.class).optional().orElse(null);
        var row = new LinkedHashMap<String,Object>();
        row.put("schoolId",schoolId); row.put("studentId",studentId); row.put("channel",channel); row.put("eventId",eventId);
        if (originalMessage == null || !MessageDigest.isEqual(sha256(originalMessage).getBytes(StandardCharsets.US_ASCII),
                messageSha256.getBytes(StandardCharsets.US_ASCII))) {
            row.put("allowed",false); row.put("reason","ABSENTEE_REQUEST_NOT_CURRENT");
            return List.of(row);
        }
        var decision = policy.evaluate(schoolId,studentId,channel);
        row.put("allowed",decision.allowed()); row.put("reason",decision.reason());
        if (decision.allowed()) {
            row.put("guardianId",decision.guardianId()); row.put("destination",decision.destination());
            row.put("destinationSha256",GuardianCommunicationPolicy.destinationSha256(channel,decision.destination()));
            var evidence = decision.evidence(eventId);
            evidence.put("notificationCategory","ABSENTEE_ALERT"); evidence.put("absenteeNotificationId",notificationId);
            evidence.put("attendanceDate",date.toString()); evidence.put("messageSha256",messageSha256);
            row.put("policyEvidence",evidence);
        }
        return List.of(row);
    }

    public static String sha256(String message) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(message.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable",impossible); }
    }
}
