package com.custoking.ims.operationsservice.persistence;

import com.custoking.ims.operationsservice.security.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * SQL for the WhatsApp group link change log (workflow V23): one append-only row for every real change of any group link,
 * with who made it, when, from where, and the link before and after. A row about a school's group is tenant data under
 * row-level security; a row about the global internal approval group or a vendor's group belongs to no school, so only
 * superadmin (whose connection bypasses row-level security) writes or reads it. Every write must run inside the
 * transaction that changed the link, so the link and its log row commit together.
 */
@Repository
public class GroupLinkChangeRepository {
    /** Which group changed. */
    public enum Kind { SCHOOL_GROUP, INTERNAL_APPROVAL, ITEM, GLOBAL_INTERNAL_APPROVAL, VENDOR }

    /** Where it was changed. */
    public enum Source { SETUP, ORDER, IMPORT, VENDOR_DIRECTORY }

    /**
     * The group a change is about, named exactly as V23's {@code ck_wglc_target} requires.
     *
     * @param schoolId     for a school's groups and item groups
     * @param categoryCode for an item group
     * @param vendorId     for a vendor's group
     */
    public record Target(Kind kind, Long schoolId, String categoryCode, Long vendorId) {
        public Target {
            Objects.requireNonNull(kind, "kind");
            boolean school = kind == Kind.SCHOOL_GROUP || kind == Kind.INTERNAL_APPROVAL || kind == Kind.ITEM;
            if (school != (schoolId != null) || (kind == Kind.ITEM) != (categoryCode != null)
                    || (kind == Kind.VENDOR) != (vendorId != null)) {
                throw new IllegalArgumentException("A " + kind + " change names " + (school ? "its school" : "no school")
                        + (kind == Kind.ITEM ? " and its item" : "") + (kind == Kind.VENDOR ? " and its vendor" : ""));
            }
        }

        /** One of a school's own groups, by its V15 target key ({@code SCHOOL_GROUP} or {@code INTERNAL_APPROVAL}). */
        public static Target schoolGroup(long schoolId, String targetKey) {
            return new Target(Kind.valueOf(targetKey), schoolId, null, null);
        }

        public static Target item(long schoolId, String categoryCode) {
            return new Target(Kind.ITEM, schoolId, categoryCode, null);
        }

        public static Target globalInternalApproval() {
            return new Target(Kind.GLOBAL_INTERNAL_APPROVAL, null, null, null);
        }

        public static Target vendor(long vendorId) {
            return new Target(Kind.VENDOR, null, null, vendorId);
        }
    }

    /**
     * A logged change, newest first in {@link #changes}.
     *
     * @param vendorName     the vendor's current name, for a vendor's group (null if the vendor row is gone)
     * @param before         the link before the change; null when the group was added
     * @param after          the link after it; null when the group was removed
     * @param orderId        the order's CK id (never the order number), when it was changed on the order
     * @param orderNumber    that order's SOP number as it is now (null before one is minted, and for other sources)
     * @param changedByEmail the email the person was signed in with, as the gateway passed it (may be null)
     */
    public record ChangeRow(long id, OffsetDateTime changedAt, Kind kind, Long schoolId, String categoryCode, Long vendorId,
                            String vendorName, String before, String after, Source source, String orderId, String orderNumber,
                            long changedBy, String changedByEmail) { }

    private final JdbcClient jdbc;
    private final String changes;
    private final String vendors;
    private final String trackers;

    public GroupLinkChangeRepository(JdbcClient jdbc, @Value("${workflow.db.schema:workflow}") String schema) {
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid schema name");
        this.jdbc = jdbc;
        this.changes = schema + ".workflow_group_link_changes";
        this.vendors = schema + ".workflow_vendors";
        this.trackers = schema + ".workflow_notebook_trackers";
    }

    /**
     * Serialises the school, item and global group link writers until the transaction ends, so the link a change reads as
     * "before" is still the saved one when it writes. The vendor directory serialises vendor writes with its own lock
     * ({@link VendorRepository#lockDirectory}); a writer that needs both takes this one first.
     */
    public void lockLinks() {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('workflow.group-links'))").query((rs, n) -> 1).single();
    }

    /**
     * Logs the change as made by the signed-in user, when there is one: the same link before and after is no change and
     * logs nothing.
     *
     * @param orderId the order's CK id for {@link Source#ORDER}, otherwise null
     * @return whether a row was written
     * @throws IllegalStateException when no user is signed in (every caller has already required one)
     */
    public boolean record(Target target, String before, String after, Source source, String orderId) {
        if (Objects.equals(before, after)) return false;
        TenantContext actor = TenantContext.get();
        if (actor.userId() == null) throw new IllegalStateException("A group link change needs the user who made it");
        jdbc.sql("INSERT INTO " + changes + " (kind, school_id, category_code, vendor_id, before_url, after_url, source, order_id, "
                        + "changed_by, changed_by_email, request_correlation_id) VALUES (:kind, :school, :category, :vendor, :before, "
                        + ":after, :source, :order, :user, :email, :request)")
                .param("kind", target.kind().name()).param("school", target.schoolId()).param("category", target.categoryCode())
                .param("vendor", target.vendorId()).param("before", before).param("after", after).param("source", source.name())
                .param("order", orderId).param("user", actor.userId()).param("email", actor.email())
                .param("request", WorkflowSettingsRepository.correlationId())
                .update();
        return true;
    }

    /**
     * Logged changes, newest first, that the caller's row-level-security scope admits.
     *
     * @param schoolId only that school's groups (its own groups and item groups) when given; every change when null
     * @param beforeId only changes older than this one (the previous page's last id) when given
     * @param limit    at most this many
     */
    public List<ChangeRow> changes(Long schoolId, Long beforeId, int limit) {
        // The optional filters are added only when given: a null parameter in ":p IS NULL" leaves PostgreSQL unable to
        // type it.
        StringBuilder sql = new StringBuilder("SELECT c.id, c.changed_at, c.kind, c.school_id, c.category_code, c.vendor_id, "
                + "v.name AS vendor_name, c.before_url, c.after_url, c.source, c.order_id, t.order_number, c.changed_by, "
                + "c.changed_by_email FROM " + changes + " c LEFT JOIN " + vendors + " v ON v.id = c.vendor_id LEFT JOIN " + trackers
                + " t ON t.order_id = c.order_id WHERE true");
        if (schoolId != null) sql.append(" AND c.school_id = :school");
        if (beforeId != null) sql.append(" AND c.id < :before");
        sql.append(" ORDER BY c.id DESC LIMIT :limit");
        JdbcClient.StatementSpec statement = jdbc.sql(sql.toString()).param("limit", limit);
        if (schoolId != null) statement = statement.param("school", schoolId);
        if (beforeId != null) statement = statement.param("before", beforeId);
        return statement.query((rs, n) -> new ChangeRow(rs.getLong("id"), rs.getObject("changed_at", OffsetDateTime.class),
                Kind.valueOf(rs.getString("kind")), rs.getObject("school_id", Long.class), rs.getString("category_code"),
                rs.getObject("vendor_id", Long.class), rs.getString("vendor_name"), rs.getString("before_url"),
                rs.getString("after_url"), Source.valueOf(rs.getString("source")), rs.getString("order_id"),
                rs.getString("order_number"), rs.getLong("changed_by"), rs.getString("changed_by_email"))).list();
    }
}
