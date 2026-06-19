package com.custoking.ims.controller;

import com.custoking.ims.audit.AuditLogEntity;
import com.custoking.ims.audit.AuditLogRepository;
import com.custoking.ims.common.domain.PermissionConstants;
import com.custoking.ims.context.TenantContext;
import com.custoking.ims.service.UserContextService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Read-only audit log endpoint.
 * SUPERADMIN/ZONE_ADMIN can query across schools; ADMIN/ACCOUNTANT scoped to own school.
 */
@RestController
@RequestMapping("/api/v1/audit-logs")
@PreAuthorize(PermissionConstants.AUDIT_READ)
public class AuditLogController {

    private final AuditLogRepository auditRepo;
    private final UserContextService userContext;

    public AuditLogController(AuditLogRepository auditRepo, UserContextService userContext) {
        this.auditRepo = auditRepo;
        this.userContext = userContext;
    }

    @GetMapping
    public Map<String, Object> query(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0")   int page,
            @RequestParam(defaultValue = "50")  int size) {

        userContext.requireUser(authorization);

        // Platform admins can query across all schools; school-scoped users see their own school only.
        // School context comes from RBAC-derived TenantContext (set by TenantResolverFilter), not branchId.
        if (!userContext.isPlatformAdmin()) {
            Long actorSchool = TenantContext.get();
            if (actorSchool == null)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No school context for this user");
            if (schoolId != null && !schoolId.equals(actorSchool))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot query another school's audit log");
            schoolId = actorSchool;
        }

        int safeSize = Math.min(size, 200);
        PageRequest pageable = PageRequest.of(page, safeSize, Sort.by("timestamp").descending());

        Page<AuditLogEntity> result = auditRepo.search(schoolId, userId, action, from, to, pageable);

        return Map.of(
                "content",       result.getContent(),
                "totalElements", result.getTotalElements(),
                "totalPages",    result.getTotalPages(),
                "page",          result.getNumber(),
                "size",          result.getSize()
        );
    }
}
