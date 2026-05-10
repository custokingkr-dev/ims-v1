package com.custoking.ims.repo;

import com.custoking.ims.entity.UserRoleAssignmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserRoleAssignmentRepository extends JpaRepository<UserRoleAssignmentEntity, Long> {
    List<UserRoleAssignmentEntity> findByUser_Id(Long userId);
    boolean existsByUser_IdAndRole_Name(Long userId, String roleName);
    void deleteByUser_Id(Long userId);

    @Query("SELECT ura FROM UserRoleAssignmentEntity ura JOIN FETCH ura.role r JOIN FETCH r.permissions WHERE ura.user.id = :userId")
    List<UserRoleAssignmentEntity> findByUser_IdWithPermissions(@Param("userId") Long userId);
}
