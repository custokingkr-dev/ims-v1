package com.custoking.ims.repo;

import com.custoking.ims.entity.ZoneEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ZoneRepository extends JpaRepository<ZoneEntity, Long> {
    Optional<ZoneEntity> findByName(String name);
    Optional<ZoneEntity> findByCode(String code);
    List<ZoneEntity> findAllByActiveTrueOrderByNameAsc();
}
