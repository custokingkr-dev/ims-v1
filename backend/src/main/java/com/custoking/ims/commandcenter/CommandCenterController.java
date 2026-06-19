package com.custoking.ims.commandcenter;

import com.custoking.ims.commandcenter.dto.*;
import com.custoking.ims.common.domain.PermissionConstants;
import com.custoking.ims.context.TenantContext;
import com.custoking.ims.service.UserContextService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/command-centre")
@PreAuthorize(PermissionConstants.WORKSPACE_ACCESS)
public class CommandCenterController {

    private final CommandCenterService service;
    private final UserContextService userContext;

    public CommandCenterController(CommandCenterService service, UserContextService userContext) {
        this.service = service;
        this.userContext = userContext;
    }

    @GetMapping("/summary")
    public CommandCenterSummaryResponse getSummary(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        var actor = userContext.requireUser(auth);
        return service.getSummary(actor, TenantContext.get());
    }

    @GetMapping("/actions")
    public List<CommandCenterActionResponse> getActions(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        var actor = userContext.requireUser(auth);
        return service.getActions(actor, TenantContext.get());
    }

    @PostMapping("/actions/{id}/accept")
    public CommandCenterActionResponse accept(
            @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return service.acceptAction(id, userContext.requireUser(auth));
    }

    @PostMapping("/actions/{id}/dismiss")
    public CommandCenterActionResponse dismiss(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String reason = body != null ? body.getOrDefault("reason", null) : null;
        return service.dismissAction(id, reason, userContext.requireUser(auth));
    }

    @GetMapping("/feed")
    public List<CommandCenterFeedItemResponse> getFeed(
            @RequestParam(defaultValue = "20") int limit,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        var actor = userContext.requireUser(auth);
        return service.getFeed(actor, TenantContext.get(), limit);
    }

    @GetMapping("/brief")
    public DailyBriefResponse getDailyBrief(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        var actor = userContext.requireUser(auth);
        return service.getDailyBrief(actor, TenantContext.get());
    }
}
