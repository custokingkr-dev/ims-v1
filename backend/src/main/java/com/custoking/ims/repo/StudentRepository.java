package com.custoking.ims.repo;

import com.custoking.ims.entity.StudentEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.*;

public interface StudentRepository extends JpaRepository<StudentEntity, Long> {
    Optional<StudentEntity> findByAdmissionNoIgnoreCase(String admissionNo);
    List<StudentEntity> findBySchoolClass_IdOrderByFullNameAsc(String classId);
    List<StudentEntity> findBySchoolClass_IdAndSection_IdOrderByFullNameAsc(String classId, String sectionId);
    long countBySection_Id(String sectionId);
    long countBySchool_Id(Long schoolId);
    List<StudentEntity> findByAcademicYear_Id(String academicYearId);

    /** Eagerly join-fetch school + academicYear to avoid N+1 on list/page queries. */
    @EntityGraph(attributePaths = {"school", "academicYear"})
    List<StudentEntity> findBySchool_IdOrderByFullNameAsc(Long schoolId);

    List<StudentEntity> findBySection_IdAndSchool_IdOrderByFullNameAsc(String sectionId, Long schoolId);
}
