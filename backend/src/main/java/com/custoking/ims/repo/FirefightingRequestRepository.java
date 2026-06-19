package com.custoking.ims.repo;

import com.custoking.ims.entity.FirefightingRequestEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    // ── Paginated variants ────────────────────────────────────────────────
    Page<FirefightingRequestEntity> findBySchool_IdOrderByCreatedAtDesc(Long schoolId, Pageable pageable);
    Page<FirefightingRequestEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    // ── Vendor payment dues ───────────────────────────────────────────────
    @Query("SELECT COUNT(r) FROM FirefightingRequestEntity r WHERE r.school.id = :schoolId " +
           "AND r.status = 'APPROVED' AND r.winnerAmount IS NOT NULL AND r.vendorPaidAt IS NULL")
    long countPendingVendorDues(@Param("schoolId") Long schoolId);

    @Query("SELECT SUM(r.winnerAmount) FROM FirefightingRequestEntity r WHERE r.school.id = :schoolId " +
           "AND r.status = 'APPROVED' AND r.winnerAmount IS NOT NULL AND r.vendorPaidAt IS NULL")
    Long sumPendingVendorDues(@Param("schoolId") Long schoolId);

    @Query("SELECT r FROM FirefightingRequestEntity r WHERE r.school.id = :schoolId " +
           "AND r.status = 'APPROVED' AND r.winnerAmount IS NOT NULL AND r.vendorPaidAt IS NULL " +
           "ORDER BY r.createdAt DESC")
    List<FirefightingRequestEntity> findPendingVendorDues(@Param("schoolId") Long schoolId);
}
