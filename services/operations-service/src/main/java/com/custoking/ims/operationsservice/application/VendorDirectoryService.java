package com.custoking.ims.operationsservice.application;

import com.custoking.ims.operationsservice.application.orderworkflow.rules.WorkflowRuleViolation;
import com.custoking.ims.operationsservice.persistence.VendorRepository;
import com.custoking.ims.operationsservice.persistence.VendorRepository.CategoryRow;
import com.custoking.ims.operationsservice.persistence.VendorRepository.VendorFields;
import com.custoking.ims.operationsservice.persistence.VendorRepository.VendorRow;
import com.custoking.ims.operationsservice.security.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The vendor directory (design D31, D32, §4.5): the SOP's 18 vendor categories and Custoking's suppliers in them, global
 * rather than per school. Superadmin maintains it; operators read the active vendors, which the order's stage cards
 * show. Vendors are deactivated, never deleted, so an order that selected one keeps it, shown as "Inactive". Admin edits
 * keep only who changed a row and when (D40); there is no admin audit trail.
 */
@Service
public class VendorDirectoryService {
    static final int NAME_MAX = 200;
    static final int CONTACT_MAX = 200;
    static final int PHONE_MAX = 32;
    static final int EMAIL_MAX = 254;
    static final int CITY_MAX = 100;
    static final int NOTES_MAX = 500;
    static final int CATEGORY_NAME_MAX = 100;
    static final int SORT_ORDER_MAX = 9_999;
    static final int QUERY_MAX = 100;

