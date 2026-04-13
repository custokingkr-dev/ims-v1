package com.custoking.ims.repo;
import com.custoking.ims.entity.FeeBandEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface FeeBandRepository extends JpaRepository<FeeBandEntity, String> { List<FeeBandEntity> findByAcademicYear_IdOrderByClassFromAscNameAsc(String academicYearId); Optional<FeeBandEntity> findFirstByAcademicYear_IdAndClassFromLessThanEqualAndClassToGreaterThanEqual(String academicYearId, int from, int to); }
