package com.custoking.ims.service;

import com.custoking.ims.context.TenantScope;
import com.custoking.ims.entity.AppUserEntity;
import com.custoking.ims.repo.ZoneAdminAssignmentRepository;
import com.custoking.ims.repo.ZoneSchoolMappingRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.custoking.ims.security.AppUserDetails;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class TenantScopeService {

    private final RbacService rbacService;
    private final ZoneAdminAssignmentRepository zoneAdminRepo;
    private final ZoneSchoolMappingRepository zoneMappingRepo;

    public TenantScopeService(RbacService rbacService,
                               ZoneAdminAssignmentRepository zoneAdminRepo,
                               ZoneSchoolMappingRepository zoneMappingRepo) {
        this.rbacService = rbacService;
        this.zoneAdminRepo = zoneAdminRepo;
        this.zoneMappingRepo = zoneMappingRepo;
    }

    public TenantScope buildScope(AppUserEntity user) {
        boolean isSuperadmin = "SUPERADMIN".equals(user.getRole());
        List<String> roleNames = rbacService.getUserRoleNames(user.getId());
        java.util.Set<String> permissions = rbacService.getUserPermissions(user.getId());

        List<Long> accessibleSchoolIds = new ArrayList<>();
        if ("ZONE_ADMIN".equals(user.getRole())) {
            zoneAdminRepo.findByUser_Id(user.getId()).forEach(zaa ->
                    zoneMappingRepo.findByZone_Id(zaa.getZone().getId()).forEach(zsm ->
                            accessibleSchoolIds.add(zsm.getSchool().getId())
                    )
            );
        }

        return new TenantScope(
                user.getId(),
                user.getRole(),
                Collections.unmodifiableList(roleNames),
                Collections.unmodifiableSet(permissions),
                user.getBranchId(),
                user.getBranchName(),
                user.getZoneId(),
                user.getZoneName(),
                Collections.unmodifiableList(accessibleSchoolIds),
                isSuperadmin
        );
    }

    public TenantScope getCurrentScope() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUserDetails details)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return buildScope(details.getUser());
    }

    public boolean canAccessSchool(Long schoolId) {
        TenantScope scope = getCurrentScope();
        if (scope.isSuperadmin()) return true;
        if (scope.schoolId() != null && scope.schoolId().equals(schoolId)) return true;
        return scope.accessibleSchoolIds().contains(schoolId);
    }

    public Long resolveRequestedSchoolId(Long requestedId) {
        TenantScope scope = getCurrentScope();
        if (scope.isSuperadmin()) return requestedId;
        if (!scope.accessibleSchoolIds().isEmpty()) {
            if (requestedId != null && scope.accessibleSchoolIds().contains(requestedId)) return requestedId;
            return scope.accessibleSchoolIds().isEmpty() ? null : scope.accessibleSchoolIds().get(0);
        }
        return scope.schoolId();
    }
}
