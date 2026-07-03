package com.custoking.ims.schoolcoreservice.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ZoneRepository extends JpaRepository<ZoneEntity, Long> {

    List<ZoneEntity> findAllByOrderByNameAsc();
}