    private static final Pattern CATEGORY_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,39}");
    /** Digits with the separators people type: spaces, dots, dashes, brackets and a leading plus. */
    private static final Pattern PHONE = Pattern.compile("\\+?[0-9 ().-]+");
    /** local@domain.tld. The domain's labels exclude the dot, so no two parts can claim the same characters (no ReDoS). */
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s.]+(?:\\.[^@\\s.]+)+");
    private static final String SUPERADMIN_ONLY = "Only superadmin can maintain the vendor directory";

    private final VendorRepository repo;
    private final TransactionTemplate tx;

    public VendorDirectoryService(VendorRepository repo, TransactionTemplate tx) {
        this.repo = repo;
        this.tx = tx;
    }

    /**
     * A vendor category as the directory screen lists it.
     *
     * @param vendorCount the category's active vendors
     */
    public record VendorCategoryView(String code, String name, int sortOrder, boolean active, long vendorCount,
                                     OffsetDateTime updatedAt, Long updatedBy) {
        static VendorCategoryView of(CategoryRow row) {
            return new VendorCategoryView(row.code(), row.name(), row.sortOrder(), row.active(), row.activeVendors(),
                    row.updatedAt(), row.updatedBy());
        }
    }

    /**
     * A vendor with every directory field.
     *
     * @param phone     null only on the seeded sample vendors
     * @param sample    a seeded prototype vendor, shown as "Sample"
     * @param updatedBy null on seeded rows
     */
    public record VendorView(long id, String name, List<String> categories, String contactPerson, String phone, String altPhone,
                             String email, String city, String whatsappGroupUrl, String notes, boolean sample, boolean active,
                             OffsetDateTime updatedAt, Long updatedBy) {
        static VendorView of(VendorRow row) {
            return new VendorView(row.id(), row.name(), row.categories(), row.contactPerson(), row.phone(), row.altPhone(),
                    row.email(), row.city(), row.whatsappGroupUrl(), row.notes(), row.sample(), row.active(), row.updatedAt(),
                    row.updatedBy());
        }
    }

    /** What superadmin sends to add or edit a vendor; {@code active} null keeps a vendor's state (new vendors are active). */
    public record VendorInput(String name, List<String> categories, String contactPerson, String phone, String altPhone,
                              String email, String city, String whatsappGroupUrl, String notes, Boolean active) { }

    /** A category change; each null keeps that part as it is. */
    public record CategoryChange(String name, Boolean active, Integer sortOrder) { }

    // ---- categories ------------------------------------------------------------------------------------------------

    /** Every category in sort order, inactive ones included (the screens grey them out). */
    public List<VendorCategoryView> categories() {
        requireReader();
        return repo.categories().stream().map(VendorCategoryView::of).toList();
    }

    /** Superadmin: rename (a label only; the code is stable), reorder, deactivate or activate a category. */
    public VendorCategoryView updateCategory(String code, CategoryChange change) {
        long userId = requireSuperadmin();
        if (code == null || !CATEGORY_CODE.matcher(code).matches()) throw notFound("Vendor category not found");
        if (change == null) throw bad("Send the category's name, active state or position");
        String name = change.name() == null ? null : change.name().strip();
        if (name != null && (name.isEmpty() || name.length() > CATEGORY_NAME_MAX)) {
            throw bad("A category name needs 1 to " + CATEGORY_NAME_MAX + " characters");
        }
        Integer sortOrder = change.sortOrder();
        if (sortOrder != null && (sortOrder < 1 || sortOrder > SORT_ORDER_MAX)) {
            throw bad("A category position is a whole number from 1 to " + SORT_ORDER_MAX);
        }
        return tx.execute(status -> {
            CategoryRow current = repo.category(code).orElseThrow(() -> notFound("Vendor category not found"));
            repo.updateCategory(code, name == null ? current.name() : name,
                    change.active() == null ? current.active() : change.active(),
                    sortOrder == null ? current.sortOrder() : sortOrder, userId);
            return VendorCategoryView.of(repo.category(code).orElseThrow());
        });
    }

    // ---- vendors ---------------------------------------------------------------------------------------------------

    /**
     * Vendors by name. Operators always get the active vendors only; superadmin gets inactive ones too when asked.
     *
     * @param category only vendors in this category (null for every category)
     * @param query    a case-insensitive substring of the name, contact person, phone or city (blank for none)
     */
    public List<VendorView> vendors(String category, String query, boolean includeInactive) {
        boolean superadmin = requireReader();
        String cleanCategory = category == null || category.isBlank() ? null : category.strip();
        if (cleanCategory != null && !CATEGORY_CODE.matcher(cleanCategory).matches()) throw bad("Unknown vendor category " + cleanCategory);
        String cleanQuery = query == null || query.isBlank() ? null : query.strip();
        if (cleanQuery != null && cleanQuery.length() > QUERY_MAX) throw bad("A search is limited to " + QUERY_MAX + " characters");
        return repo.vendors(cleanCategory, cleanQuery, superadmin && includeInactive).stream().map(VendorView::of).toList();
    }

    /** Superadmin: adds a vendor. A phone is required: only the seeded sample vendors have none. */
    public VendorView create(VendorInput input) {
        long userId = requireSuperadmin();
        Validated v = validate(input, false, input == null || input.active() == null || input.active());
        return tx.execute(status -> {
            repo.lockDirectory();
            requireCategoriesExist(v.categories());
            if (repo.duplicateExists(v.fields().name(), v.fields().phone(), null)) throw duplicate();
            long id = repo.insertVendor(v.fields(), userId);
            repo.replaceCategories(id, v.categories());
            return VendorView.of(repo.vendor(id).orElseThrow());
        });
    }

    /**
     * Superadmin: replaces every field of a vendor. A sample vendor stays "Sample" only while it has no phone: giving it a
     * phone makes it a real vendor. Deactivating keeps it on the orders that selected it.
     */
    public VendorView update(long id, VendorInput input) {
        long userId = requireSuperadmin();
        if (input == null) throw bad("Send the vendor's details");
        return tx.execute(status -> {
            repo.lockDirectory();
            VendorRow current = repo.vendor(id).orElseThrow(() -> notFound("Vendor not found"));
            boolean active = input.active() == null ? current.active() : input.active();
            Validated v = validate(input, current.sample(), active);
            requireCategoriesExist(v.categories());
            if (repo.duplicateExists(v.fields().name(), v.fields().phone(), id)) throw duplicate();
            repo.updateVendor(id, v.fields(), userId);
            repo.replaceCategories(id, v.categories());
            return VendorView.of(repo.vendor(id).orElseThrow());
        });
    }

    // ---- validation --------------------------------------------------------------------------------------------------

    private record Validated(VendorFields fields, List<String> categories) { }

    /**
     * @param wasSample whether the vendor is a seeded sample vendor (false for a new one)
     * @param active    the vendor's active state after the change
     */
    private static Validated validate(VendorInput input, boolean wasSample, boolean active) {
        if (input == null) throw bad("Send the vendor's details");
        String name = required(input.name(), NAME_MAX, "Enter the vendor's name");
        List<String> categories = categories(input.categories());
        String contact = optional(input.contactPerson(), CONTACT_MAX, "The contact person");
        String phone = phone(input.phone(), "The phone number");
        String altPhone = phone(input.altPhone(), "The alternate phone number");
        String email = optional(input.email(), EMAIL_MAX, "The email address");
        if (email != null && !EMAIL.matcher(email).matches()) throw bad("Enter a valid email address");
        String city = optional(input.city(), CITY_MAX, "The city");
        String invite = GroupLinkService.canonicalInvite(input.whatsappGroupUrl());
        String notes = optional(input.notes(), NOTES_MAX, "Notes");
        boolean sample = wasSample && phone == null;
        if (phone == null && !sample) throw bad("Enter the vendor's phone number");
        return new Validated(new VendorFields(name, contact, phone, altPhone, email, city, invite, notes, sample, active), categories);
    }

    private static List<String> categories(List<String> codes) {
        if (codes == null || codes.isEmpty()) throw bad("Choose at least one vendor category");
        Set<String> clean = new LinkedHashSet<>();
        for (String code : codes) {
            String c = code == null ? "" : code.strip();
            if (!CATEGORY_CODE.matcher(c).matches()) throw bad("Unknown vendor category " + (c.isEmpty() ? "(blank)" : c));
            clean.add(c);
        }
        return List.copyOf(clean);
    }

    private void requireCategoriesExist(List<String> codes) {
        Set<String> known = repo.categoryCodes();
        for (String code : codes) if (!known.contains(code)) throw bad("Unknown vendor category " + code);
    }

    private static String required(String value, int max, String missing) {
        String clean = value == null ? "" : value.strip();
        if (clean.isEmpty()) throw bad(missing);
        if (clean.length() > max) throw bad(missing + " (at most " + max + " characters)");
        return clean;
    }

    private static String optional(String value, int max, String what) {
        if (value == null || value.isBlank()) return null;
        String clean = value.strip();
        if (clean.length() > max) throw bad(what + " is limited to " + max + " characters");
        return clean;
    }

    /** A phone people can dial: at most 32 characters, digits and the usual separators, and at least six digits. */
    static String phone(String value, String what) {
        String clean = optional(value, PHONE_MAX, what);
        if (clean == null) return null;
        long digits = clean.chars().filter(Character::isDigit).count();
        if (!PHONE.matcher(clean).matches() || digits < 6) throw bad(what + " must be a phone number, such as +91 98765 43210");
        return clean;
    }

    // ---- who may do what ---------------------------------------------------------------------------------------------

    /** Superadmin, or an operator holding the order workflow's read permission; answers whether the caller is superadmin. */
    private static boolean requireReader() {
        TenantContext ctx = TenantContext.get();
        if (ctx.userId() == null) throw forbidden("Authenticated user required");
        if (ctx.isSuperAdmin()) return true;
        if (!ctx.isOperations()) throw forbidden("The vendor directory is for superadmin and operators only");
        if (!ctx.hasPermission("notebook-tracker:read")) throw forbidden("permission required: notebook-tracker:read");
        return false;
    }

    private static long requireSuperadmin() {
        TenantContext ctx = TenantContext.get();
        if (ctx.userId() == null) throw forbidden("Authenticated user required");
        if (!ctx.isSuperAdmin()) throw new WorkflowRuleViolation(WorkflowRuleViolation.SUPERADMIN_ONLY, SUPERADMIN_ONLY);
        return ctx.userId();
    }

    private static ResponseStatusException duplicate() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "A vendor with this name and phone already exists");
    }

    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
    private static ResponseStatusException forbidden(String message) { return new ResponseStatusException(HttpStatus.FORBIDDEN, message); }
}
