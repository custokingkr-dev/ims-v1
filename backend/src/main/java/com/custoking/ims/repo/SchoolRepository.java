package com.custoking.ims.repo;

import com.custoking.ims.entity.SchoolEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SchoolRepository extends JpaRepository<SchoolEntity, Long> {
    Optional<SchoolEntity> findByShortCodeIgnoreCase(String shortCode);
    Optional<SchoolEntity> findFirstByOrderByIdAsc();
    List<SchoolEntity> findAllByOrderByNameAsc();
    List<SchoolEntity> findAllByIdInOrderByNameAsc(Collection<Long> ids);
    Optional<SchoolEntity> findByNameIgnoreCase(String name);
    List<SchoolEntity> findByActiveTrueOrderByNameAsc();
}
