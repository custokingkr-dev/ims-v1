package com.custoking.ims.repo;
import com.custoking.ims.entity.AcademicYearEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface AcademicYearRepository extends JpaRepository<AcademicYearEntity, String> { Optional<AcademicYearEntity> findFirstByActiveTrue(); }
