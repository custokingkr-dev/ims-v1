package com.custoking.ims.commandcenter.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record CommandCenterSummaryResponse(
        Long schoolId,
        String scope,
        OffsetDateTime generatedAt,
        List<KpiCard> kpis,
        List<CriticalAlert> criticalAlerts
) {
    public record KpiCard(
            String key,
            String label,
            String value,
            String delta,
            String status,
            String drilldownTarget,
            String drilldownFilter
    ) {}

    public record CriticalAlert(
            String title,
            String module,
            String severity
    ) {}
}
