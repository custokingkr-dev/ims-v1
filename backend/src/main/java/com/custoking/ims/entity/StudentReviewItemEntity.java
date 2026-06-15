package com.custoking.ims.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "student_review_items",
       uniqueConstraints = @UniqueConstraint(name = "uq_campaign_student", columnNames = {"campaign_id", "student_id"}))
public class StudentReviewItemEntity {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "campaign_id", nullable = false)
    private StudentReviewCampaignEntity campaign;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private StudentEntity student;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "assigned_to_user_id")
    private Long assignedToUserId;

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(name = "verified_photo", nullable = false)
    private boolean verifiedPhoto = false;

    @Column(name = "verified_full_name", nullable = false)
    private boolean verifiedFullName = false;

    @Column(name = "verified_admission_no", nullable = false)
    private boolean verifiedAdmissionNo = false;

    @Column(name = "verified_class_section", nullable = false)
    private boolean verifiedClassSection = false;

    @Column(name = "verified_roll_no", nullable = false)
    private boolean verifiedRollNo = false;

    @Column(name = "verified_father_name", nullable = false)
    private boolean verifiedFatherName = false;

    @Column(name = "verified_father_contact", nullable = false)
    private boolean verifiedFatherContact = false;

    @Column(name = "verified_address", nullable = false)
    private boolean verifiedAddress = false;

    @Column(name = "verified_blood_group", nullable = false)
    private boolean verifiedBloodGroup = false;

    @Column(name = "current_full_name")
    private String currentFullName;

    @Column(name = "suggested_full_name")
    private String suggestedFullName;

    @Column(name = "parent_confirmed", nullable = false)
    private boolean parentConfirmed = false;

    @Column(name = "teacher_confirmed", nullable = false)
    private boolean teacherConfirmed = false;

    @Column(name = "correction_requested", nullable = false)
    private boolean correctionRequested = false;

    @Column(name = "correction_notes", columnDefinition = "TEXT")
    private String correctionNotes;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public StudentReviewCampaignEntity getCampaign() { return campaign; }
    public void setCampaign(StudentReviewCampaignEntity campaign) { this.campaign = campaign; }
    public StudentEntity getStudent() { return student; }
    public void setStudent(StudentEntity student) { this.student = student; }
    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }
    public Long getAssignedToUserId() { return assignedToUserId; }
    public void setAssignedToUserId(Long assignedToUserId) { this.assignedToUserId = assignedToUserId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isVerifiedPhoto() { return verifiedPhoto; }
    public void setVerifiedPhoto(boolean verifiedPhoto) { this.verifiedPhoto = verifiedPhoto; }
    public boolean isVerifiedFullName() { return verifiedFullName; }
    public void setVerifiedFullName(boolean verifiedFullName) { this.verifiedFullName = verifiedFullName; }
    public boolean isVerifiedAdmissionNo() { return verifiedAdmissionNo; }
    public void setVerifiedAdmissionNo(boolean verifiedAdmissionNo) { this.verifiedAdmissionNo = verifiedAdmissionNo; }
    public boolean isVerifiedClassSection() { return verifiedClassSection; }
    public void setVerifiedClassSection(boolean verifiedClassSection) { this.verifiedClassSection = verifiedClassSection; }
    public boolean isVerifiedRollNo() { return verifiedRollNo; }
    public void setVerifiedRollNo(boolean verifiedRollNo) { this.verifiedRollNo = verifiedRollNo; }
    public boolean isVerifiedFatherName() { return verifiedFatherName; }
    public void setVerifiedFatherName(boolean verifiedFatherName) { this.verifiedFatherName = verifiedFatherName; }
    public boolean isVerifiedFatherContact() { return verifiedFatherContact; }
    public void setVerifiedFatherContact(boolean verifiedFatherContact) { this.verifiedFatherContact = verifiedFatherContact; }
    public boolean isVerifiedAddress() { return verifiedAddress; }
    public void setVerifiedAddress(boolean verifiedAddress) { this.verifiedAddress = verifiedAddress; }
    public boolean isVerifiedBloodGroup() { return verifiedBloodGroup; }
    public void setVerifiedBloodGroup(boolean verifiedBloodGroup) { this.verifiedBloodGroup = verifiedBloodGroup; }
    public String getCurrentFullName() { return currentFullName; }
    public void setCurrentFullName(String currentFullName) { this.currentFullName = currentFullName; }
    public String getSuggestedFullName() { return suggestedFullName; }
    public void setSuggestedFullName(String suggestedFullName) { this.suggestedFullName = suggestedFullName; }
    public boolean isParentConfirmed() { return parentConfirmed; }
    public void setParentConfirmed(boolean parentConfirmed) { this.parentConfirmed = parentConfirmed; }
    public boolean isTeacherConfirmed() { return teacherConfirmed; }
    public void setTeacherConfirmed(boolean teacherConfirmed) { this.teacherConfirmed = teacherConfirmed; }
    public boolean isCorrectionRequested() { return correctionRequested; }
    public void setCorrectionRequested(boolean correctionRequested) { this.correctionRequested = correctionRequested; }
    public String getCorrectionNotes() { return correctionNotes; }
    public void setCorrectionNotes(String correctionNotes) { this.correctionNotes = correctionNotes; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
