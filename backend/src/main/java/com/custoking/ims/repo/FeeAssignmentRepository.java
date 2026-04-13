package com.custoking.ims.repo;
import com.custoking.ims.entity.FeeAssignmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface FeeAssignmentRepository extends JpaRepository<FeeAssignmentEntity, String> { Optional<FeeAssignmentEntity> findByStudent_IdAndAcademicYear_Id(Long studentId, String academicYearId); List<FeeAssignmentEntity> findByStudent_SchoolClass_IdAndStudent_Section_IdAndAcademicYear_Id(String classId, String sectionId, String academicYearId); Optional<FeeAssignmentEntity> findByStudent_Id(Long studentId); }
