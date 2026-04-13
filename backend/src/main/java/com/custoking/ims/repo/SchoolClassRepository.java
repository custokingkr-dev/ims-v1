package com.custoking.ims.repo;
import com.custoking.ims.entity.SchoolClassEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;
public interface SchoolClassRepository extends JpaRepository<SchoolClassEntity, String> { Optional<SchoolClassEntity> findByName(String name); List<SchoolClassEntity> findAllByOrderBySortOrderAsc(); }
