package com.custoking.ims.operationsservice.persistence;

import com.custoking.ims.operationsservice.application.orderworkflow.VendorLookup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * SQL for the global vendor directory (design D31-D33, workflow V19): the 18 SOP categories, the vendors and which
 * categories each belongs to. The directory tables are global like {@code workflow_designers}: no tenant key and no
 * row-level security, so these reads need no request scope. {@link #selectedVendorIds} is the exception: it reads an
 * order's tracker row, which row-level security guards, so it must run inside the caller's scoped transaction.
 *
 * <p>Also the {@link VendorLookup} the order workflow uses to validate selections and show the selected vendors.
 */
@Repository
public class VendorRepository implements VendorLookup {
    private final JdbcClient jdbc;
    private final String categories;
    private final String vendors;
    private final String members;
    private final String trackers;

    public VendorRepository(JdbcClient jdbc, @Value("${workflow.db.schema:workflow}") String schema) {
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid schema name");
        this.jdbc = jdbc;
        this.categories = schema + ".workflow_vendor_categories";
        this.vendors = schema + ".workflow_vendors";
        this.members = schema + ".workflow_vendor_category_members";
        this.trackers = schema + ".workflow_notebook_trackers";
    }

    // ---- rows -----------------------------------------------------------------------------------------------------

    /** A category with the number of its active vendors. */
    public record CategoryRow(String code, String name, int sortOrder, boolean active, long activeVendors,
                              OffsetDateTime updatedAt, Long updatedBy) { }

    /** A vendor with its category codes, in category sort order. */
    public record VendorRow(long id, String name, List<String> categories, String contactPerson, String phone, String altPhone,
                            String email, String city, String whatsappGroupUrl, String notes, boolean sample, boolean active,
                            OffsetDateTime updatedAt, Long updatedBy) {
        public VendorRow {
            categories = categories == null ? List.of() : List.copyOf(categories);
        }

        public VendorSummary summary() {
            return new VendorSummary(id, name, categories, contactPerson, phone, city, whatsappGroupUrl, sample, active);
        }
    }

    /** The stored fields of a vendor, already validated. */
    public record VendorFields(String name, String contactPerson, String phone, String altPhone, String email, String city,
                               String whatsappGroupUrl, String notes, boolean sample, boolean active) { }

    // ---- categories ------------------------------------------------------------------------------------------------

    public List<CategoryRow> categories() {
        return jdbc.sql(categorySelect() + " ORDER BY c.sort_order, c.code").query((rs, n) -> category(rs)).list();
    }

    public Optional<CategoryRow> category(String code) {
        return jdbc.sql(categorySelect() + " WHERE c.code = :code").param("code", code).query((rs, n) -> category(rs)).optional();
    }

    public int updateCategory(String code, String name, boolean active, int sortOrder, long userId) {
        return jdbc.sql("UPDATE " + categories + " SET name = :name, active = :active, sort_order = :sort, updated_by = :user, "
                        + "updated_at = now() WHERE code = :code")
                .param("code", code).param("name", name).param("active", active).param("sort", sortOrder).param("user", userId)
                .update();
    }

    private String categorySelect() {
        return "SELECT c.code, c.name, c.sort_order, c.active, c.updated_at, c.updated_by, "
                + "(SELECT count(*) FROM " + members + " m JOIN " + vendors + " v ON v.id = m.vendor_id "
                + "WHERE m.category_code = c.code AND v.active) AS active_vendors FROM " + categories + " c";
    }

    private static CategoryRow category(ResultSet rs) throws SQLException {
        return new CategoryRow(rs.getString("code"), rs.getString("name"), rs.getInt("sort_order"), rs.getBoolean("active"),
                rs.getLong("active_vendors"), rs.getObject("updated_at", OffsetDateTime.class), rs.getObject("updated_by", Long.class));
    }

    // ---- vendors ---------------------------------------------------------------------------------------------------

    /**
     * Vendors by name. {@code category} keeps those in that category; {@code query} is a case-insensitive substring of the
     * name, contact person, phone or city; inactive vendors are left out unless asked for.
     */
    public List<VendorRow> vendors(String category, String query, boolean includeInactive) {
        StringBuilder where = new StringBuilder(" WHERE true");
        if (!includeInactive) where.append(" AND v.active");
        if (category != null) {
            where.append(" AND EXISTS (SELECT 1 FROM ").append(members).append(" mc WHERE mc.vendor_id = v.id AND mc.category_code = :category)");
        }
        if (query != null) {
            where.append(" AND (lower(v.name) LIKE :q ESCAPE '\\' OR lower(coalesce(v.contact_person, '')) LIKE :q ESCAPE '\\'"
                    + " OR lower(coalesce(v.phone, '')) LIKE :q ESCAPE '\\' OR lower(coalesce(v.city, '')) LIKE :q ESCAPE '\\')");
        }
        JdbcClient.StatementSpec statement = jdbc.sql(vendorSelect() + where + " GROUP BY v.id ORDER BY lower(v.name), v.id");
        if (category != null) statement = statement.param("category", category);
        if (query != null) statement = statement.param("q", "%" + likeEscape(query.toLowerCase(Locale.ROOT)) + "%");
        return statement.query((rs, n) -> vendor(rs)).list();
    }

    public Optional<VendorRow> vendor(long id) {
        return jdbc.sql(vendorSelect() + " WHERE v.id = :id GROUP BY v.id").param("id", id).query((rs, n) -> vendor(rs)).optional();
    }

    public long insertVendor(VendorFields f, long userId) {
        return jdbc.sql("INSERT INTO " + vendors + " (name, contact_person, phone, alt_phone, email, city, whatsapp_group_url, notes, "
                        + "sample, active, created_by, updated_by) VALUES (:name, :contact, :phone, :alt, :email, :city, :url, :notes, "
                        + ":sample, :active, :user, :user) RETURNING id")
                .param("name", f.name()).param("contact", f.contactPerson()).param("phone", f.phone()).param("alt", f.altPhone())
                .param("email", f.email()).param("city", f.city()).param("url", f.whatsappGroupUrl()).param("notes", f.notes())
                .param("sample", f.sample()).param("active", f.active()).param("user", userId)
                .query(Long.class).single();
    }

    public int updateVendor(long id, VendorFields f, long userId) {
        return jdbc.sql("UPDATE " + vendors + " SET name = :name, contact_person = :contact, phone = :phone, alt_phone = :alt, "
                        + "email = :email, city = :city, whatsapp_group_url = :url, notes = :notes, sample = :sample, active = :active, "
                        + "updated_by = :user, updated_at = now() WHERE id = :id")
                .param("id", id).param("name", f.name()).param("contact", f.contactPerson()).param("phone", f.phone())
                .param("alt", f.altPhone()).param("email", f.email()).param("city", f.city()).param("url", f.whatsappGroupUrl())
                .param("notes", f.notes()).param("sample", f.sample()).param("active", f.active()).param("user", userId)
                .update();
    }

    /** Makes the vendor a member of exactly these categories. */
    public void replaceCategories(long vendorId, Collection<String> codes) {
        jdbc.sql("DELETE FROM " + members + " WHERE vendor_id = :id AND category_code NOT IN (:codes)")
                .param("id", vendorId).param("codes", List.copyOf(codes)).update();
        for (String code : codes) {
            jdbc.sql("INSERT INTO " + members + " (vendor_id, category_code) VALUES (:id, :code) ON CONFLICT DO NOTHING")
                    .param("id", vendorId).param("code", code).update();
        }
    }

    /**
     * Whether another vendor has the same name (ignoring case and surrounding spaces) and the same phone. Two branches of
     * one supplier keep different phones, so only the pair counts as a duplicate.
     */
    public boolean duplicateExists(String name, String phone, Long exceptId) {
        if (phone == null) return false;
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM " + vendors + " WHERE lower(btrim(name)) = lower(btrim(:name)) "
                        + "AND phone = :phone AND id <> :except)")
                .param("name", name).param("phone", phone).param("except", exceptId == null ? -1L : exceptId)
                .query(Boolean.class).single();
    }

    /**
     * Serialises directory writes for the rest of the transaction, so two superadmins adding the same vendor at once cannot
     * both pass the duplicate check (the table has no unique key on name and phone).
     */
    public void lockDirectory() {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('workflow.workflow_vendors'))").query((rs, n) -> 1).list();
    }

    private String vendorSelect() {
        return "SELECT v.id, v.name, v.contact_person, v.phone, v.alt_phone, v.email, v.city, v.whatsapp_group_url, v.notes, "
                + "v.sample, v.active, v.updated_at, v.updated_by, "
                + "array_remove(array_agg(m.category_code ORDER BY c.sort_order, m.category_code), NULL) AS category_codes "
                + "FROM " + vendors + " v LEFT JOIN " + members + " m ON m.vendor_id = v.id "
                + "LEFT JOIN " + categories + " c ON c.code = m.category_code";
    }

    private static VendorRow vendor(ResultSet rs) throws SQLException {
        return new VendorRow(rs.getLong("id"), rs.getString("name"), strings(rs.getArray("category_codes")),
                rs.getString("contact_person"), rs.getString("phone"), rs.getString("alt_phone"), rs.getString("email"),
                rs.getString("city"), rs.getString("whatsapp_group_url"), rs.getString("notes"), rs.getBoolean("sample"),
                rs.getBoolean("active"), rs.getObject("updated_at", OffsetDateTime.class), rs.getObject("updated_by", Long.class));
    }

    private static List<String> strings(Array array) throws SQLException {
        if (array == null) return List.of();
        Object[] values = (Object[]) array.getArray();
        return Arrays.stream(values).map(String::valueOf).toList();
    }

    /** Escapes LIKE's wildcards and its escape character, so a search for "50%" finds exactly that text. */
    static String likeEscape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // ---- the order's selections (tenant data) ------------------------------------------------------------------------

    /**
     * Every vendor id the order selected, in any category, without duplicates. Reads the tracker row under row-level
     * security: call it inside the transaction that cleared the order.
     */
    public List<Long> selectedVendorIds(long instanceId) {
        List<String> ids = jdbc.sql("SELECT DISTINCT item.value FROM " + trackers + " t "
                        + "CROSS JOIN LATERAL jsonb_each(t.vendor_selections) AS sel(key, value) "
                        + "CROSS JOIN LATERAL jsonb_array_elements_text(CASE WHEN jsonb_typeof(sel.value) = 'array' "
                        + "THEN sel.value ELSE '[]'::jsonb END) AS item(value) "
                        + "WHERE t.instance_id = :id")
                .param("id", instanceId).query(String.class).list();
        Set<Long> parsed = new LinkedHashSet<>();
        for (String id : ids) {
            try {
                parsed.add(Long.parseLong(id));
            } catch (NumberFormatException notAVendorId) {
                // V19 rewrote every selection to numeric ids; anything else selects no vendor
            }
        }
        return parsed.stream().sorted().toList();
    }

    // ---- VendorLookup ----------------------------------------------------------------------------------------------

    @Override
    public boolean inCategory(long vendorId, String category) {
        if (category == null) return false;
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM " + members + " WHERE vendor_id = :id AND category_code = :category)")
                .param("id", vendorId).param("category", category).query(Boolean.class).single();
    }

    @Override
    public List<VendorSummary> vendors(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<Long> wanted = new ArrayList<>(new LinkedHashSet<>(ids));
        wanted.removeIf(java.util.Objects::isNull);
        if (wanted.isEmpty()) return List.of();
        return jdbc.sql(vendorSelect() + " WHERE v.id IN (:ids) GROUP BY v.id ORDER BY lower(v.name), v.id")
                .param("ids", wanted).query((rs, n) -> vendor(rs).summary()).list();
    }

    @Override
    public Set<String> categoryCodes() {
        return new LinkedHashSet<>(jdbc.sql("SELECT code FROM " + categories + " ORDER BY sort_order, code").query(String.class).list());
    }

    @Override
    public Map<String, String> categoryNames() {
        Map<String, String> names = new LinkedHashMap<>();
        jdbc.sql("SELECT code, name FROM " + categories + " ORDER BY sort_order, code")
                .query(rs -> { names.put(rs.getString("code"), rs.getString("name")); });
        return names;
    }
}
