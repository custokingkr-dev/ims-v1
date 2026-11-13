package com.custoking.ims.operationsservice.application;

import com.custoking.ims.operationsservice.application.orderworkflow.rules.WorkflowRuleViolation;
import com.custoking.ims.operationsservice.persistence.WorkflowSettingsRepository;
import com.custoking.ims.operationsservice.persistence.WorkflowSettingsRepository.Setting;
import com.custoking.ims.operationsservice.security.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;

/**
 * The order workflow's global settings (design D34, §4.7 Groups tab). Today that is one link: the internal approval
 * group every order shows on its approval stages, unless the order's school keeps its own {@code INTERNAL_APPROVAL}
 * group. Superadmin sets it; anyone who works orders may read it. The system only opens the link; it sends nothing.
 */
@Service
public class WorkflowSettingsService {
    private final WorkflowSettingsRepository repo;
    private final TransactionTemplate tx;

    public WorkflowSettingsService(WorkflowSettingsRepository repo, TransactionTemplate tx) {
        this.repo = repo;
        this.tx = tx;
    }

    /**
     * The global internal approval group.
     *
     * @param inviteUrl the {@code https://chat.whatsapp.com/...} invite, or null when none is set
     * @param updatedAt when it was last set, or null when none is set
     * @param updatedBy who last set it, or null when none is set
     */
    public record InternalApprovalGroupView(String inviteUrl, OffsetDateTime updatedAt, Long updatedBy) {
        static InternalApprovalGroupView of(Setting setting) {
            return setting == null ? new InternalApprovalGroupView(null, null, null)
                    : new InternalApprovalGroupView(setting.value(), setting.updatedAt(), setting.updatedBy());
        }
    }

    /** Superadmin, or an operator holding the order workflow's read permission. */
    public InternalApprovalGroupView internalApprovalGroup() {
        requireReader();
        return InternalApprovalGroupView.of(repo.setting(WorkflowSettingsRepository.INTERNAL_APPROVAL_GROUP_URL).orElse(null));
    }

    /**
     * Superadmin only. A blank or null link removes the group.
     *
     * @throws WorkflowRuleViolation {@code INVALID_INVITE} (400) for anything but a WhatsApp group invite
     */
    public InternalApprovalGroupView setInternalApprovalGroup(String inviteUrl) {
        TenantContext ctx = TenantContext.get();
        if (ctx.userId() == null) throw forbidden("Authenticated user required");
        if (!ctx.isSuperAdmin()) {
            throw new WorkflowRuleViolation(WorkflowRuleViolation.SUPERADMIN_ONLY, "Only superadmin can set the internal approval group");
        }
        String clean = GroupLinkService.canonicalInvite(inviteUrl);
        long userId = ctx.userId();
        return tx.execute(status -> {
            if (clean == null) repo.deleteSetting(WorkflowSettingsRepository.INTERNAL_APPROVAL_GROUP_URL);
            else repo.upsertSetting(WorkflowSettingsRepository.INTERNAL_APPROVAL_GROUP_URL, clean, userId);
            return InternalApprovalGroupView.of(repo.setting(WorkflowSettingsRepository.INTERNAL_APPROVAL_GROUP_URL).orElse(null));
        });
    }

    private static void requireReader() {
        TenantContext ctx = TenantContext.get();
        if (ctx.userId() == null) throw forbidden("Authenticated user required");
        if (ctx.isSuperAdmin()) return;
        if (!ctx.isOperations()) throw forbidden("Workflow settings are for superadmin and operators only");
        if (!ctx.hasPermission("notebook-tracker:read")) throw forbidden("permission required: notebook-tracker:read");
    }

    private static ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
}
