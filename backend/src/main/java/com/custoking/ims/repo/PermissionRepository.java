package com.custoking.ims.repo;

import com.custoking.ims.entity.PermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PermissionRepository extends JpaRepository<PermissionEntity, Long> {
    Optional<PermissionEntity> findByCode(String code);
    List<PermissionEntity> findByCodeIn(Collection<String> codes);
}
