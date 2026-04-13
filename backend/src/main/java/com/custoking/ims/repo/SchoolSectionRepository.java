package com.custoking.ims.repo;
import com.custoking.ims.entity.SchoolSectionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;import java.util.Optional;
public interface SchoolSectionRepository extends JpaRepository<SchoolSectionEntity, String> { List<SchoolSectionEntity> findBySchoolClass_IdOrderByNameAsc(String classId); Optional<SchoolSectionEntity> findBySchoolClass_IdAndNameIgnoreCase(String classId, String name); Optional<SchoolSectionEntity> findBySchool_IdAndSchoolClass_IdAndNameIgnoreCase(Long schoolId, String classId, String name); }
