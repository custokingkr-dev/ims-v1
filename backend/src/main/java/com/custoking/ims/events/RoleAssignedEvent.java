package com.custoking.ims.events;

import org.springframework.context.ApplicationEvent;

/** Published when a role is assigned to a user (any scope). */
public class RoleAssignedEvent extends ApplicationEvent {

    private final long assignmentId;
    private final long userId;
    private final String roleName;
    private final Long schoolId;
    private final Long zoneId;
    private final Long assignedBy;

    public RoleAssignedEvent(Object source, long assignmentId, long userId,
                              String roleName, Long schoolId, Long zoneId, Long assignedBy) {
        super(source);
        this.assignmentId = assignmentId;
        this.userId = userId;
        this.roleName = roleName;
        this.schoolId = schoolId;
        this.zoneId = zoneId;
        this.assignedBy = assignedBy;
    }

    public long getAssignmentId() { return assignmentId; }
    public long getUserId() { return userId; }
    public String getRoleName() { return roleName; }
    public Long getSchoolId() { return schoolId; }
    public Long getZoneId() { return zoneId; }
    public Long getAssignedBy() { return assignedBy; }
}
