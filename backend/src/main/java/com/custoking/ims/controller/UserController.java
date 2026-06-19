package com.custoking.ims.controller;

import com.custoking.ims.common.domain.PermissionConstants;
import com.custoking.ims.entity.AppUserEntity;
import com.custoking.ims.repo.AppUserRepository;
import com.custoking.ims.security.AppUserDetails;
import com.custoking.ims.service.RbacAuditService;
import com.custoking.ims.service.UserContextService;
import com.custoking.ims.service.WorkspaceService;
import com.custoking.ims.util.PasswordUtil;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize(PermissionConstants.USER_READ)
public class UserController {
    private final UserContextService userContext;
    private final WorkspaceService workspaceService;
    private final AppUserRepository userRepo;
    private final PasswordUtil passwordUtil;
    private final RbacAuditService rbacAuditService;

    public UserController(UserContextService userContext, WorkspaceService workspaceService,
                          AppUserRepository userRepo, PasswordUtil passwordUtil,
                          RbacAuditService rbacAuditService) {
        this.userContext = userContext;
        this.workspaceService = workspaceService;
        this.userRepo = userRepo;
        this.passwordUtil = passwordUtil;
        this.rbacAuditService = rbacAuditService;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        userContext.requireUser(authorization);
        return workspaceService.users();
    }

    /** Admin-triggered password reset for any user. Requires user:reset_password permission. */
    @PostMapping("/{userId}/password-reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(PermissionConstants.USER_RESET_PASSWORD)
    @Transactional
    public void resetPassword(@PathVariable Long userId,
                              @RequestBody Map<String, Object> body,
                              Authentication authentication) {
        Object pw = body.get("password");
        if (pw == null || pw.toString().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "password is required");
        }
        AppUserEntity target = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        target.setPasswordHash(passwordUtil.hash(pw.toString()));
        userRepo.save(target);
        Long actorId = resolveActorId(authentication);
        String actorEmail = resolveActorEmail(authentication);
        rbacAuditService.logPasswordReset(actorId, actorEmail, userId);
    }

    /** Soft-disables a user account. The user can no longer log in. Requires user:disable permission. */
    @PostMapping("/{userId}/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(PermissionConstants.USER_DISABLE)
    @Transactional
    public void disableUser(@PathVariable Long userId, Authentication authentication) {
        AppUserEntity target = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (target.isDisabled()) return;
        String actorEmail = resolveActorEmail(authentication);
        target.setDeletedAt(OffsetDateTime.now());
        target.setDeletedBy(actorEmail != null ? actorEmail : "system");
        userRepo.save(target);
        rbacAuditService.logUserDisabled(resolveActorId(authentication), actorEmail, userId);
    }

    /** Re-enables a previously disabled user account. Requires user:disable permission. */
    @PostMapping("/{userId}/enable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(PermissionConstants.USER_DISABLE)
    @Transactional
    public void enableUser(@PathVariable Long userId, Authentication authentication) {
        AppUserEntity target = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (!target.isDisabled()) return;
        target.setDeletedAt(null);
        target.setDeletedBy(null);
        userRepo.save(target);
        String actorEmail = resolveActorEmail(authentication);
        rbacAuditService.logUserEnabled(resolveActorId(authentication), actorEmail, userId);
    }

    private static Long resolveActorId(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof AppUserDetails d) return d.getUser().getId();
        return null;
    }

    private static String resolveActorEmail(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof AppUserDetails d) return d.getUser().getEmail();
        return null;
    }
}
