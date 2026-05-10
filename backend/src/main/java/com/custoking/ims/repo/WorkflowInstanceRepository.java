package com.custoking.ims.repo;

import com.custoking.ims.entity.WorkflowInstanceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowInstanceRepository extends JpaRepository<WorkflowInstanceEntity, Long> {
    Optional<WorkflowInstanceEntity> findByEntityTypeAndEntityId(String entityType, String entityId);
    List<WorkflowInstanceEntity> findBySchoolIdAndStatus(Long schoolId, String status);
    List<WorkflowInstanceEntity> findBySchoolIdAndStatusIn(Long schoolId, List<String> statuses);
    List<WorkflowInstanceEntity> findByStatusIn(List<String> statuses);
}
