package com.custoking.ims.controller;

import com.custoking.ims.commandcenter.FeeDefaulterService;
import com.custoking.ims.commandcenter.dto.FeeDefaulterListResponse;
import com.custoking.ims.commandcenter.dto.SendFeeRemindersRequest;
import com.custoking.ims.commandcenter.dto.SendFeeRemindersResult;
import com.custoking.ims.common.domain.PermissionConstants;
import com.custoking.ims.context.TenantContext;
import com.custoking.ims.service.UserContextService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/dashboard/finance")
public class FeeDefaulterController {

    private final FeeDefaulterService feeDefaulterService;
    private final UserContextService userContext;

    public FeeDefaulterController(FeeDefaulterService feeDefaulterService,
                                   UserContextService userContext) {
        this.feeDefaulterService = feeDefaulterService;
        this.userContext = userContext;
    }

    @GetMapping("/fee-defaulters")
    @PreAuthorize(PermissionConstants.FEE_READ)
    public FeeDefaulterListResponse listDefaulters(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(required = false) String classId,
            @RequestParam(required = false) String sectionId,
            @RequestParam(required = false) Integer daysOverdue,
            @RequestParam(required = false) String reminderStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        userContext.requireUser(auth);
        Long schoolId = TenantContext.get();
        return feeDefaulterService.listDefaulters(schoolId, classId, sectionId, daysOverdue, reminderStatus, page, size);
    }

    @PostMapping("/fee-defaulters/reminders")
    @PreAuthorize(PermissionConstants.FEE_NOTIFY_PARENT)
    public SendFeeRemindersResult sendReminders(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @Valid @RequestBody SendFeeRemindersRequest request) {
        var actor = userContext.requireUser(auth);
        Long schoolId = TenantContext.get();
        return feeDefaulterService.sendReminders(schoolId, actor, request);
    }
}
