package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;

/** Stores the exact reviewed current-year plan; mutations later require a new confirmation. */
@Repository
public class AnnualPlanConfirmationRepository {
    private final JdbcClient jdbc;
    private final OutboxWriter outbox;
    private final ObjectMapper mapper;
    public AnnualPlanConfirmationRepository(JdbcClient jdbc, OutboxWriter outbox, ObjectMapper mapper) {
        this.jdbc = jdbc; this.outbox = outbox; this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> review(Long schoolId) {
        Snapshot snapshot = snapshot(schoolId);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("schoolId",schoolId); result.put("academicYearId",snapshot.year().id()); result.put("yearLabel",snapshot.year().label());
        result.put("items",snapshot.items()); result.put("fingerprint",snapshot.fingerprint());
        result.put("confirmation",existing(schoolId,snapshot.year().id(),snapshot.fingerprint()).orElse(null));
        return result;
    }

    @Transactional
    public Map<String, Object> confirm(Long schoolId, Long actorId, String reviewedFingerprint) {
        requireSchool(schoolId);
        if (actorId == null || actorId <= 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"An authenticated actor is required to confirm a plan");
        // Serializes confirmations for this school, including duplicate requests on different replicas.
        // Item edits stay independent: their changed fingerprint is never covered by this snapshot.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key","annual-plan:"+schoolId).query().singleRow();
        Snapshot snapshot = snapshot(schoolId);
        if (reviewedFingerprint == null || !snapshot.fingerprint().equals(reviewedFingerprint)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"The plan changed or has not been reviewed. Refresh the current plan before confirming.");
        }
        if (snapshot.items().isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Add at least one current-year plan item before confirming");
        var replay = existing(schoolId,snapshot.year().id(),snapshot.fingerprint());
        if (replay.isPresent()) return replay.get();
        int revision = jdbc.sql("SELECT COALESCE(MAX(revision),0)+1 FROM catalog.annual_plan_confirmations WHERE school_id=:school AND academic_year_id=:year")
                .param("school",schoolId).param("year",snapshot.year().id()).query(Integer.class).single();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.annual_plan_confirmations
                  (id,school_id,academic_year_id,revision,fingerprint,item_count,snapshot_json,confirmed_by)
                VALUES (:id,:school,:year,:revision,:fingerprint,:count,:snapshot::jsonb,:actor)
                """).param("id",id).param("school",schoolId).param("year",snapshot.year().id()).param("revision",revision)
                .param("fingerprint",snapshot.fingerprint()).param("count",snapshot.items().size())
                .param("snapshot",mapper.writeValueAsString(snapshot.items())).param("actor",actorId).update();
        outbox.append("catalog.annual-plan-confirmed.v1","AnnualPlanConfirmed:"+id,"AnnualPlan",schoolId+":"+snapshot.year().id(),schoolId,
                Map.of("confirmationId",id.toString(),"schoolId",schoolId,"academicYearId",snapshot.year().id(),"revision",revision,
                        "itemCount",snapshot.items().size(),"fingerprint",snapshot.fingerprint(),"confirmedBy",actorId));
        return existing(schoolId,snapshot.year().id(),snapshot.fingerprint()).orElseThrow();
    }

    private Snapshot snapshot(Long schoolId) {
        requireSchool(schoolId);
        var year = AcademicCalendar.currentAcademicYear(AcademicCalendar.academicYearStartMonth(jdbc,schoolId));
        List<Map<String,Object>> items = jdbc.sql("""
                SELECT id,term_name,category,description,quantity,estimated_amount,status,linked_order_id
                FROM catalog.annual_plan_items WHERE school_id=:school AND academic_year_id=:year
                ORDER BY id LIMIT 10001
                """).param("school",schoolId).param("year",year.id()).query((rs,row) -> {
                    Map<String,Object> item = new LinkedHashMap<>(); item.put("id",rs.getString("id")); item.put("term",rs.getString("term_name"));
                    item.put("category",rs.getString("category")); item.put("description",rs.getString("description")); item.put("quantity",rs.getString("quantity"));
                    item.put("estimatedAmount",rs.getLong("estimated_amount")); item.put("status",rs.getString("status")); item.put("linkedOrderId",rs.getString("linked_order_id")); return item;
                }).list();
        if (items.size()>10000) throw new ResponseStatusException(HttpStatus.CONFLICT,"The plan exceeds the 10000-item review limit; no partial confirmation was recorded");
        try {
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    mapper.writeValueAsString(List.of(schoolId,year.id(),items)).getBytes(StandardCharsets.UTF_8)));
            return new Snapshot(year,items,fingerprint);
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private Optional<Map<String,Object>> existing(long schoolId, String year, String fingerprint) {
        return jdbc.sql("""
                SELECT id,revision,confirmed_at,item_count FROM catalog.annual_plan_confirmations
                WHERE school_id=:school AND academic_year_id=:year AND fingerprint=:fingerprint
                """).param("school",schoolId).param("year",year).param("fingerprint",fingerprint).query((rs,row) -> {
                    Map<String,Object> result = new LinkedHashMap<>(); result.put("confirmed",true); result.put("id",rs.getObject("id",UUID.class));
                    result.put("schoolId",schoolId); result.put("academicYearId",year); result.put("revision",rs.getInt("revision"));
                    result.put("confirmedAt",rs.getObject("confirmed_at",OffsetDateTime.class)); result.put("itemCount",rs.getInt("item_count"));
                    result.put("fingerprint",fingerprint); result.put("notificationStatus","NOT_SENT"); return result;
                }).optional();
    }

    private void requireSchool(Long schoolId) {
        if (schoolId == null || schoolId <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a school before reviewing its annual plan");
        if (jdbc.sql("SELECT count(*) FROM tenant_school.schools WHERE id=:id").param("id",schoolId).query(Long.class).single()!=1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"School not found in the current scope");
        }
    }
    private record Snapshot(AcademicCalendar.AcademicYear year,List<Map<String,Object>> items,String fingerprint) {}
}
