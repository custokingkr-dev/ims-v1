package com.custoking.ims.commandcenter;

/**
 * Structured metrics response for the dashboard command-center endpoint.
 * Amounts are in paise (smallest currency unit) — frontend converts to rupees.
 * Sections without data sources yet return zero values; later phases fill them.
 */
public record DashboardCommandCenterResponse(
        FeeSection fees,
        PhotographySection photography,
        LifecycleSection lifecycle,
        AttendanceSection attendance,
        VendorDuesSection vendorDues,
        ReorderSection reorderSignals
) {
    public record FeeSection(long defaulterCount, long totalOverdueAmountPaise, int oldestDueDays) {}
    public record PhotographySection(String eventId, long collectedAmount, long pendingAmount, long targetAmount) {}
    public record LifecycleSection(int pendingReviewCount, int longAbsenceCount) {}
    public record AttendanceSection(int sectionsBelowThresholdCount, int thresholdPercent) {}
    public record VendorDuesSection(long catalogOrderCount, long catalogOrderTotalPaise,
                                    long firefightingCount, long firefightingTotalPaise,
                                    long totalDuesPaise) {}
    public record ReorderSection(int alertCount) {}
}
