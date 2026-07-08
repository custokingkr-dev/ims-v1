package com.custoking.ims.identityservice.persistence;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RbacLookupRepository extends Repository<AppUserEntity, Long> {

    // Display-only role names for AuthResponse.roles — intentionally NOT school-scoped; see
    // IdentityAuthService.responseFor. Permission-code and operator-school lookups live on
    // RbacReadRepository (the canonical repo for those queries).
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
}
