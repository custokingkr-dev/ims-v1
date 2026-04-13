package com.custoking.ims.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "school_sections")
public class SchoolSectionEntity {
    @Id
    private String id;
    private String name;
    private String teacherName;
    private boolean active = true;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    private SchoolClassEntity schoolClass;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "school_id")
    private SchoolEntity school;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTeacherName() { return teacherName; }
    public void setTeacherName(String teacherName) { this.teacherName = teacherName; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public SchoolClassEntity getSchoolClass() { return schoolClass; }
    public void setSchoolClass(SchoolClassEntity schoolClass) { this.schoolClass = schoolClass; }
    public SchoolEntity getSchool() { return school; }
    public void setSchool(SchoolEntity school) { this.school = school; }
}
