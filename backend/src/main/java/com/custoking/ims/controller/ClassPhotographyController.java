package com.custoking.ims.controller;

import com.custoking.ims.commandcenter.ClassPhotographyService;
import com.custoking.ims.commandcenter.dto.ClassPhotographyPaymentStatusResponse;
import com.custoking.ims.commandcenter.dto.SendEventPaymentRemindersRequest;
import com.custoking.ims.commandcenter.dto.SendEventPaymentRemindersResult;
import com.custoking.ims.common.domain.PermissionConstants;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.context.TenantContext;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/dashboard")
public class ClassPhotographyController {

    private final ClassPhotographyService classPhotographyService;

    public ClassPhotographyController(ClassPhotographyService classPhotographyService) {
        this.classPhotographyService = classPhotographyService;
    }

    @GetMapping("/events/class-photography/payment-status")
    @PreAuthorize(PermissionConstants.EVENT_PAYMENT_VIEW)
    public ClassPhotographyPaymentStatusResponse getPaymentStatus(
            @RequestParam(required = false) String classId,
            @RequestParam(required = false) String sectionId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Long schoolId = TenantContext.get();
        return classPhotographyService.getPaymentStatus(schoolId, classId, sectionId, status, page, size);
    }

    @PostMapping("/events/{eventId}/payment-reminders")
    @PreAuthorize(PermissionConstants.EVENT_PAYMENT_NOTIFY_PARENT)
    public SendEventPaymentRemindersResult sendPaymentReminders(
            @PathVariable String eventId,
            @Valid @RequestBody SendEventPaymentRemindersRequest request,
            @AuthenticationPrincipal AuthUser actor) {

        Long schoolId = TenantContext.get();
        return classPhotographyService.sendPaymentReminders(schoolId, eventId, actor, request);
    }
}
