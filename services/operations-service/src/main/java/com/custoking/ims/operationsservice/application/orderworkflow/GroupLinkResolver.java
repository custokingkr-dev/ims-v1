package com.custoking.ims.operationsservice.application.orderworkflow;

import com.custoking.ims.operationsservice.application.orderworkflow.VendorLookup.VendorSummary;

import java.util.Collection;
import java.util.List;

/**
 * Resolves which WhatsApp group links an order shows (design D34). The system only opens these links; it sends nothing.
 * <ul>
 *   <li>School group: the item-level link for the order's school and category, else the school's own link, else none.</li>
 *   <li>Internal approval group: the school's own {@code INTERNAL_APPROVAL} link where one exists, else the global link,
 *       else none.</li>
 *   <li>Vendors: each selected vendor with its own group link.</li>
 * </ul>
 * The group-link service implements it.
 */
public interface GroupLinkResolver {

    /**
     * @param schoolId     the order's school
     * @param categoryCode the order's catalogue category (the key of item-level school links)
     * @param vendorIds    the order's selected vendors, in any category; inactive vendors are included
     */
    ResolvedGroups resolve(long schoolId, String categoryCode, Collection<Long> vendorIds);

    /** Where a resolved link came from. */
    enum Source { ITEM, SCHOOL, GLOBAL, NONE }

    /** One resolved link: {@code url} is null exactly when {@code source} is {@link Source#NONE}. */
    public record GroupLink(String url, Source source) {
        public GroupLink {
            if (source == null) throw new IllegalArgumentException("A group link needs its source");
            if ((url == null) != (source == Source.NONE)) {
                throw new IllegalArgumentException("A group link has a url exactly when its source is not NONE");
            }
        }

        public static GroupLink none() { return new GroupLink(null, Source.NONE); }
    }

    /** The links an order shows: the school group, the internal approval group and the selected vendors. */
    public record ResolvedGroups(GroupLink school, GroupLink internalApproval, List<VendorSummary> vendors) {
        public ResolvedGroups {
            school = school == null ? GroupLink.none() : school;
            internalApproval = internalApproval == null ? GroupLink.none() : internalApproval;
            vendors = vendors == null ? List.of() : List.copyOf(vendors);
        }
    }
}
