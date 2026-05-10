package com.custoking.ims.service;

import com.custoking.ims.entity.AppUserEntity;
import com.custoking.ims.entity.RoleEntity;
import com.custoking.ims.entity.UserRoleAssignmentEntity;
import com.custoking.ims.repo.RoleRepository;
import com.custoking.ims.repo.UserRoleAssignmentRepository;
import com.custoking.ims.security.AppUserDetails;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Central RBAC service. Bean name "rbacService" enables
 * @PreAuthorize("@rbacService.hasPermission(authentication, 'perm:code')") expressions.
 */
@Service("rbacService")
@Transactional(readOnly = true)
public class RbacService {

    private final UserRoleAssignmentRepository uraRepo;
    private final RoleRepository roleRepo;

    public RbacService(UserRoleAssignmentRepository uraRepo, RoleRepository roleRepo) {
        this.uraRepo = uraRepo;
        this.roleRepo = roleRepo;
    }

    // ── Spring Security SpEL entry point ─────────────────────────────────────

    public boolean hasPermission(Authentication authentication, String permissionCode) {
        if (authentication == null || !authentication.isAuthenticated()) return false;
        if (!(authentication.getPrincipal() instanceof AppUserDetails details)) return false;
        return userHasPermission(details.getUser().getId(), permissionCode);
    }

    // ── Core permission checks ────────────────────────────────────────────────

    public boolean userHasPermission(Long userId, String permissionCode) {
        return getUserPermissions(userId).contains(permissionCode);
    }

    public Set<String> getUserPermissions(Long userId) {
        return uraRepo.findByUser_IdWithPermissions(userId).stream()
                .flatMap(ura -> ura.getRole().getPermissions().stream())
                .map(p -> p.getCode())
                .collect(Collectors.toSet());
    }

    public List<String> getUserRoleNames(Long userId) {
        return uraRepo.findByUser_Id(userId).stream()
                .map(ura -> ura.getRole().getName())
                .collect(Collectors.toList());
    }

    // ── Role assignment ───────────────────────────────────────────────────────

    @Transactional
    public void assignRole(Long userId, String roleName, Long assignedBy) {
        if (uraRepo.existsByUser_IdAndRole_Name(userId, roleName)) return;
        RoleEntity role = roleRepo.findByName(roleName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown role: " + roleName));
        AppUserEntity userRef = new AppUserEntity();
        userRef.setId(userId);
        UserRoleAssignmentEntity ura = new UserRoleAssignmentEntity();
        ura.setUser(userRef);
        ura.setRole(role);
        ura.setAssignedBy(assignedBy);
        uraRepo.save(ura);
    }

    @Transactional
    public void revokeRole(Long userId, String roleName) {
        uraRepo.findByUser_Id(userId).stream()
                .filter(ura -> roleName.equals(ura.getRole().getName()))
                .findFirst()
                .ifPresent(uraRepo::delete);
    }

    @Transactional
    public void replaceRole(Long userId, String newRoleName, Long assignedBy) {
        uraRepo.deleteByUser_Id(userId);
        assignRole(userId, newRoleName, assignedBy);
    }
}
