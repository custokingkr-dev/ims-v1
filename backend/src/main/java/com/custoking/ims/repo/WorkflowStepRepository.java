package com.custoking.ims.repo;

import com.custoking.ims.entity.WorkflowStepEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowStepRepository extends JpaRepository<WorkflowStepEntity, Long> {
    List<WorkflowStepEntity> findByDefinition_IdOrderByStepOrderAsc(String definitionId);
    Optional<WorkflowStepEntity> findByDefinition_IdAndStepOrder(String definitionId, int stepOrder);
}
