package com.custoking.ims.operationsservice.application.orderworkflow;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read access to the global vendor directory (design D31-D33) for the order workflow: validating per-order vendor
 * selections and showing the selected vendors on later stages. The vendor directory repository implements it.
 */
public interface VendorLookup {

    /**
     * Whether the vendor exists and belongs to the category, active or not. A selection may keep a vendor that has since
     * been deactivated (D32); whether a <em>newly</em> selected vendor must be active is the caller's rule, using
     * {@link VendorSummary#active()} from {@link #vendors}.
     */
    boolean inCategory(long vendorId, String category);

    /**
     * The vendors with these ids that exist, active or not, ordered by name. Unknown ids are left out, so a caller can
     * tell which ids are unknown by comparing.
     */
    List<VendorSummary> vendors(Collection<Long> ids);

    /** Every vendor category code, active or not (for example {@code BOARD_SUPPLIERS}). */
    Set<String> categoryCodes();

    /**
     * Every vendor category's current name by code, active or not. Superadmin may rename a category (D31), so a requirement
     * that names one, such as "Select a vendor in Binders", uses this name rather than the SOP's.
     */
    Map<String, String> categoryNames();

    /**
     * A vendor as stage cards and the call log show it.
     *
     * @param categories       the category codes the vendor belongs to
     * @param phone            required for real vendors; null only on sample vendors
     * @param whatsappGroupUrl the vendor's own WhatsApp group invite ({@code https://chat.whatsapp.com/...}), or null
     * @param sample           a seeded prototype vendor with no phone, shown as "Sample"
     * @param active           false for a deactivated vendor that an order selected before; shown as "Inactive"
     */
    public record VendorSummary(long id, String name, List<String> categories, String contactPerson, String phone, String city,
                                String whatsappGroupUrl, boolean sample, boolean active) {
        public VendorSummary {
            categories = categories == null ? List.of() : List.copyOf(categories);
        }
    }
}
