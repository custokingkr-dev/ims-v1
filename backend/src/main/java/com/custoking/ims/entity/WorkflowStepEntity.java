package com.custoking.ims.entity;

import jakarta.persistence.*;

@Entity
@Table(
    name = "workflow_steps",
    uniqueConstraints = @UniqueConstraint(name = "uk_wf_step_def_order", columnNames = {"definition_id", "step_order"})
)
public class WorkflowStepEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "definition_id", nullable = false)
    private WorkflowDefinitionEntity definition;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @Column(name = "step_name", nullable = false)
    private String stepName;

    @Column(name = "required_permission", length = 100)
    private String requiredPermission;

    @Column(name = "required_role", length = 100)
    private String requiredRole;

    @Column(name = "auto_approve", nullable = false)
    private boolean autoApprove = false;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public WorkflowDefinitionEntity getDefinition() { return definition; }
    public void setDefinition(WorkflowDefinitionEntity definition) { this.definition = definition; }
    public int getStepOrder() { return stepOrder; }
    public void setStepOrder(int stepOrder) { this.stepOrder = stepOrder; }
    public String getStepName() { return stepName; }
    public void setStepName(String stepName) { this.stepName = stepName; }
    public String getRequiredPermission() { return requiredPermission; }
    public void setRequiredPermission(String requiredPermission) { this.requiredPermission = requiredPermission; }
    public String getRequiredRole() { return requiredRole; }
    public void setRequiredRole(String requiredRole) { this.requiredRole = requiredRole; }
    public boolean isAutoApprove() { return autoApprove; }
    public void setAutoApprove(boolean autoApprove) { this.autoApprove = autoApprove; }
}
