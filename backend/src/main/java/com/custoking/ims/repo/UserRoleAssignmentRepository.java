package com.custoking.ims.repo;

import com.custoking.ims.entity.UserRoleAssignmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserRoleAssignmentRepository extends JpaRepository<UserRoleAssignmentEntity, Long> {

    List<UserRoleAssignmentEntity> findByUser_Id(Long userId);

    List<UserRoleAssignmentEntity> findByUser_IdAndActive(Long userId, boolean active);

    boolean existsByUser_IdAndRole_Name(Long userId, String roleName);

    void deleteByUser_Id(Long userId);

    @Query("""
            SELECT ura FROM UserRoleAssignmentEntity ura
            JOIN FETCH ura.role r JOIN FETCH r.permissions
            WHERE ura.user.id = :userId AND ura.active = true
              AND ura.revokedAt IS NULL
              AND (ura.validFrom IS NULL OR ura.validFrom <= CURRENT_TIMESTAMP)
              AND (ura.validUntil IS NULL OR ura.validUntil > CURRENT_TIMESTAMP)
            """)
    List<UserRoleAssignmentEntity> findByUser_IdWithPermissions(@Param("userId") Long userId);

    @Query("""
            SELECT CASE WHEN COUNT(ura) > 0 THEN true ELSE false END
            FROM UserRoleAssignmentEntity ura
            WHERE ura.user.id = :userId AND ura.role.name = :roleName AND ura.active = true
              AND ura.revokedAt IS NULL
              AND ((:schoolId IS NULL AND ura.schoolId IS NULL) OR ura.schoolId = :schoolId)
              AND ((:zoneId IS NULL AND ura.zoneId IS NULL) OR ura.zoneId = :zoneId)
              AND (ura.validFrom IS NULL OR ura.validFrom <= CURRENT_TIMESTAMP)
              AND (ura.validUntil IS NULL OR ura.validUntil > CURRENT_TIMESTAMP)
            """)
    boolean existsActiveAssignment(@Param("userId") Long userId,
                                   @Param("roleName") String roleName,
                                   @Param("schoolId") Long schoolId,
                                   @Param("zoneId") Long zoneId);

    /** True if ANY user holds an active, effective assignment with the given role for the school. */
    @Query("""
            SELECT CASE WHEN COUNT(ura) > 0 THEN true ELSE false END
            FROM UserRoleAssignmentEntity ura
            WHERE ura.role.name = :roleName AND ura.schoolId = :schoolId AND ura.active = true
              AND ura.revokedAt IS NULL
              AND (ura.validFrom IS NULL OR ura.validFrom <= CURRENT_TIMESTAMP)
              AND (ura.validUntil IS NULL OR ura.validUntil > CURRENT_TIMESTAMP)
            """)
    boolean existsEffectiveRoleForSchool(@Param("roleName") String roleName,
                                         @Param("schoolId") Long schoolId);

    /** First user with an active, effective assignment with the given role for the school. */
    @Query("""
            SELECT ura FROM UserRoleAssignmentEntity ura
            WHERE ura.role.name = :roleName AND ura.schoolId = :schoolId AND ura.active = true
              AND ura.revokedAt IS NULL
              AND (ura.validFrom IS NULL OR ura.validFrom <= CURRENT_TIMESTAMP)
              AND (ura.validUntil IS NULL OR ura.validUntil > CURRENT_TIMESTAMP)
            ORDER BY ura.assignedAt ASC
            """)
    List<UserRoleAssignmentEntity> findEffectiveByRoleAndSchool(@Param("roleName") String roleName,
                                                                @Param("schoolId") Long schoolId);
}
