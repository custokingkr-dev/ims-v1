package com.custoking.ims.schoolcoreservice.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SchoolRepository extends JpaRepository<SchoolEntity, Long> {

    List<SchoolEntity> findAllByOrderByNameAsc();
}
