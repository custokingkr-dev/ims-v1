package com.custoking.ims.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "staff_members")
public class StaffMemberEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "staff_member_seq")
    @SequenceGenerator(name = "staff_member_seq", sequenceName = "seq_staff_members", allocationSize = 1)
    private Long id;
    private String name;
    private String designation;
    private String department;
    private long monthlySalary;
    private String payrollStatus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "school_id")
    private SchoolEntity school;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDesignation() { return designation; }
    public void setDesignation(String designation) { this.designation = designation; }
    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }
    public long getMonthlySalary() { return monthlySalary; }
    public void setMonthlySalary(long monthlySalary) { this.monthlySalary = monthlySalary; }
    public String getPayrollStatus() { return payrollStatus; }
    public void setPayrollStatus(String payrollStatus) { this.payrollStatus = payrollStatus; }
    public SchoolEntity getSchool() { return school; }
    public void setSchool(SchoolEntity school) { this.school = school; }
}
