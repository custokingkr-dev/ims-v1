package com.custoking.ims.repo;

import com.custoking.ims.entity.FirefightingRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface FirefightingRequestRepository extends JpaRepository<FirefightingRequestEntity, String> {
    List<FirefightingRequestEntity> findBySchool_Id(Long schoolId);
    List<FirefightingRequestEntity> findBySchool_IdAndStatus(Long schoolId, String status);
    long countBySchool_IdAndStatusNot(Long schoolId, String status);
    long countBySchool_IdAndStatus(Long schoolId, String status);
    List<FirefightingRequestEntity> findAllByOrderByCreatedAtDesc();
    List<FirefightingRequestEntity> findBySchool_IdOrderByCreatedAtDesc(Long schoolId);
    List<FirefightingRequestEntity> findByStatus(String status);
    @Query("SELECT r.code FROM FirefightingRequestEntity r")
    List<String> findAllCodes();
}
