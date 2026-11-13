package com.custoking.ims.operationsservice.application;

import com.custoking.ims.operationsservice.application.orderworkflow.AccessibleOrder;
import com.custoking.ims.operationsservice.application.orderworkflow.GroupLinkResolver;
import com.custoking.ims.operationsservice.application.orderworkflow.OrderAccess;
import com.custoking.ims.operationsservice.application.orderworkflow.VendorLookup;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.WorkflowRuleViolation;
import com.custoking.ims.operationsservice.persistence.VendorRepository;
import com.custoking.ims.operationsservice.persistence.WorkflowRequestScope;
import com.custoking.ims.operationsservice.persistence.WorkflowSettingsRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Which WhatsApp group links an order shows, and the item-level school group an order's worker may save (design D34).
 * The system only opens these links in a new tab; it sends nothing.
 * <ul>
 *   <li><b>School group:</b> the school's group for the order's item (an operator creates one per item), else the school's
 *       own {@code SCHOOL_GROUP} link, else none.</li>
 *   <li><b>Internal approval group:</b> the school's own {@code INTERNAL_APPROVAL} link where one exists, else the global
 *       link superadmin sets, else none.</li>
 *   <li><b>Vendors:</b> each vendor the order selected, with its own group (inactive vendors included, so an order keeps
 *       what it chose).</li>
 * </ul>
 * The order workflow asks {@link #resolve} for its view; the order's own endpoints use {@link #orderGroups} and
 * {@link #setOrderSchoolGroup}.
 */
@Service
public class GroupLinkService implements GroupLinkResolver {
    /** The only origin an invite is stored with: the CHECKs of workflow V15 and V19 spell it exactly so. */
    static final String INVITE_ORIGIN = "https://chat.whatsapp.com";
    /** The refusal the screens show under an invite field (design §4.5). */
    public static final String INVALID_INVITE_MESSAGE = "Paste a WhatsApp group invite link (https://chat.whatsapp.com/…)";
    static final String GROUP_LINK_SET = "GROUP_LINK_SET";

    private final WorkflowSettingsRepository settings;
    private final VendorRepository vendorRepository;
    private final VendorLookup vendors;
    private final OrderAccess access;
    private final WorkflowRequestScope scope;
    private final TransactionTemplate tx;

    /**
     * {@code access} is lazy, so the bean that implements {@link OrderAccess} may itself depend on this resolver (the order
     * workflow builds its view from it) without a dependency cycle.
     */
    public GroupLinkService(WorkflowSettingsRepository settings, VendorRepository vendorRepository, @Lazy OrderAccess access,
                            WorkflowRequestScope scope, TransactionTemplate tx) {
        this.settings = settings;
        this.vendorRepository = vendorRepository;
        this.vendors = vendorRepository;
        this.access = access;
        this.scope = scope;
        this.tx = tx;
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
     * change is recorded in the order's history as {@code GROUP_LINK_SET}; saving the link already saved changes nothing.
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
            Optional<String> saved = settings.itemGroupLink(order.schoolId(), category);
            if (!Objects.equals(saved.orElse(null), clean)) {
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

    // ---- invite links ------------------------------------------------------------------------------------------------

    /**
     * The one spelling an invite is stored with, {@code https://chat.whatsapp.com/<code>}, or null for blank text. The
     * scheme and host may be typed in any case and with an empty port, as browsers allow; the invite code (10 to 64
     * letters, digits, {@code _} or {@code -}) is kept exactly, since it is case-sensitive. The browser's
     * {@code features/orders/shared/invite.ts} accepts exactly the same links.
     *
     * @throws WorkflowRuleViolation {@code INVALID_INVITE} (400) for anything else, including a query or fragment
     */
    public static String canonicalInvite(String value) {
        if (value == null || value.isBlank()) return null;
        String clean = value.strip();
        if (clean.length() > 2048) throw invalidInvite();
        try {
            URI uri = new URI(clean);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"chat.whatsapp.com".equalsIgnoreCase(uri.getHost())
                    || uri.getPort() != -1 || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getRawPath() == null || !uri.getRawPath().matches("/[A-Za-z0-9_-]{10,64}")) {
                throw invalidInvite();
            }
            return INVITE_ORIGIN + uri.getRawPath();
        } catch (URISyntaxException malformed) {
            throw invalidInvite();
        }
    }

    private static WorkflowRuleViolation invalidInvite() {
        return new WorkflowRuleViolation(WorkflowRuleViolation.INVALID_INVITE, INVALID_INVITE_MESSAGE);
    }
}
