package com.custoking.ims.repo;

import com.custoking.ims.entity.WorkflowDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinitionEntity, String> {
    Optional<WorkflowDefinitionEntity> findByIdAndActiveTrue(String id);
}
