package com.custoking.ims.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "workflow_actions")
public class WorkflowActionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instance_id", nullable = false)
    private WorkflowInstanceEntity instance;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_email", length = 255)
    private String actorEmail;

    @Column(length = 1000)
    private String notes;

    @Column(name = "acted_at", nullable = false)
    private OffsetDateTime actedAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public WorkflowInstanceEntity getInstance() { return instance; }
    public void setInstance(WorkflowInstanceEntity instance) { this.instance = instance; }
    public int getStepOrder() { return stepOrder; }
    public void setStepOrder(int stepOrder) { this.stepOrder = stepOrder; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public Long getActorId() { return actorId; }
    public void setActorId(Long actorId) { this.actorId = actorId; }
    public String getActorEmail() { return actorEmail; }
    public void setActorEmail(String actorEmail) { this.actorEmail = actorEmail; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public OffsetDateTime getActedAt() { return actedAt; }
    public void setActedAt(OffsetDateTime actedAt) { this.actedAt = actedAt; }
}
