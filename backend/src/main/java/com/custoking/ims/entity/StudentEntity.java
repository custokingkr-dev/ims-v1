package com.custoking.ims.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "students", uniqueConstraints = {
        // Composite constraint: admission_no must be unique within each school.
        // V126 migration replaced the old global uk_student_admission_no with this.
        @UniqueConstraint(name = "uix_students_school_admission", columnNames = {"school_id", "admission_no"})
})
public class StudentEntity {
    @Id @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "student_seq")
    @SequenceGenerator(name = "student_seq", sequenceName = "seq_students", allocationSize = 1)
    private Long id;
    @Column(name = "admission_no", nullable = false) private String admissionNo;
    private String rollNo; private String boardRegNo; @Column(nullable = false) private String fullName; private LocalDate dob; private String gender; private String fatherName; private String fatherContact; private String motherName; private String phone; @Column(columnDefinition = "TEXT") private String address; private String houseNumber; private String street; private String locality; private String city; private String state; private String pinCode; private String photoUrl; private String feeStatus; private Double attendancePercent; private OffsetDateTime importedAt; private String importBatchId; private OffsetDateTime createdAt = OffsetDateTime.now(); private OffsetDateTime updatedAt = OffsetDateTime.now();
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "school_id") private SchoolEntity school;
    @ManyToOne(fetch = FetchType.EAGER, optional = false) @JoinColumn(name = "class_id") private SchoolClassEntity schoolClass;
    @ManyToOne(fetch = FetchType.EAGER, optional = false) @JoinColumn(name = "section_id") private SchoolSectionEntity section;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "academic_year_id") private AcademicYearEntity academicYear;
    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public String getAdmissionNo() { return admissionNo; } public void setAdmissionNo(String admissionNo) { this.admissionNo = admissionNo; }
    public String getRollNo() { return rollNo; } public void setRollNo(String rollNo) { this.rollNo = rollNo; }
    public String getBoardRegNo() { return boardRegNo; } public void setBoardRegNo(String boardRegNo) { this.boardRegNo = boardRegNo; }
    public String getFullName() { return fullName; } public void setFullName(String fullName) { this.fullName = fullName; }
    public LocalDate getDob() { return dob; } public void setDob(LocalDate dob) { this.dob = dob; }
    public String getGender() { return gender; } public void setGender(String gender) { this.gender = gender; }
    public String getFatherName() { return fatherName; } public void setFatherName(String fatherName) { this.fatherName = fatherName; }
    public String getFatherContact() { return fatherContact; } public void setFatherContact(String fatherContact) { this.fatherContact = fatherContact; }
    public String getMotherName() { return motherName; } public void setMotherName(String motherName) { this.motherName = motherName; }
    public String getPhone() { return phone; } public void setPhone(String phone) { this.phone = phone; }
    public String getAddress() { return address; } public void setAddress(String address) { this.address = address; }
    public String getHouseNumber() { return houseNumber; } public void setHouseNumber(String houseNumber) { this.houseNumber = houseNumber; }
    public String getStreet() { return street; } public void setStreet(String street) { this.street = street; }
    public String getLocality() { return locality; } public void setLocality(String locality) { this.locality = locality; }
    public String getCity() { return city; } public void setCity(String city) { this.city = city; }
    public String getState() { return state; } public void setState(String state) { this.state = state; }
    public String getPinCode() { return pinCode; } public void setPinCode(String pinCode) { this.pinCode = pinCode; }
    public String getPhotoUrl() { return photoUrl; } public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }
    public String getFeeStatus() { return feeStatus; } public void setFeeStatus(String feeStatus) { this.feeStatus = feeStatus; }
    public Double getAttendancePercent() { return attendancePercent; } public void setAttendancePercent(Double attendancePercent) { this.attendancePercent = attendancePercent; }
    public OffsetDateTime getImportedAt() { return importedAt; } public void setImportedAt(OffsetDateTime importedAt) { this.importedAt = importedAt; }
    public String getImportBatchId() { return importBatchId; } public void setImportBatchId(String importBatchId) { this.importBatchId = importBatchId; }
    public OffsetDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; } public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public SchoolEntity getSchool() { return school; } public void setSchool(SchoolEntity school) { this.school = school; }
    public SchoolClassEntity getSchoolClass() { return schoolClass; } public void setSchoolClass(SchoolClassEntity schoolClass) { this.schoolClass = schoolClass; }
    public SchoolSectionEntity getSection() { return section; } public void setSection(SchoolSectionEntity section) { this.section = section; }
    public AcademicYearEntity getAcademicYear() { return academicYear; } public void setAcademicYear(AcademicYearEntity academicYear) { this.academicYear = academicYear; }
}
