package com.custoking.ims.operationsservice.persistence;

import com.custoking.ims.operationsservice.security.TenantContext;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL for the order workflow's global settings and WhatsApp group links (design D34, workflow V15 and V19):
 * <ul>
 *   <li>{@code workflow_settings}: global, no row-level security; today only the internal approval group's invite.</li>
 *   <li>{@code workflow_school_item_group_links}: a school's group for one item, overriding its school-wide group. Tenant
 *       data under row-level security.</li>
 *   <li>{@code workflow_school_whatsapp_group_links}: the school-wide groups ({@code SCHOOL_GROUP},
 *       {@code INTERNAL_APPROVAL}), read here and maintained through the school endpoints. Tenant data.</li>
 * </ul>
 * Every tenant read and write must run inside the transaction that applied the caller's scope.
 */
@Repository
public class WorkflowSettingsRepository {
    /** The global internal approval group's invite (the only key V19's CHECK allows). */
    public static final String INTERNAL_APPROVAL_GROUP_URL = "INTERNAL_APPROVAL_GROUP_URL";
    /** The school-wide group targets an order resolves (V15's keys). */
    public static final String SCHOOL_GROUP = "SCHOOL_GROUP";
    public static final String INTERNAL_APPROVAL = "INTERNAL_APPROVAL";

    private final JdbcClient jdbc;
    private final String settings;
    private final String itemLinks;
    private final String schoolLinks;
    private final String instanceSteps;
    private final String actions;

    public WorkflowSettingsRepository(JdbcClient jdbc, @Value("${workflow.db.schema:workflow}") String schema) {
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid schema name");
        this.jdbc = jdbc;
        this.settings = schema + ".workflow_settings";
        this.itemLinks = schema + ".workflow_school_item_group_links";
        this.schoolLinks = schema + ".workflow_school_whatsapp_group_links";
        this.instanceSteps = schema + ".workflow_instance_steps";
        this.actions = schema + ".workflow_actions";
    }

    /** A saved setting with who saved it last and when. */
    public record Setting(String value, OffsetDateTime updatedAt, Long updatedBy) { }

    // ---- global settings ---------------------------------------------------------------------------------------------

    public Optional<Setting> setting(String key) {
        return jdbc.sql("SELECT value, updated_at, updated_by FROM " + settings + " WHERE key = :key").param("key", key)
                .query((rs, n) -> new Setting(rs.getString("value"), rs.getObject("updated_at", OffsetDateTime.class),
                        rs.getObject("updated_by", Long.class)))
                .optional();
    }

    public void upsertSetting(String key, String value, long userId) {
        jdbc.sql("INSERT INTO " + settings + " (key, value, updated_by, updated_at) VALUES (:key, :value, :user, now()) "
                        + "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_by = EXCLUDED.updated_by, updated_at = now()")
                .param("key", key).param("value", value).param("user", userId).update();
    }

    public int deleteSetting(String key) {
        return jdbc.sql("DELETE FROM " + settings + " WHERE key = :key").param("key", key).update();
    }

    // ---- WhatsApp groups (tenant data) -------------------------------------------------------------------------------

    public Optional<String> itemGroupLink(long schoolId, String categoryCode) {
        return jdbc.sql("SELECT invite_url FROM " + itemLinks + " WHERE school_id = :school AND category_code = :category")
                .param("school", schoolId).param("category", categoryCode).query(String.class).optional();
    }

    public void upsertItemGroupLink(long schoolId, String categoryCode, String inviteUrl, long userId) {
        jdbc.sql("INSERT INTO " + itemLinks + " (school_id, category_code, invite_url, updated_by, updated_at) "
                        + "VALUES (:school, :category, :url, :user, now()) "
                        + "ON CONFLICT (school_id, category_code) DO UPDATE SET invite_url = EXCLUDED.invite_url, "
                        + "updated_by = EXCLUDED.updated_by, updated_at = now()")
                .param("school", schoolId).param("category", categoryCode).param("url", inviteUrl).param("user", userId).update();
    }

    public int deleteItemGroupLink(long schoolId, String categoryCode) {
        return jdbc.sql("DELETE FROM " + itemLinks + " WHERE school_id = :school AND category_code = :category")
                .param("school", schoolId).param("category", categoryCode).update();
    }

    /** The school-wide {@link #SCHOOL_GROUP} and {@link #INTERNAL_APPROVAL} links the school has, by target. */
    public Map<String, String> schoolGroupLinks(long schoolId) {
        Map<String, String> links = new LinkedHashMap<>();
        jdbc.sql("SELECT target_key, invite_url FROM " + schoolLinks + " WHERE school_id = :school "
                        + "AND target_key IN ('" + SCHOOL_GROUP + "', '" + INTERNAL_APPROVAL + "') ORDER BY target_key")
                .param("school", schoolId)
                .query((rs, n) -> Map.entry(rs.getString("target_key"), rs.getString("invite_url"))).list()
                .forEach(link -> links.put(link.getKey(), link.getValue()));
        return links;
    }

    // ---- the order's stages and history ------------------------------------------------------------------------------

    /** Who is assigned the order's stage, if anyone. */
    public Optional<Long> stepAssignee(long instanceId, int stepOrder) {
        return jdbc.sql("SELECT assignee_user_id FROM " + instanceSteps + " WHERE instance_id = :id AND step_order = :step")
                .param("id", instanceId).param("step", stepOrder)
                .query((rs, n) -> Optional.ofNullable(rs.getObject("assignee_user_id", Long.class))).optional()
                .flatMap(assignee -> assignee);
    }

    /** The order's last stage, for history recorded after every stage completed; 1 when it has none. */
    public int lastStepOrder(long instanceId) {
        return jdbc.sql("SELECT COALESCE(max(step_order), 1) FROM " + instanceSteps + " WHERE instance_id = :id")
                .param("id", instanceId).query(Integer.class).single();
    }

    /**
     * Records an order change in the history (design D40), as the workflow engine does: the actor, the request's
     * correlation id and, in {@code notes}, a JSON object of what changed (never the full values).
     */
    public void recordAction(long instanceId, long schoolId, int stepOrder, String action, String notes) {
        TenantContext actor = TenantContext.get();
        jdbc.sql("INSERT INTO " + actions + " (instance_id, step_order, action, actor_id, actor_email, notes, school_id, "
                        + "request_correlation_id, authority) VALUES (:id, :step, :action, :actorId, :email, :notes, :school, :request, 'SERVER')")
                .param("id", instanceId).param("step", stepOrder).param("action", action).param("actorId", actor.userId())
                .param("email", actor.email()).param("notes", notes).param("school", schoolId).param("request", correlationId())
                .update();
    }

    private static String correlationId() {
        String value = MDC.get("requestId");
        return value != null && value.matches("[A-Za-z0-9._:-]{1,128}") ? value : UUID.randomUUID().toString();
    }
}
