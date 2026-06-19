package com.custoking.ims.commandcenter.dto;

import java.util.List;

public record SendMeetingInvitesResult(
        int sentCount,
        int failedCount,
        List<Long> failedStudentIds
) {}
