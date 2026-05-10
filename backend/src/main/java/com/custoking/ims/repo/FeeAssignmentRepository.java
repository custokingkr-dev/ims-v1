package com.custoking.ims.repo;
import com.custoking.ims.entity.FeeAssignmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface FeeAssignmentRepository extends JpaRepository<FeeAssignmentEntity, String> {
    Optional<FeeAssignmentEntity> findByStudent_IdAndAcademicYear_Id(Long studentId, String academicYearId);
    List<FeeAssignmentEntity> findByStudent_SchoolClass_IdAndStudent_Section_IdAndAcademicYear_Id(String classId, String sectionId, String academicYearId);
    Optional<FeeAssignmentEntity> findByStudent_Id(Long studentId);
    List<FeeAssignmentEntity> findByAcademicYear_IdAndStudent_School_Id(String yearId, Long schoolId);
    List<FeeAssignmentEntity> findByAcademicYear_Id(String yearId);
    @Query("SELECT COUNT(a) FROM FeeAssignmentEntity a WHERE a.academicYear.id = :yearId AND a.student.school.id = :schoolId AND a.netPayable > a.paidAmount")
    long countOverdueByYearAndSchool(@Param("yearId") String yearId, @Param("schoolId") Long schoolId);
}
