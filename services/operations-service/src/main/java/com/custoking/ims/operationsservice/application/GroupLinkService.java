package com.custoking.ims.operationsservice.application;

import com.custoking.ims.operationsservice.application.orderworkflow.AccessibleOrder;
import com.custoking.ims.operationsservice.application.orderworkflow.GroupLinkResolver;
import com.custoking.ims.operationsservice.application.orderworkflow.OrderAccess;
import com.custoking.ims.operationsservice.application.orderworkflow.VendorLookup;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.Role;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.WorkflowRuleViolation;
import com.custoking.ims.operationsservice.persistence.GroupLinkChangeRepository;
import com.custoking.ims.operationsservice.persistence.GroupLinkChangeRepository.Target;
import com.custoking.ims.operationsservice.persistence.VendorRepository;
import com.custoking.ims.operationsservice.persistence.WorkflowRequestScope;
import com.custoking.ims.operationsservice.persistence.WorkflowSettingsRepository;
import com.custoking.ims.operationsservice.persistence.WorkflowSettingsRepository.ItemGroupLinkRow;
import com.custoking.ims.operationsservice.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which WhatsApp group links an order shows, and how they are kept (design D34). The system only opens these links in a
 * new tab; it sends nothing.
 * <ul>
 *   <li><b>School group:</b> the school's group for the order's item (an operator creates one per item), else the school's
 *       own {@code SCHOOL_GROUP} link, else none.</li>
 *   <li><b>Internal approval group:</b> the school's own {@code INTERNAL_APPROVAL} link where one exists, else the global
 *       link superadmin sets, else none.</li>
 *   <li><b>Vendors:</b> each vendor the order selected, with its own group (inactive vendors included, so an order keeps
 *       what it chose).</li>
 * </ul>
 * The order workflow asks {@link #resolve} for its view; the order's own endpoints use {@link #orderGroups} and
 * {@link #setOrderSchoolGroup}. Workflow set-up keeps a school's own groups through {@link #setSchoolGroupLink}, lists and
 * edits its item groups through {@link #schoolItemGroupLinks} and {@link #setSchoolItemGroupLink}, and imports many links
 * at once through {@link #importGroupLinks}. Every real change, from any of them, is logged with who made it and the link
 * before and after ({@link GroupLinkChangeRepository}); saving the link already saved changes and logs nothing.
 */
@Service
public class GroupLinkService implements GroupLinkResolver {
    private static final Logger LOG = LoggerFactory.getLogger(GroupLinkService.class);
    /** The only origin an invite is stored with: the CHECKs of workflow V15 and V19 spell it exactly so. */
    static final String INVITE_ORIGIN = "https://chat.whatsapp.com";
    /** The refusal the screens show under an invite field (design §4.5). */
    public static final String INVALID_INVITE_MESSAGE = "Paste a WhatsApp group invite link (https://chat.whatsapp.com/…)";
    static final String GROUP_LINK_SET = "GROUP_LINK_SET";
    static final String STAFF_ONLY = "WhatsApp group links are for superadmin and operators only";
    /** An item is named by its catalogue category code, as V19's {@code ck_wsigl_category_code} requires. */
    static final Pattern ITEM_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,59}");
    /** The most rows one import may carry; a school network's links are far fewer. */
    public static final int MAX_IMPORT_ROWS = 2000;
    /** A school's own groups, by their workflow V15 target key. */
    public static final Set<String> SCHOOL_TARGETS = Set.of(WorkflowSettingsRepository.SCHOOL_GROUP,
            WorkflowSettingsRepository.INTERNAL_APPROVAL);

    private final WorkflowSettingsRepository settings;
    private final VendorRepository vendorRepository;
    private final VendorLookup vendors;
    private final OrderAccess access;
    private final WorkflowRequestScope scope;
    private final TransactionTemplate tx;
    private final GroupLinkChangeRepository changes;

    /**
     * {@code access} is lazy, so the bean that implements {@link OrderAccess} may itself depend on this resolver (the order
     * workflow builds its view from it) without a dependency cycle.
     */
    public GroupLinkService(WorkflowSettingsRepository settings, VendorRepository vendorRepository, @Lazy OrderAccess access,
                            WorkflowRequestScope scope, TransactionTemplate tx, GroupLinkChangeRepository changes) {
        this.settings = settings;
        this.vendorRepository = vendorRepository;
        this.vendors = vendorRepository;
        this.access = access;
        this.scope = scope;
        this.tx = tx;
        this.changes = changes;
    }

    // ---- GroupLinkResolver -------------------------------------------------------------------------------------------

    /**
     * Resolves the order's groups. Inside a transaction it reads with that transaction's scope (the caller has cleared the
     * order); outside one it opens its own and applies the caller's operator scope first.
     */
    @Override
    public ResolvedGroups resolve(long schoolId, String categoryCode, Collection<Long> vendorIds) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) return resolveInScope(schoolId, categoryCode, vendorIds);
        return tx.execute(status -> {
            scope.applyOperatorScope();
            return resolveInScope(schoolId, categoryCode, vendorIds);
        });
    }

    private ResolvedGroups resolveInScope(long schoolId, String categoryCode, Collection<Long> vendorIds) {
        Map<String, String> school = settings.schoolGroupLinks(schoolId);
        Optional<String> item = categoryCode == null ? Optional.empty() : settings.itemGroupLink(schoolId, categoryCode);
        GroupLink schoolGroup = item.map(url -> new GroupLink(url, Source.ITEM))
                .or(() -> Optional.ofNullable(school.get(WorkflowSettingsRepository.SCHOOL_GROUP)).map(url -> new GroupLink(url, Source.SCHOOL)))
                .orElse(GroupLink.none());
        GroupLink internal = Optional.ofNullable(school.get(WorkflowSettingsRepository.INTERNAL_APPROVAL))
                .map(url -> new GroupLink(url, Source.SCHOOL))
                .or(() -> settings.setting(WorkflowSettingsRepository.INTERNAL_APPROVAL_GROUP_URL)
                        .map(setting -> new GroupLink(setting.value(), Source.GLOBAL)))
                .orElse(GroupLink.none());
        List<VendorLookup.VendorSummary> selected = vendorIds == null || vendorIds.isEmpty() ? List.of() : vendors.vendors(vendorIds);
        return new ResolvedGroups(schoolGroup, internal, selected);
    }

    // ---- the order's endpoints ---------------------------------------------------------------------------------------

    /** The groups of an order the caller may see. */
    public ResolvedGroups orderGroups(String orderId) {
        return tx.execute(status -> {
            scope.applyOperatorScope();
            AccessibleOrder order = access.require(orderId, false);
            return resolveInScope(order.schoolId(), order.categoryCode(), vendorRepository.selectedVendorIds(order.instanceId()));
        });
    }

    /**
     * Saves, or with a blank link removes, the school's group for the order's item; the school-wide group then shows
     * again. Superadmin, or the operator assigned the order's current stage (operators create the item groups). The
     * change is recorded in the order's history as {@code GROUP_LINK_SET} and in the group link change log, with the
     * order's number; saving the link already saved changes nothing.
     *
     * @throws WorkflowRuleViolation {@code INVALID_INVITE} (400) for anything but a WhatsApp group invite, and
     *         {@code NOT_ASSIGNEE} (403) for an operator who is not assigned the current stage
     */
    public ResolvedGroups setOrderSchoolGroup(String orderId, String inviteUrl) {
        String clean = canonicalInvite(inviteUrl);
        return tx.execute(status -> {
            scope.applyOperatorScope();
            AccessibleOrder order = access.require(orderId, true);
            if (!order.superadmin()) requireCurrentAssignee(order);
            String category = order.categoryCode();
            changes.lockLinks();
            Optional<String> saved = settings.itemGroupLink(order.schoolId(), category);
            if (changes.record(Target.item(order.schoolId(), category), saved.orElse(null), clean, GroupLinkChangeRepository.Source.ORDER, order.orderId())) {
                if (clean == null) settings.deleteItemGroupLink(order.schoolId(), category);
                else settings.upsertItemGroupLink(order.schoolId(), category, clean, order.userId());
                int step = order.activeStep() != null ? order.activeStep() : settings.lastStepOrder(order.instanceId());
                settings.recordAction(order.instanceId(), order.schoolId(), step, GROUP_LINK_SET,
                        "{\"group\":\"SCHOOL\",\"level\":\"ITEM\",\"removed\":" + (clean == null) + "}");
            }
            return resolveInScope(order.schoolId(), category, vendorRepository.selectedVendorIds(order.instanceId()));
        });
    }

    private void requireCurrentAssignee(AccessibleOrder order) {
        Long assignee = order.activeStep() == null ? null : settings.stepAssignee(order.instanceId(), order.activeStep()).orElse(null);
        if (assignee == null || assignee != order.userId()) {
            throw new WorkflowRuleViolation(WorkflowRuleViolation.NOT_ASSIGNEE,
                    "Only the operator assigned the current stage, or a superadmin, can change this order's group link");
        }
    }

    // ---- a school's own groups (Workflow set-up) ---------------------------------------------------------------------

    /**
     * Superadmin only: sets, or with a blank link removes, one of the school's own groups ({@code SCHOOL_GROUP} or
     * {@code INTERNAL_APPROVAL}). Saving the link already saved changes nothing.
     *
     * @return the school's own groups after the change, by target key
     * @throws WorkflowRuleViolation {@code SUPERADMIN_ONLY} (403) and {@code INVALID_INVITE} (400)
     */
    public Map<String, String> setSchoolGroupLink(long schoolId, String targetKey, String inviteUrl) {
        requireSuperadmin("Only superadmin can maintain WhatsApp group links");
        String target = targetKey == null ? "" : targetKey.strip();
        if (!SCHOOL_TARGETS.contains(target)) throw bad("Choose the school group or the internal approval group");
        String clean = canonicalInvite(inviteUrl);
        requirePositiveSchool(schoolId);
        long userId = TenantContext.get().userId();
        return tx.execute(status -> {
            changes.lockLinks();
            String before = settings.schoolGroupLinks(schoolId).get(target);
            if (changes.record(Target.schoolGroup(schoolId, target), before, clean, GroupLinkChangeRepository.Source.SETUP, null)) {
                if (clean == null) settings.deleteSchoolGroupLink(schoolId, target);
                else settings.upsertSchoolGroupLink(schoolId, target, clean, userId);
            }
            return settings.schoolGroupLinks(schoolId);
        });
    }

    // ---- a school's item groups (Workflow set-up) --------------------------------------------------------------------

    /**
     * A school's group for one item: it overrides the school-wide group on every order of that item at the school.
     *
     * @param categoryCode the item's catalogue category code, such as {@code FLIERS}
     * @param inviteUrl    the {@code https://chat.whatsapp.com/...} invite
     * @param updatedAt    when it was last saved
     * @param updatedBy    who last saved it
     */
    public record ItemGroupLinkView(String categoryCode, String inviteUrl, OffsetDateTime updatedAt, Long updatedBy) {
        static ItemGroupLinkView of(ItemGroupLinkRow row) {
            return new ItemGroupLinkView(row.categoryCode(), row.inviteUrl(), row.updatedAt(), row.updatedBy());
        }
    }

    /** The school's item groups by item code: superadmin, or an operator assigned to the whole school (as its school-wide groups). */
    public List<ItemGroupLinkView> schoolItemGroupLinks(long schoolId) {
        return tx.execute(status -> {
            OrderWorkflowAccess.role(false, STAFF_ONLY);
            requirePositiveSchool(schoolId);
            TenantContext ctx = TenantContext.get();
            if (!ctx.isSuperAdmin() && !ctx.operatorSchools().contains(schoolId)) throw forbidden("No access to this school");
            scope.applyOperatorScope();
            return settings.itemGroupLinks(schoolId).stream().map(ItemGroupLinkView::of).toList();
        });
    }

    /**
     * Superadmin only: sets, or with a blank link removes, the school's group for one item. Unlike a save from an order,
     * this records no order history: it belongs to the school, not to one order. The group link change log records it;
     * saving the link already saved changes nothing.
     *
     * @throws WorkflowRuleViolation {@code SUPERADMIN_ONLY} (403) and {@code INVALID_INVITE} (400)
     */
    public List<ItemGroupLinkView> setSchoolItemGroupLink(long schoolId, String categoryCode, String inviteUrl) {
        requireSuperadmin("Only superadmin can maintain WhatsApp group links");
        requirePositiveSchool(schoolId);
        String category = itemCode(categoryCode);
        if (category == null) throw bad("Choose an item, such as FLIERS");
        String clean = canonicalInvite(inviteUrl);
        long userId = TenantContext.get().userId();
        return tx.execute(status -> {
            changes.lockLinks();
            String before = settings.itemGroupLink(schoolId, category).orElse(null);
            if (changes.record(Target.item(schoolId, category), before, clean, GroupLinkChangeRepository.Source.SETUP, null)) {
                if (clean == null) settings.deleteItemGroupLink(schoolId, category);
                else settings.upsertItemGroupLink(schoolId, category, clean, userId);
            }
            return settings.itemGroupLinks(schoolId).stream().map(ItemGroupLinkView::of).toList();
        });
    }

    // ---- the spreadsheet import --------------------------------------------------------------------------------------

    /** What a row of the import sets: one school-wide group, one item group at a school, or one vendor's group. */
    public enum ImportKind { SCHOOL_GROUP, INTERNAL_APPROVAL, ITEM, VENDOR }

    /** What happens to a row's link: the link it holds now against the one the row asks for. */
    public enum ImportOutcome { ADDED, CHANGED, REMOVED, UNCHANGED }

    /**
     * One link to set.
     *
     * @param line         the spreadsheet line it came from, so errors point at it; the row's position when absent
     * @param kind         {@link ImportKind}
     * @param schoolId     the school, for every kind but {@code VENDOR}
     * @param categoryCode the item, for {@code ITEM}
     * @param vendorId     the vendor, for {@code VENDOR}
     * @param inviteUrl    the invite to set; blank or null removes the link
     */
    public record ImportRow(Integer line, String kind, Long schoolId, String categoryCode, Long vendorId, String inviteUrl) { }

    /**
     * @param dryRun only work out what would change (the default); {@code false} applies it
     * @param rows   at most {@link #MAX_IMPORT_ROWS}
     */
    public record ImportRequest(Boolean dryRun, List<ImportRow> rows) { }

    /** A row that was understood, with the link it holds now ({@code before}) and the one it would hold ({@code after}). */
    public record ImportChange(int line, ImportKind kind, Long schoolId, String categoryCode, Long vendorId,
                               String before, String after, ImportOutcome outcome) { }

    /** A row that cannot be imported, and why. */
    public record ImportError(int line, String message) { }

    /**
     * The import's answer. {@code applied} is true only when this was not a dry run and every row was understood: one row
     * in error applies nothing, so a file is imported whole or not at all.
     */
    public record ImportResult(boolean dryRun, boolean applied, List<ImportChange> changes, List<ImportError> errors) { }

    /**
     * Superadmin only: sets many school, item and vendor groups at once, from a spreadsheet the set-up screen reads. Every
     * row is checked first and the file is applied in one transaction, all of it or (when any row is in error) none of it.
     * A blank link removes that group. School ids are not checked against the schools: the set-up screen sends only the
     * schools it lists, and the school endpoints accept any positive id in the same way. An applied file logs each row it
     * changes in the group link change log.
     *
     * @throws WorkflowRuleViolation {@code SUPERADMIN_ONLY} (403)
     */
    public ImportResult importGroupLinks(ImportRequest request) {
        requireSuperadmin("Only superadmin can import WhatsApp group links");
        if (request == null || request.rows() == null) throw bad("Send {\"rows\": [...]} with the links to import");
        if (request.rows().isEmpty()) throw bad("The file has no links to import");
        if (request.rows().size() > MAX_IMPORT_ROWS) throw bad("Import at most " + MAX_IMPORT_ROWS + " links at a time");
        boolean dryRun = !Boolean.FALSE.equals(request.dryRun());
        long userId = TenantContext.get().userId();
        return tx.execute(status -> {
            if (!dryRun) {
                // Before anything is read, so each row's "before" is still the saved link when the file is applied: the
                // group links first, then the vendor directory (the order every writer that needs both takes them in).
                changes.lockLinks();
                if (request.rows().stream().anyMatch(GroupLinkService::namesVendor)) vendorRepository.lockDirectory();
            }
            List<ImportChange> found = new ArrayList<>();
            List<ImportError> errors = new ArrayList<>();
            Map<String, Integer> seen = new HashMap<>();
            Map<Long, Map<String, String>> schoolLinks = new HashMap<>();
            for (int index = 0; index < request.rows().size(); index++) {
                ImportRow row = request.rows().get(index);
                int line = row != null && row.line() != null && row.line() > 0 ? row.line() : index + 1;
                try {
                    ImportChange change = understand(row, line, schoolLinks);
                    Integer first = seen.putIfAbsent(target(change), line);
                    if (first != null) throw bad("This sets the same group as line " + first);
                    found.add(change);
                } catch (WorkflowRuleViolation | ResponseStatusException refused) {
                    errors.add(new ImportError(line, refused instanceof ResponseStatusException r ? r.getReason() : refused.getMessage()));
                }
            }
            boolean apply = !dryRun && errors.isEmpty();
            if (apply) {
                found.stream().filter(c -> c.outcome() != ImportOutcome.UNCHANGED).forEach(c -> write(c, userId));
                LOG.info("WhatsApp group links imported by user {}: {} added, {} changed, {} removed, {} unchanged", userId,
                        count(found, ImportOutcome.ADDED), count(found, ImportOutcome.CHANGED),
                        count(found, ImportOutcome.REMOVED), count(found, ImportOutcome.UNCHANGED));
            }
            return new ImportResult(dryRun, apply, List.copyOf(found), List.copyOf(errors));
        });
    }

    /** The row checked and compared with what is saved now; refused with the reason the screen shows against its line. */
    private ImportChange understand(ImportRow row, int line, Map<Long, Map<String, String>> schoolLinks) {
        if (row == null) throw bad("This line is empty");
        ImportKind kind = importKind(row.kind());
        String after = canonicalInvite(row.inviteUrl());
        String before;
        Long schoolId = null;
        String category = null;
        Long vendorId = null;
        switch (kind) {
            case SCHOOL_GROUP, INTERNAL_APPROVAL -> {
                schoolId = requireRowSchool(row);
                before = schoolLinks.computeIfAbsent(schoolId, settings::schoolGroupLinks).get(kind.name());
            }
            case ITEM -> {
                schoolId = requireRowSchool(row);
                category = itemCode(row.categoryCode());
                if (category == null) throw bad("Give the item's code, such as FLIERS");
                before = settings.itemGroupLink(schoolId, category).orElse(null);
            }
            case VENDOR -> {
                if (row.vendorId() == null || row.vendorId() <= 0) throw bad("Give the vendor's id");
                vendorId = row.vendorId();
                before = vendorRepository.vendor(vendorId).orElseThrow(() -> bad("There is no vendor " + row.vendorId()))
                        .whatsappGroupUrl();
            }
            default -> throw new IllegalStateException("Unhandled import kind " + kind);
        }
        ImportOutcome outcome = Objects.equals(before, after) ? ImportOutcome.UNCHANGED
                : before == null ? ImportOutcome.ADDED : after == null ? ImportOutcome.REMOVED : ImportOutcome.CHANGED;
        return new ImportChange(line, kind, schoolId, category, vendorId, before, after, outcome);
    }

    /** Whether an import row is about a vendor's group, read as {@link #importKind} reads it. */
    private static boolean namesVendor(ImportRow row) {
        return row != null && row.kind() != null && ImportKind.VENDOR.name().equals(row.kind().strip().toUpperCase(Locale.ROOT));
    }

    private void write(ImportChange change, long userId) {
        changes.record(logTarget(change), change.before(), change.after(), GroupLinkChangeRepository.Source.IMPORT, null);
        switch (change.kind()) {
            case SCHOOL_GROUP, INTERNAL_APPROVAL -> {
                if (change.after() == null) settings.deleteSchoolGroupLink(change.schoolId(), change.kind().name());
                else settings.upsertSchoolGroupLink(change.schoolId(), change.kind().name(), change.after(), userId);
            }
            case ITEM -> {
                if (change.after() == null) settings.deleteItemGroupLink(change.schoolId(), change.categoryCode());
                else settings.upsertItemGroupLink(change.schoolId(), change.categoryCode(), change.after(), userId);
            }
            case VENDOR -> vendorRepository.updateGroupLink(change.vendorId(), change.after(), userId);
        }
    }

    private static Target logTarget(ImportChange change) {
        return switch (change.kind()) {
            case SCHOOL_GROUP, INTERNAL_APPROVAL -> Target.schoolGroup(change.schoolId(), change.kind().name());
            case ITEM -> Target.item(change.schoolId(), change.categoryCode());
            case VENDOR -> Target.vendor(change.vendorId());
        };
    }

    private static String target(ImportChange change) {
        return change.kind() + ":" + change.schoolId() + ":" + change.categoryCode() + ":" + change.vendorId();
    }

    private static long count(List<ImportChange> changes, ImportOutcome outcome) {
        return changes.stream().filter(change -> change.outcome() == outcome).count();
    }

    private static ImportKind importKind(String value) {
        String key = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        try {
            return ImportKind.valueOf(key);
        } catch (IllegalArgumentException unknown) {
            throw bad(key.isEmpty() ? "Say which group this is" : "Unknown group type " + value.strip());
        }
    }

    private static long requireRowSchool(ImportRow row) {
        if (row.schoolId() == null || row.schoolId() <= 0) throw bad("Give the school's id");
        return row.schoolId();
    }

    /** The item code as stored (upper case), or null when it is not one. */
    private static String itemCode(String value) {
        if (value == null) return null;
        String code = value.strip().toUpperCase(Locale.ROOT);
        return ITEM_CODE.matcher(code).matches() ? code : null;
    }

    private static void requireSuperadmin(String message) {
        if (OrderWorkflowAccess.role(true, STAFF_ONLY) != Role.SUPERADMIN) {
            throw new WorkflowRuleViolation(WorkflowRuleViolation.SUPERADMIN_ONLY, message);
        }
    }

    private static void requirePositiveSchool(long schoolId) {
        if (schoolId <= 0) throw bad("A positive schoolId is required");
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }

    // ---- invite links ------------------------------------------------------------------------------------------------

    /**
     * The one spelling an invite is stored with, {@code https://chat.whatsapp.com/<code>}, or null for blank text. The
     * scheme and host may be typed in any case and with an empty port, as browsers allow; the invite code (10 to 64
     * letters, digits, {@code _} or {@code -}) is kept exactly, since it is case-sensitive. Everything from the first
     * {@code ?} or {@code #} is dropped unread: WhatsApp's own "copy link" adds a query (such as {@code ?mode=ems_copy_t})
     * the invite does not need. The browser's {@code features/orders/shared/invite.ts} applies the same rule; it trims with
     * JavaScript's {@code trim()}, which also removes a no-break space this {@code strip()} keeps, and it only ever sends
     * the canonical link.
     *
     * @throws WorkflowRuleViolation {@code INVALID_INVITE} (400) for anything else
     */
    public static String canonicalInvite(String value) {
        if (value == null || value.isBlank()) return null;
        String clean = value.strip();
        if (clean.length() > 2048) throw invalidInvite();
        int tail = indexOfQueryOrFragment(clean);
        try {
            URI uri = new URI(tail < 0 ? clean : clean.substring(0, tail));
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"chat.whatsapp.com".equalsIgnoreCase(uri.getHost())
                    || uri.getPort() != -1 || uri.getRawUserInfo() != null
                    || uri.getRawPath() == null || !uri.getRawPath().matches("/[A-Za-z0-9_-]{10,64}")) {
                throw invalidInvite();
            }
            return INVITE_ORIGIN + uri.getRawPath();
        } catch (URISyntaxException malformed) {
            throw invalidInvite();
        }
    }

    private static int indexOfQueryOrFragment(String text) {
        int query = text.indexOf('?');
        int fragment = text.indexOf('#');
        return query < 0 ? fragment : fragment < 0 ? query : Math.min(query, fragment);
    }

    private static WorkflowRuleViolation invalidInvite() {
        return new WorkflowRuleViolation(WorkflowRuleViolation.INVALID_INVITE, INVALID_INVITE_MESSAGE);
    }
}
