package com.custoking.ims.commandcenter.dto;

import java.util.List;

public record DailyBriefResponse(
        String title,
        String summary,
        String focusArea,
        List<String> highlights,
        List<String> risks,
        String recommendedNextStep
) {}
