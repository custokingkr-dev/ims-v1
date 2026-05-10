package com.custoking.ims.repo;

import com.custoking.ims.entity.RoleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<RoleEntity, Long> {
    Optional<RoleEntity> findByName(String name);
    List<RoleEntity> findByNameIn(Collection<String> names);

    @Query("SELECT DISTINCT r FROM RoleEntity r JOIN FETCH r.permissions WHERE r.id IN :ids")
    List<RoleEntity> findByIdInWithPermissions(@Param("ids") Collection<Long> ids);
}
