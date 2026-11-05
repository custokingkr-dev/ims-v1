package com.custoking.ims.operationsservice.application.tracker;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fixed vendor choices provided by the client prototype. Selections themselves are stored per order. */
public final class VendorDirectory {
    public static final Map<String, List<Vendor>> CATEGORIES = Map.of(
            "board", List.of(
                    new Vendor("board_shree", "Shree Board Traders"),
                    new Vendor("board_national", "National Boards Co."),
                    new Vendor("board_prime", "Prime Board Suppliers")),
            "lamination", List.of(
                    new Vendor("lam_glosspro", "GlossPro Laminations"),
                    new Vendor("lam_crystal", "Crystal Lamination Works"),
                    new Vendor("lam_brightfinish", "BrightFinish Laminators")),
            "binder", List.of(
                    new Vendor("binder_perfect", "Perfect Bindery"),
                    new Vendor("binder_classic", "Classic Notebook Binding"),
                    new Vendor("binder_metro", "Metro Binding House")),
            "offset", List.of(
                    new Vendor("offset_metro", "Metro Offset Printers"),
                    new Vendor("offset_precision", "Precision Print House"),
                    new Vendor("offset_cityline", "Cityline Offset Works")));

    private VendorDirectory() { }

    public static Set<String> vendorIds(String category) {
        return CATEGORIES.getOrDefault(category, List.of()).stream().map(Vendor::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public record Vendor(String id, String name) { }
}
