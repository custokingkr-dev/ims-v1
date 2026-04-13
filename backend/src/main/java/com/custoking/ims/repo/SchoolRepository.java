package com.custoking.ims.repo;

import com.custoking.ims.entity.SchoolEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SchoolRepository extends JpaRepository<SchoolEntity, Long> {
    Optional<SchoolEntity> findByShortCodeIgnoreCase(String shortCode);
}
