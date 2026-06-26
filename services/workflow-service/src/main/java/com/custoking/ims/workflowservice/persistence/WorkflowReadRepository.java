package com.custoking.ims.workflowservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class WorkflowReadRepository {

    private final JdbcClient jdbc;
    private final String definitionsTable;
    private final String stepsTable;
    private final String instancesTable;
    private final String actionsTable;

    public WorkflowReadRepository(
            JdbcClient jdbc,
            @Value("${workflow.db.schema:public}") String schema) {
        this.jdbc = jdbc;
        this.definitionsTable = qualifiedTable(schema, "workflow_definitions");
        this.stepsTable = qualifiedTable(schema, "workflow_steps");
        this.instancesTable = qualifiedTable(schema, "workflow_instances");
        this.actionsTable = qualifiedTable(schema, "workflow_actions");
    }

    public List<WorkflowDefinitionRow> definitions(boolean activeOnly) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, name, description, active, created_at
                FROM %s
                WHERE 1=1
                """.formatted(definitionsTable));
        if (activeOnly) sql.append(" AND active = true");
        sql.append(" ORDER BY name");
        return jdbc.sql(sql.toString()).query(WorkflowDefinitionRow.class).list();
    }

    public List<WorkflowStepRow> steps(String definitionId) {
        return jdbc.sql("""
                SELECT id, definition_id, step_order, step_name, required_permission,
                       required_role, auto_approve
                FROM %s
                WHERE definition_id = :definitionId
                ORDER BY step_order
                """.formatted(stepsTable)).param("definitionId", definitionId)
                .query(WorkflowStepRow.class)
                .list();
    }

    public List<WorkflowInstanceRow> instances(Long schoolId, String status, String entityType, int limit) {
        StringBuilder sql = new StringBuilder(instanceSelect()).append(" WHERE 1=1");
        if (schoolId != null) sql.append(" AND school_id = :schoolId");
        if (status != null && !status.isBlank()) sql.append(" AND status = :status");
        if (entityType != null && !entityType.isBlank()) sql.append(" AND entity_type = :entityType");
        sql.append(" ORDER BY initiated_at DESC LIMIT :limit");

        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 500)));
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        if (status != null && !status.isBlank()) spec = spec.param("status", status);
        if (entityType != null && !entityType.isBlank()) spec = spec.param("entityType", entityType);
        return spec.query(WorkflowInstanceRow.class).list();
    }

    public List<WorkflowInstanceRow> pending(Long schoolId, int limit) {
        return instances(schoolId, "PENDING", null, limit);
    }

    public Optional<WorkflowInstanceRow> instance(Long id) {
        return jdbc.sql(instanceSelect() + " WHERE id = :id")
                .param("id", id)
                .query(WorkflowInstanceRow.class)
                .optional();
    }

    public List<WorkflowActionRow> actions(Long instanceId) {
        return jdbc.sql("""
                SELECT id, instance_id, step_order, action, actor_id, actor_email, notes, acted_at
                FROM %s
                WHERE instance_id = :instanceId
                ORDER BY acted_at ASC, id ASC
                """.formatted(actionsTable)).param("instanceId", instanceId)
                .query(WorkflowActionRow.class)
                .list();
    }

    @Transactional
    public Map<String, Object> createOrGetInstance(Map<String, Object> request) {
        String entityType = requireText(request.get("entityType"), "entityType is required");
        String entityId = requireText(request.get("entityId"), "entityId is required");
        Optional<Map<String, Object>> existing = instanceMapByEntity(entityType, entityId);
        if (existing.isPresent()) return existing.get();

        String definitionId = requireText(request.get("definitionId"), "definitionId is required");
        long activeDefinition = jdbc.sql("SELECT COUNT(*) FROM " + definitionsTable + " WHERE id = :id AND active = true")
                .param("id", definitionId)
                .query(Long.class)
                .single();
        if (activeDefinition == 0) {
            throw new IllegalArgumentException("Workflow definition not found: " + definitionId);
        }
        Long id = jdbc.sql("""
                INSERT INTO %s(definition_id, entity_type, entity_id, school_id,
                                               current_step, status, initiated_by)
                VALUES (:definitionId, :entityType, :entityId, :schoolId, 0, 'PENDING', :initiatedBy)
                RETURNING id
                """.formatted(instancesTable))
                .param("definitionId", definitionId)
                .param("entityType", entityType)
                .param("entityId", entityId)
                .param("schoolId", longValue(request.get("schoolId"), null))
                .param("initiatedBy", longValue(request.get("initiatedBy"), null))
                .query(Long.class)
                .single();
        return instanceMap(id);
    }

    @Transactional
    public Map<String, Object> submit(Long instanceId, Map<String, Object> request) {
        Map<String, Object> instance = instanceMap(instanceId);
        if (!"PENDING".equals(instance.get("status"))) {
            throw new IllegalArgumentException("Workflow is not in PENDING state");
        }
        recordAction(instanceId, 0, "SUBMIT", request);
        jdbc.sql("UPDATE " + instancesTable + " SET current_step = 1, status = 'IN_PROGRESS' WHERE id = :id")
                .param("id", instanceId)
                .update();
        return instanceMap(instanceId);
    }

    @Transactional
    public Map<String, Object> approve(Long instanceId, Map<String, Object> request) {
        Map<String, Object> instance = instanceMap(instanceId);
        if (!"IN_PROGRESS".equals(instance.get("status"))) {
            throw new IllegalArgumentException("Workflow is not IN_PROGRESS");
        }
        int currentStep = intValue(instance.get("currentStep"), 0);
        recordAction(instanceId, currentStep, "APPROVE", request);
        int maxStep = jdbc.sql("SELECT COALESCE(MAX(step_order), 0) FROM " + stepsTable + " WHERE definition_id = :definitionId")
                .param("definitionId", instance.get("definitionId"))
                .query(Integer.class)
                .single();
        if (currentStep >= maxStep) {
            jdbc.sql("UPDATE " + instancesTable + " SET status = 'APPROVED', completed_at = :completedAt WHERE id = :id")
                    .param("id", instanceId)
                    .param("completedAt", OffsetDateTime.now())
                    .update();
        } else {
            jdbc.sql("UPDATE " + instancesTable + " SET current_step = :currentStep WHERE id = :id")
                    .param("id", instanceId)
                    .param("currentStep", currentStep + 1)
                    .update();
        }
        return instanceMap(instanceId);
    }

    @Transactional
    public Map<String, Object> reject(Long instanceId, Map<String, Object> request) {
        Map<String, Object> instance = instanceMap(instanceId);
        if (!"IN_PROGRESS".equals(instance.get("status"))) {
            throw new IllegalArgumentException("Workflow is not IN_PROGRESS");
        }
        recordAction(instanceId, intValue(instance.get("currentStep"), 0), "REJECT", request);
        return finish(instanceId, "REJECTED");
    }

    @Transactional
    public Map<String, Object> cancel(Long instanceId, Map<String, Object> request) {
        Map<String, Object> instance = instanceMap(instanceId);
        recordAction(instanceId, intValue(instance.get("currentStep"), 0), "CANCEL", request);
        return finish(instanceId, "CANCELLED");
    }

    @Transactional
    public Map<String, Object> complete(Long instanceId, Map<String, Object> request) {
        Map<String, Object> instance = instanceMap(instanceId);
        recordAction(instanceId, intValue(instance.get("currentStep"), 0), "COMPLETE", request);
        return finish(instanceId, "COMPLETED");
    }

    private String instanceSelect() {
        return """
                SELECT id, definition_id, entity_type, entity_id, school_id, current_step,
                       status, initiated_by, initiated_at, completed_at, version
                FROM %s
                """.formatted(instancesTable);
    }

    private Optional<Map<String, Object>> instanceMapByEntity(String entityType, String entityId) {
        return jdbc.sql(instanceSelect() + " WHERE entity_type = :entityType AND entity_id = :entityId ORDER BY id LIMIT 1")
                .param("entityType", entityType)
                .param("entityId", entityId)
                .query((rs, rowNum) -> instanceRowMap(rs))
                .optional();
    }

    private Map<String, Object> instanceMap(Long id) {
        return jdbc.sql(instanceSelect() + " WHERE id = :id")
                .param("id", id)
                .query((rs, rowNum) -> instanceRowMap(rs))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Workflow instance not found"));
    }

    private Map<String, Object> instanceRowMap(java.sql.ResultSet rs) throws java.sql.SQLException {
        return row(
                "id", rs.getLong("id"),
                "definitionId", rs.getString("definition_id"),
                "entityType", rs.getString("entity_type"),
                "entityId", rs.getString("entity_id"),
                "schoolId", rs.getObject("school_id") == null ? null : rs.getLong("school_id"),
                "currentStep", rs.getInt("current_step"),
                "status", rs.getString("status"),
                "initiatedBy", rs.getObject("initiated_by") == null ? null : rs.getLong("initiated_by"),
                "initiatedAt", rs.getObject("initiated_at", OffsetDateTime.class),
                "completedAt", rs.getObject("completed_at", OffsetDateTime.class),
                "version", rs.getLong("version"));
    }

    private void recordAction(Long instanceId, int stepOrder, String action, Map<String, Object> request) {
        jdbc.sql("""
                INSERT INTO %s(instance_id, step_order, action, actor_id, actor_email, notes)
                VALUES (:instanceId, :stepOrder, :action, :actorId, :actorEmail, :notes)
                """.formatted(actionsTable))
                .param("instanceId", instanceId)
                .param("stepOrder", stepOrder)
                .param("action", action)
                .param("actorId", longValue(request.get("actorId"), null))
                .param("actorEmail", textOrNull(request.get("actorEmail")))
                .param("notes", textOrNull(request.get("notes")))
                .update();
    }

    private Map<String, Object> finish(Long instanceId, String status) {
        jdbc.sql("UPDATE " + instancesTable + " SET status = :status, completed_at = :completedAt WHERE id = :id")
                .param("id", instanceId)
                .param("status", status)
                .param("completedAt", OffsetDateTime.now())
                .update();
        return instanceMap(instanceId);
    }

    private String requireText(Object value, String message) {
        String text = textOrNull(value);
        if (text == null || text.isBlank()) throw new IllegalArgumentException(message);
        return text;
    }

    private String qualifiedTable(String schema, String table) {
        String normalizedSchema = identifier(schema == null || schema.isBlank() ? "public" : schema);
        return normalizedSchema + "." + identifier(table);
    }

    private String identifier(String identifier) {
        if (!identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid database identifier: " + identifier);
        }
        return identifier;
    }

    private String textOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long longValue(Object value, Long fallback) {
        if (value == null) return fallback;
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value).replace(",", "").trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private int intValue(Object value, int fallback) {
        if (value == null) return fallback;
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value).replace(",", "").trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private Map<String, Object> row(Object... kv) {
        if (kv.length % 2 != 0) throw new IllegalArgumentException("row requires key/value pairs");
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put(String.valueOf(kv[i]), kv[i + 1]);
        return map;
    }

    public record WorkflowDefinitionRow(
            String id,
            String name,
            String description,
            Boolean active,
            OffsetDateTime createdAt) {
    }

    public record WorkflowStepRow(
            Long id,
            String definitionId,
            Integer stepOrder,
            String stepName,
            String requiredPermission,
            String requiredRole,
            Boolean autoApprove) {
    }

    public record WorkflowInstanceRow(
            Long id,
            String definitionId,
            String entityType,
            String entityId,
            Long schoolId,
            Integer currentStep,
            String status,
            Long initiatedBy,
            OffsetDateTime initiatedAt,
            OffsetDateTime completedAt,
            Long version) {
    }

    public record WorkflowActionRow(
            Long id,
            Long instanceId,
            Integer stepOrder,
            String action,
            Long actorId,
            String actorEmail,
            String notes,
            OffsetDateTime actedAt) {
    }
}
