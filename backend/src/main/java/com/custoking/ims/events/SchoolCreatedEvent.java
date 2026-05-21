package com.custoking.ims.events;

import org.springframework.context.ApplicationEvent;

/** Published when a new school is created. */
public class SchoolCreatedEvent extends ApplicationEvent {

    private final long schoolId;
    private final String schoolName;
    private final Long createdBy;

    public SchoolCreatedEvent(Object source, long schoolId, String schoolName, Long createdBy) {
        super(source);
        this.schoolId = schoolId;
        this.schoolName = schoolName;
        this.createdBy = createdBy;
    }

    public long getSchoolId() { return schoolId; }
    public String getSchoolName() { return schoolName; }
    public Long getCreatedBy() { return createdBy; }
}
