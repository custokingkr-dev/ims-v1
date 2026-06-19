package com.custoking.ims.controller;

import com.custoking.ims.commandcenter.ReorderPredictionService;
import com.custoking.ims.commandcenter.dto.ReorderSignalsResponse;
import com.custoking.ims.common.domain.PermissionConstants;
import com.custoking.ims.context.TenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard/reorder-signals")
public class ReorderPredictionController {

    private final ReorderPredictionService reorderPredictionService;

    public ReorderPredictionController(ReorderPredictionService reorderPredictionService) {
        this.reorderPredictionService = reorderPredictionService;
    }

    @GetMapping
    @PreAuthorize(PermissionConstants.ORDER_READ)
    public ReorderSignalsResponse getReorderSignals() {
        return reorderPredictionService.getSignals(TenantContext.get());
    }
}
