package com.custoking.ims.service;

import com.custoking.ims.entity.*;
import com.custoking.ims.repo.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.*;

@Service
@Transactional
public class WorkflowService {

    private final WorkflowDefinitionRepository definitionRepo;
    private final WorkflowStepRepository stepRepo;
    private final WorkflowInstanceRepository instanceRepo;
    private final WorkflowActionRepository actionRepo;

    public WorkflowService(WorkflowDefinitionRepository definitionRepo,
                           WorkflowStepRepository stepRepo,
                           WorkflowInstanceRepository instanceRepo,
                           WorkflowActionRepository actionRepo) {
        this.definitionRepo = definitionRepo;
        this.stepRepo = stepRepo;
        this.instanceRepo = instanceRepo;
        this.actionRepo = actionRepo;
    }

    public WorkflowInstanceEntity createOrGetInstance(String definitionId, String entityType,
                                                       String entityId, Long schoolId, Long initiatedBy) {
        return instanceRepo.findByEntityTypeAndEntityId(entityType, entityId)
                .orElseGet(() -> {
                    WorkflowDefinitionEntity def = definitionRepo.findByIdAndActiveTrue(definitionId)
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                    "Workflow definition not found: " + definitionId));
                    WorkflowInstanceEntity inst = new WorkflowInstanceEntity();
                    inst.setDefinition(def);
                    inst.setEntityType(entityType);
                    inst.setEntityId(entityId);
                    inst.setSchoolId(schoolId);
                    inst.setInitiatedBy(initiatedBy);
                    inst.setStatus("PENDING");
                    inst.setCurrentStep(0);
                    return instanceRepo.save(inst);
                });
    }

    public WorkflowInstanceEntity submit(Long instanceId, Long actorId, String actorEmail, String notes) {
        WorkflowInstanceEntity inst = findInstance(instanceId);
        if (!"PENDING".equals(inst.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Workflow is not in PENDING state");
        }
        inst.setCurrentStep(1);
        inst.setStatus("IN_PROGRESS");
        recordAction(inst, 0, "SUBMIT", actorId, actorEmail, notes);
        return instanceRepo.save(inst);
    }

    public WorkflowInstanceEntity approve(Long instanceId, Long actorId, String actorEmail, String notes) {
        WorkflowInstanceEntity inst = findInstance(instanceId);
        if (!"IN_PROGRESS".equals(inst.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Workflow is not IN_PROGRESS");
        }
        int currentStep = inst.getCurrentStep();
        recordAction(inst, currentStep, "APPROVE", actorId, actorEmail, notes);

        List<WorkflowStepEntity> steps = stepRepo.findByDefinition_IdOrderByStepOrderAsc(inst.getDefinition().getId());
        int maxStep = steps.stream().mapToInt(WorkflowStepEntity::getStepOrder).max().orElse(0);

        if (currentStep >= maxStep) {
            inst.setStatus("APPROVED");
            inst.setCompletedAt(OffsetDateTime.now());
        } else {
            inst.setCurrentStep(currentStep + 1);
        }
        return instanceRepo.save(inst);
    }

    public WorkflowInstanceEntity reject(Long instanceId, Long actorId, String actorEmail, String notes) {
        WorkflowInstanceEntity inst = findInstance(instanceId);
        if (!"IN_PROGRESS".equals(inst.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Workflow is not IN_PROGRESS");
        }
        recordAction(inst, inst.getCurrentStep(), "REJECT", actorId, actorEmail, notes);
        inst.setStatus("REJECTED");
        inst.setCompletedAt(OffsetDateTime.now());
        return instanceRepo.save(inst);
    }

    public WorkflowInstanceEntity cancel(Long instanceId, Long actorId, String actorEmail) {
        WorkflowInstanceEntity inst = findInstance(instanceId);
        recordAction(inst, inst.getCurrentStep(), "CANCEL", actorId, actorEmail, null);
        inst.setStatus("CANCELLED");
        inst.setCompletedAt(OffsetDateTime.now());
        return instanceRepo.save(inst);
    }

    public WorkflowInstanceEntity complete(Long instanceId, Long actorId, String actorEmail, String notes) {
        WorkflowInstanceEntity inst = findInstance(instanceId);
        recordAction(inst, inst.getCurrentStep(), "COMPLETE", actorId, actorEmail, notes);
        inst.setStatus("COMPLETED");
        inst.setCompletedAt(OffsetDateTime.now());
        return instanceRepo.save(inst);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPendingApprovals(Long schoolId) {
        List<WorkflowInstanceEntity> instances = schoolId != null
                ? instanceRepo.findBySchoolIdAndStatus(schoolId, "IN_PROGRESS")
                : instanceRepo.findByStatusIn(List.of("IN_PROGRESS"));
        return instances.stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getInstanceActions(Long instanceId) {
        return actionRepo.findByInstance_IdOrderByActedAtAsc(instanceId).stream()
                .map(a -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", a.getId());
                    row.put("action", a.getAction());
                    row.put("stepOrder", a.getStepOrder());
                    row.put("actorEmail", a.getActorEmail());
                    row.put("notes", a.getNotes());
                    row.put("actedAt", a.getActedAt() == null ? null : a.getActedAt().toString());
                    return row;
                }).toList();
    }

    @Transactional(readOnly = true)
    public Optional<WorkflowInstanceEntity> findByEntity(String entityType, String entityId) {
        return instanceRepo.findByEntityTypeAndEntityId(entityType, entityId);
    }

    private void recordAction(WorkflowInstanceEntity inst, int stepOrder, String action,
                               Long actorId, String actorEmail, String notes) {
        WorkflowActionEntity actionEntity = new WorkflowActionEntity();
        actionEntity.setInstance(inst);
        actionEntity.setStepOrder(stepOrder);
        actionEntity.setAction(action);
        actionEntity.setActorId(actorId);
        actionEntity.setActorEmail(actorEmail);
        actionEntity.setNotes(notes);
        actionRepo.save(actionEntity);
    }

    private WorkflowInstanceEntity findInstance(Long instanceId) {
        return instanceRepo.findById(instanceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow instance not found"));
    }

    private Map<String, Object> toSummary(WorkflowInstanceEntity inst) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", inst.getId());
        row.put("entityType", inst.getEntityType());
        row.put("entityId", inst.getEntityId());
        row.put("schoolId", inst.getSchoolId());
        row.put("status", inst.getStatus());
        row.put("currentStep", inst.getCurrentStep());
        row.put("definitionId", inst.getDefinition().getId());
        row.put("initiatedAt", inst.getInitiatedAt() == null ? null : inst.getInitiatedAt().toString());
        return row;
    }
}
