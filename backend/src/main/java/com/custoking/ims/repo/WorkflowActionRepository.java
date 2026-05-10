package com.custoking.ims.repo;

import com.custoking.ims.entity.WorkflowActionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowActionRepository extends JpaRepository<WorkflowActionEntity, Long> {
    List<WorkflowActionEntity> findByInstance_IdOrderByActedAtAsc(Long instanceId);
}
