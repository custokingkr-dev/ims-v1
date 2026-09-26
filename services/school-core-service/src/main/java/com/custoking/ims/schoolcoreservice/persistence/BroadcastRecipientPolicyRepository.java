package com.custoking.ims.schoolcoreservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Authoritative recipient resolution; no destination or consent evidence comes from the browser. */
@Repository
public class BroadcastRecipientPolicyRepository {
    private final JdbcClient jdbc;
    private final GuardianCommunicationPolicy policy;

    public BroadcastRecipientPolicyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.policy = new GuardianCommunicationPolicy(jdbc);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> resolve(long schoolId, UUID broadcastId, List<String> channels, List<Long> studentIds) {
        if (schoolId <= 0 || broadcastId == null) throw new IllegalArgumentException("School and broadcast are required");
        List<String> normalized = channels == null ? List.of() : channels.stream().map(value -> value == null ? "" : value.trim().toUpperCase(Locale.ROOT)).distinct().toList();
        if (normalized.isEmpty() || normalized.stream().anyMatch(value -> !List.of("SMS", "EMAIL", "WHATSAPP").contains(value))) {
            throw new IllegalArgumentException("Supported channels are SMS, EMAIL and WHATSAPP");
        }
        // Bind one transaction to one school; never enable a cross-school RLS bypass.
        jdbc.sql("SELECT set_config('app.bypass_rls', 'off', true)").query(String.class).single();
        jdbc.sql("SELECT set_config('app.current_school_id', :schoolId, true)").param("schoolId", String.valueOf(schoolId)).query(String.class).single();
        List<Long> students;
        if (studentIds == null) {
            students = jdbc.sql("SELECT id FROM student.students WHERE school_id = :schoolId AND deleted_at IS NULL ORDER BY id LIMIT 10001")
                    .param("schoolId", schoolId).query(Long.class).list();
            if (students.size() > 10000) throw new IllegalArgumentException("Audience exceeds the 10000-student review limit; no recipients were selected");
        } else {
            if (studentIds.size() > 100 || studentIds.stream().anyMatch(id -> id == null || id <= 0)) throw new IllegalArgumentException("Invalid student selection");
            students = studentIds.stream().distinct().toList();
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (long studentId : students) {
            for (String channel : normalized) {
                String eventId = "broadcast:" + broadcastId + ":" + studentId + ":" + channel;
                GuardianCommunicationPolicy.Decision decision = policy.evaluate(schoolId, studentId, channel);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("studentId", studentId); row.put("schoolId", schoolId); row.put("channel", channel); row.put("eventId", eventId);
                row.put("allowed", decision.allowed()); row.put("reason", decision.reason());
                if (decision.allowed()) {
                    row.put("guardianId", decision.guardianId()); row.put("destination", decision.destination());
                    row.put("destinationSha256", GuardianCommunicationPolicy.destinationSha256(channel, decision.destination()));
                    row.put("policyEvidence", decision.evidence(eventId));
                }
                results.add(row);
            }
        }
        return results;
    }
}
