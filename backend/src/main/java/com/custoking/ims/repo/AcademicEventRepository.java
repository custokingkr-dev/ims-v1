package com.custoking.ims.repo;

import com.custoking.ims.entity.AcademicEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AcademicEventRepository extends JpaRepository<AcademicEventEntity, String> {

    Optional<AcademicEventEntity> findFirstBySchoolIdAndEventTypeAndStatus(
            Long schoolId, String eventType, String status);
}
