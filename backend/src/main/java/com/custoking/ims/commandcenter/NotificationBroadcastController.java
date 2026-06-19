package com.custoking.ims.commandcenter;

import com.custoking.ims.commandcenter.dto.BroadcastCreateRequest;
import com.custoking.ims.commandcenter.dto.BroadcastResponse;
import com.custoking.ims.commandcenter.dto.DeliveryStatusResponse;
import com.custoking.ims.common.domain.PermissionConstants;
import com.custoking.ims.context.TenantContext;
import com.custoking.ims.service.UserContextService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications/broadcasts")
@PreAuthorize(PermissionConstants.WORKSPACE_ACCESS)
public class NotificationBroadcastController {

    private final NotificationBroadcastService service;
    private final UserContextService userContext;

    public NotificationBroadcastController(NotificationBroadcastService service,
                                           UserContextService userContext) {
        this.service = service;
        this.userContext = userContext;
    }

    @GetMapping
    public List<BroadcastResponse> getAll(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        var actor = userContext.requireUser(auth);
        return service.getAll(actor, TenantContext.get());
    }

    @PostMapping
    public BroadcastResponse create(
            @RequestBody BroadcastCreateRequest req,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        var actor = userContext.requireUser(auth);
        return service.create(req, actor, TenantContext.get());
    }

    @PostMapping("/{id}/approve")
    public BroadcastResponse approve(
            @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return service.approve(id, userContext.requireUser(auth));
    }

    @PostMapping("/{id}/send")
    public BroadcastResponse send(
            @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return service.send(id, userContext.requireUser(auth));
    }

    @GetMapping("/{id}/delivery-status")
    public DeliveryStatusResponse deliveryStatus(
            @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        userContext.requireUser(auth);
        return service.getDeliveryStatus(id);
    }
}
