package com.custoking.ims.repo;

import com.custoking.ims.entity.CatalogOrderEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface CatalogOrderRepository extends JpaRepository<CatalogOrderEntity, String> {
    List<CatalogOrderEntity> findBySchool_Id(Long schoolId);
    List<CatalogOrderEntity> findBySchool_IdAndStatus(Long schoolId, String status);
    List<CatalogOrderEntity> findBySchool_IdAndStatusIn(Long schoolId, List<String> statuses);
    List<CatalogOrderEntity> findByStatusOrderByCreatedAtDesc(String status);
    List<CatalogOrderEntity> findAllByOrderByCreatedAtDesc();
    List<CatalogOrderEntity> findBySchool_IdOrderByCreatedAtDesc(Long schoolId);

    // ── Paginated variants ────────────────────────────────────────────────
    Page<CatalogOrderEntity> findBySchool_IdOrderByCreatedAtDesc(Long schoolId, Pageable pageable);
    Page<CatalogOrderEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    // ── Vendor payment dues ───────────────────────────────────────────────
    @Query("SELECT COUNT(o) FROM CatalogOrderEntity o WHERE o.school.id = :schoolId " +
           "AND o.status IN ('APPROVED', 'FULFILLED') AND o.vendorPaidAt IS NULL")
    long countPendingVendorDues(@Param("schoolId") Long schoolId);

    @Query("SELECT SUM(o.totalAmount) FROM CatalogOrderEntity o WHERE o.school.id = :schoolId " +
           "AND o.status IN ('APPROVED', 'FULFILLED') AND o.vendorPaidAt IS NULL")
    Long sumPendingVendorDues(@Param("schoolId") Long schoolId);

    @Query("SELECT o FROM CatalogOrderEntity o WHERE o.school.id = :schoolId " +
           "AND o.status IN ('APPROVED', 'FULFILLED') AND o.vendorPaidAt IS NULL " +
           "ORDER BY o.createdAt DESC")
    List<CatalogOrderEntity> findPendingVendorDues(@Param("schoolId") Long schoolId);
}
