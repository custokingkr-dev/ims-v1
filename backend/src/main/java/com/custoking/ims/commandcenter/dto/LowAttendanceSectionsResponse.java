package com.custoking.ims.commandcenter.dto;

import java.time.LocalDate;
import java.util.List;

public record LowAttendanceSectionsResponse(
        LocalDate date,
        int thresholdPercent,
        List<LowAttendanceSectionItem> sections
) {}
