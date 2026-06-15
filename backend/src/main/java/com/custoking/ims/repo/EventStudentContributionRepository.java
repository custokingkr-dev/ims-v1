package com.custoking.ims.repo;

import com.custoking.ims.entity.EventStudentContributionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EventStudentContributionRepository extends JpaRepository<EventStudentContributionEntity, String> {

    @Query("SELECT c FROM EventStudentContributionEntity c " +
           "JOIN FETCH c.student s " +
           "JOIN FETCH s.schoolClass " +
           "JOIN FETCH s.section " +
           "WHERE c.event.id = :eventId AND c.schoolId = :schoolId " +
           "AND (:status IS NULL OR c.status = :status) " +
           "AND (:classId IS NULL OR s.schoolClass.id = :classId) " +
           "AND (:sectionId IS NULL OR s.section.id = :sectionId) " +
           "ORDER BY s.fullName ASC")
    Page<EventStudentContributionEntity> findByEventAndSchool(
            @Param("eventId") String eventId,
            @Param("schoolId") Long schoolId,
            @Param("classId") String classId,
            @Param("sectionId") String sectionId,
            @Param("status") String status,
            Pageable pageable);

    @Query("SELECT SUM(c.paidAmount) FROM EventStudentContributionEntity c WHERE c.event.id = :eventId")
    Long sumPaidAmount(@Param("eventId") String eventId);

    Optional<EventStudentContributionEntity> findByEvent_IdAndStudent_IdAndSchoolId(
            String eventId, Long studentId, Long schoolId);
}
