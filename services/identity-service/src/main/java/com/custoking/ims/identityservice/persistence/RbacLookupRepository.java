package com.custoking.ims.identityservice.persistence;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface RbacLookupRepository extends Repository<AppUserEntity, Long> {

    @Query(value = """
            SELECT DISTINCT r.name
            FROM identity.user_role_assignments ura
            JOIN identity.roles r ON r.id = ura.role_id
            WHERE ura.user_id = :userId
              AND ura.active = true
              AND ura.revoked_at IS NULL
              AND (ura.valid_from IS NULL OR ura.valid_from <= now())
              AND (ura.valid_until IS NULL OR ura.valid_until >= now())
            ORDER BY r.name
            """, nativeQuery = true)
    List<String> roleNames(@Param("userId") Long userId);

    @Query(value = """
            SELECT DISTINCT p.code
            FROM identity.user_role_assignments ura
            JOIN identity.role_permissions rp ON rp.role_id = ura.role_id
            JOIN identity.permissions p ON p.id = rp.permission_id
            WHERE ura.user_id = :userId
              AND ura.active = true
              AND ura.revoked_at IS NULL
              AND (ura.valid_from IS NULL OR ura.valid_from <= now())
              AND (ura.valid_until IS NULL OR ura.valid_until >= now())
            """, nativeQuery = true)
    Set<String> permissionCodes(@Param("userId") Long userId);

    @Query(value = """
            SELECT DISTINCT ura.school_id
            FROM identity.user_role_assignments ura
            JOIN identity.roles r ON r.id = ura.role_id
            WHERE ura.user_id = :userId
              AND ura.active = true
              AND ura.revoked_at IS NULL
              AND ura.school_id IS NOT NULL
              AND (ura.valid_from IS NULL OR ura.valid_from <= now())
              AND (ura.valid_until IS NULL OR ura.valid_until >= now())
              AND UPPER(r.name) = 'OPERATIONS'
            ORDER BY ura.school_id
            """, nativeQuery = true)
    List<Long> operatorSchoolIds(@Param("userId") Long userId);
}
