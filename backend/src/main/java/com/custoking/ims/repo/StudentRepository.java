package com.custoking.ims.repo;
import com.custoking.ims.entity.StudentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface StudentRepository extends JpaRepository<StudentEntity, Long> {
 Optional<StudentEntity> findByAdmissionNoIgnoreCase(String admissionNo);
 List<StudentEntity> findBySchoolClass_IdOrderByFullNameAsc(String classId);
 List<StudentEntity> findBySchoolClass_IdAndSection_IdOrderByFullNameAsc(String classId, String sectionId);
 long countBySection_Id(String sectionId);
 List<StudentEntity> findByAcademicYear_Id(String academicYearId);
}
