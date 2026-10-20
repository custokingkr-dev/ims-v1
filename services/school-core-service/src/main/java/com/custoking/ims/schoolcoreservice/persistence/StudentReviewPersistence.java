package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.custoking.ims.schoolcoreservice.persistence.StudentPresentation.*;
import static com.custoking.ims.schoolcoreservice.persistence.StudentValues.*;
import static com.custoking.ims.schoolcoreservice.persistence.StudentRequestActor.actorId;

import com.custoking.ims.schoolcoreservice.persistence.StudentReadRepository.ReviewCampaignRow;
import com.custoking.ims.schoolcoreservice.persistence.StudentReadRepository.ReviewItemRow;

/** Owns student reviews persistence; application commands own transaction boundaries. */
@Repository
public class StudentReviewPersistence {
    private final JdbcClient jdbc;
    private final OutboxWriter outbox;
    private final StudentReviewInvalidationService reviewInvalidation;
    private final StudentStructurePersistence structure;

    public StudentReviewPersistence(JdbcClient jdbc, OutboxWriter outbox, StudentReviewInvalidationService reviewInvalidation, StudentStructurePersistence structure) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.reviewInvalidation = reviewInvalidation;
        this.structure = structure;
    }

    private record ReviewCounts(long total, long completed, long pending, long needsCorrection, double percent) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> initiateIdCardReview(Map<String, Object> request) {
        Long schoolId = requireLong(request.get("schoolId"), "schoolId is required");
        Long actorId = longValue(request.get("actorId"), null);
        Long active = jdbc.sql("""
                        SELECT COUNT(*)
                        FROM student.student_review_campaigns
                        WHERE school_id = :schoolId AND review_type = 'ID_CARD_DETAILS' AND status = 'ACTIVE'
                        """)
                .param("schoolId", schoolId)
                .query(Long.class)
                .single();
        if (active != null && active > 0) {
            throw new IllegalArgumentException("An active ID Card Details review campaign already exists for this school");
        }
        String academicYearId = structure.currentAcademicYearId(schoolId);
        String academicYearLabel = jdbc.sql("SELECT label FROM tenant_school.academic_years WHERE id = :id")
                .param("id", academicYearId)
                .query(String.class)
                .optional()
                .orElse(academicYearId);
        String campaignId = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO student.student_review_campaigns
                            (id, school_id, academic_year_id, review_type, title, status, initiated_by,
                             initiated_at, due_date, created_at, updated_at)
                        VALUES
                            (:id, :schoolId, :academicYearId, 'ID_CARD_DETAILS', :title, 'ACTIVE', :actorId,
                             now(), :dueDate, now(), now())
                        """)
                .param("id", campaignId)
                .param("schoolId", schoolId)
                .param("academicYearId", academicYearId)
                .param("title", "ID Card Details Review - " + academicYearLabel)
                .param("actorId", actorId)
                .param("dueDate", parseDate(str(request.get("dueDate"), "")))
                .update();
        insertReviewItems(campaignId, schoolId, stringList(request.get("classIds")),
                stringList(request.get("sectionIds")), longValue(request.get("assignedToUserId"), null));
        return idCardStatus(campaignId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> initiateFullNameVerification(Map<String, Object> request) {
        Long schoolId = requireLong(request.get("schoolId"), "schoolId is required");
        Long actorId = longValue(request.get("actorId"), null);
        Long active = jdbc.sql("""
                        SELECT COUNT(*)
                        FROM student.student_review_campaigns
                        WHERE school_id = :schoolId AND review_type = 'FULL_NAME_VERIFICATION' AND status = 'ACTIVE'
                        """)
                .param("schoolId", schoolId)
                .query(Long.class)
                .single();
        if (active != null && active > 0) {
            throw new IllegalArgumentException("An active Full Name Verification campaign already exists for this school");
        }
        String academicYearId = structure.currentAcademicYearId(schoolId);
        String academicYearLabel = jdbc.sql("SELECT label FROM tenant_school.academic_years WHERE id = :id")
                .param("id", academicYearId)
                .query(String.class)
                .optional()
                .orElse(academicYearId);
        String campaignId = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO student.student_review_campaigns
                            (id, school_id, academic_year_id, review_type, title, status, verifier,
                             initiated_by, initiated_at, due_date, created_at, updated_at)
                        VALUES
                            (:id, :schoolId, :academicYearId, 'FULL_NAME_VERIFICATION', :title, 'ACTIVE', :verifier,
                             :actorId, now(), :dueDate, now(), now())
                        """)
                .param("id", campaignId)
                .param("schoolId", schoolId)
                .param("academicYearId", academicYearId)
                .param("title", "Full Name Verification - " + academicYearLabel)
                .param("verifier", str(request.get("verifier"), "TEACHER"))
                .param("actorId", actorId)
                .param("dueDate", parseDate(str(request.get("dueDate"), "")))
                .update();
        insertReviewItems(campaignId, schoolId, stringList(request.get("classIds")),
                stringList(request.get("sectionIds")), null);
        return fullNameStatus(campaignId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> initiateProfileVerification(Map<String, Object> request) {
        return initiateStudentVerification(
                request,
                "PROFILE_VERIFICATION",
                "Profile Verification",
                "An active Profile Verification campaign already exists for this school");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> initiatePhotoVerification(Map<String, Object> request) {
        return initiateStudentVerification(
                request,
                "PHOTO_VERIFICATION",
                "Photo Verification",
                "An active Photo Verification campaign already exists for this school");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> verifyStudentProfile(Long studentId, Long schoolId, Long actorId) {
        String itemId = ensureStudentReviewItem(studentId, schoolId, "PROFILE_VERIFICATION", "Profile Verification", actorId);
        return updateReviewItem(itemId, row(
                "schoolId", schoolId,
                "verifiedFullName", true,
                "verifiedAdmissionNo", true,
                "verifiedClassSection", true,
                "verifiedRollNo", true,
                "verifiedFatherName", true,
                "verifiedFatherContact", true,
                "verifiedAddress", true,
                "status", "COMPLETED",
                "correctionNotes", null));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> verifyStudentPhoto(Long studentId, Long schoolId, Long actorId) {
        String itemId = ensureStudentReviewItem(studentId, schoolId, "PHOTO_VERIFICATION", "Photo Verification", actorId);
        return updateReviewItem(itemId, row(
                "schoolId", schoolId,
                "verifiedPhoto", true,
                "status", "COMPLETED",
                "correctionNotes", null));
    }

    public Map<String, Object> studentVerificationSummary(Long studentId, Long schoolId) {
        return row(
                "profile", activeStudentReviewItem(studentId, schoolId, "PROFILE_VERIFICATION").orElse(null),
                "photo", activeStudentReviewItem(studentId, schoolId, "PHOTO_VERIFICATION").orElse(null));
    }

    private Map<String, Object> initiateStudentVerification(
            Map<String, Object> request, String reviewType, String titlePrefix, String duplicateMessage) {
        Long schoolId = requireLong(request.get("schoolId"), "schoolId is required");
        Long actorId = longValue(request.get("actorId"), null);
        Long active = jdbc.sql("""
                        SELECT COUNT(*)
                        FROM student.student_review_campaigns
                        WHERE school_id = :schoolId AND review_type = :reviewType AND status = 'ACTIVE'
                        """)
                .param("schoolId", schoolId)
                .param("reviewType", reviewType)
                .query(Long.class)
                .single();
        if (active != null && active > 0) {
            throw new IllegalArgumentException(duplicateMessage);
        }
        String academicYearId = structure.currentAcademicYearId(schoolId);
        String academicYearLabel = jdbc.sql("SELECT label FROM tenant_school.academic_years WHERE id = :id")
                .param("id", academicYearId)
                .query(String.class)
                .optional()
                .orElse(academicYearId);
        String campaignId = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO student.student_review_campaigns
                            (id, school_id, academic_year_id, review_type, title, status, initiated_by,
                             initiated_at, due_date, created_at, updated_at)
                        VALUES
                            (:id, :schoolId, :academicYearId, :reviewType, :title, 'ACTIVE', :actorId,
                             now(), :dueDate, now(), now())
                        """)
                .param("id", campaignId)
                .param("schoolId", schoolId)
                .param("academicYearId", academicYearId)
                .param("reviewType", reviewType)
                .param("title", titlePrefix + " - " + academicYearLabel)
                .param("actorId", actorId)
                .param("dueDate", parseDate(str(request.get("dueDate"), "")))
                .update();
        insertReviewItems(campaignId, schoolId, stringList(request.get("classIds")),
                stringList(request.get("sectionIds")), longValue(request.get("assignedToUserId"), null));
        return reviewStatus(campaignId);
    }

    private String ensureStudentReviewItem(Long studentId, Long schoolId, String reviewType, String titlePrefix, Long actorId) {
        validateStudentSchool(studentId, schoolId);
        Optional<String> activeCampaign = activeReviewCampaign(schoolId, reviewType);
        String campaignId = activeCampaign.orElseGet(() -> createStudentVerificationCampaign(schoolId, reviewType, titlePrefix, actorId));
        return activeStudentReviewItemId(studentId, schoolId, reviewType)
                .orElseGet(() -> insertSingleReviewItem(campaignId, studentId, schoolId));
    }

    private String createStudentVerificationCampaign(Long schoolId, String reviewType, String titlePrefix, Long actorId) {
        String academicYearId = structure.currentAcademicYearId(schoolId);
        String academicYearLabel = jdbc.sql("SELECT label FROM tenant_school.academic_years WHERE id = :id")
                .param("id", academicYearId)
                .query(String.class)
                .optional()
                .orElse(academicYearId);
        String campaignId = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO student.student_review_campaigns
                            (id, school_id, academic_year_id, review_type, title, status, initiated_by,
                             initiated_at, created_at, updated_at)
                        VALUES
                            (:id, :schoolId, :academicYearId, :reviewType, :title, 'ACTIVE', :actorId,
                             now(), now(), now())
                        """)
                .param("id", campaignId)
                .param("schoolId", schoolId)
                .param("academicYearId", academicYearId)
                .param("reviewType", reviewType)
                .param("title", titlePrefix + " - " + academicYearLabel)
                .param("actorId", actorId)
                .update();
        insertReviewItems(campaignId, schoolId, List.of(), List.of(), null);
        return campaignId;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> updateReviewItem(String itemId, Map<String, Object> request) {
        requireCampaignEditable(itemId);
        Long schoolId = requireLong(request.get("schoolId"), "schoolId is required");
        if (reviewItemDetail(itemId, schoolId).isEmpty()) {
            throw new IllegalArgumentException("Review item not found");
        }
        Map<String, Object> current = reviewItemFlags(itemId);
        boolean verifiedPhoto = boolOr(request.get("verifiedPhoto"), (Boolean) current.get("verifiedPhoto"));
        boolean verifiedFullName = boolOr(request.get("verifiedFullName"), (Boolean) current.get("verifiedFullName"));
        boolean verifiedAdmissionNo = boolOr(request.get("verifiedAdmissionNo"), (Boolean) current.get("verifiedAdmissionNo"));
        boolean verifiedClassSection = boolOr(request.get("verifiedClassSection"), (Boolean) current.get("verifiedClassSection"));
        boolean verifiedRollNo = boolOr(request.get("verifiedRollNo"), (Boolean) current.get("verifiedRollNo"));
        boolean verifiedFatherName = boolOr(request.get("verifiedFatherName"), (Boolean) current.get("verifiedFatherName"));
        boolean verifiedFatherContact = boolOr(request.get("verifiedFatherContact"), (Boolean) current.get("verifiedFatherContact"));
        boolean verifiedAddress = boolOr(request.get("verifiedAddress"), (Boolean) current.get("verifiedAddress"));
        boolean verifiedBloodGroup = boolOr(request.get("verifiedBloodGroup"), (Boolean) current.get("verifiedBloodGroup"));
        String correctionNotes = request.containsKey("correctionNotes")
                ? str(request.get("correctionNotes"), null)
                : (String) current.get("correctionNotes");
        String reviewType = reviewTypeForItem(itemId);
        String status = str(request.get("status"), null);
        if (status == null || status.isBlank()) {
            boolean hasCorrection = correctionNotes != null && !correctionNotes.isBlank();
            boolean allRequired = switch (reviewType) {
                case "PHOTO_VERIFICATION" -> verifiedPhoto;
                case "PROFILE_VERIFICATION" -> verifiedFullName && verifiedAdmissionNo && verifiedClassSection
                        && verifiedRollNo && verifiedFatherName && verifiedFatherContact && verifiedAddress;
                default -> verifiedPhoto && verifiedFullName && verifiedAdmissionNo && verifiedClassSection
                        && verifiedRollNo && verifiedFatherName && verifiedFatherContact && verifiedAddress;
            };
            status = hasCorrection ? "NEEDS_CORRECTION" : allRequired ? "COMPLETED" : (String) current.get("status");
        }
        boolean correctionRequested = "NEEDS_CORRECTION".equals(status);
        jdbc.sql("""
                        UPDATE student.student_review_items
                        SET verified_photo = :verifiedPhoto,
                            verified_full_name = :verifiedFullName,
                            verified_admission_no = :verifiedAdmissionNo,
                            verified_class_section = :verifiedClassSection,
                            verified_roll_no = :verifiedRollNo,
                            verified_father_name = :verifiedFatherName,
                            verified_father_contact = :verifiedFatherContact,
                            verified_address = :verifiedAddress,
                            verified_blood_group = :verifiedBloodGroup,
                            correction_requested = :correctionRequested,
                            correction_notes = :correctionNotes,
                            status = :status,
                            completed_at = CASE WHEN :status = 'COMPLETED' THEN COALESCE(completed_at, now()) ELSE NULL END,
                            updated_at = now()
                        WHERE id = :itemId AND school_id = :schoolId
                        """)
                .param("itemId", itemId)
                .param("schoolId", schoolId)
                .param("verifiedPhoto", verifiedPhoto)
                .param("verifiedFullName", verifiedFullName)
                .param("verifiedAdmissionNo", verifiedAdmissionNo)
                .param("verifiedClassSection", verifiedClassSection)
                .param("verifiedRollNo", verifiedRollNo)
                .param("verifiedFatherName", verifiedFatherName)
                .param("verifiedFatherContact", verifiedFatherContact)
                .param("verifiedAddress", verifiedAddress)
                .param("verifiedBloodGroup", verifiedBloodGroup)
                .param("correctionRequested", correctionRequested)
                .param("correctionNotes", correctionNotes)
                .param("status", status)
                .update();
        reviewInvalidation.emitUpserted(itemId);
        return reviewItemDetail(itemId, schoolId).orElseThrow();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> verifyFullName(String itemId, Map<String, Object> request) {
        requireCampaignEditable(itemId);
        Long schoolId = requireLong(request.get("schoolId"), "schoolId is required");
        Map<String, Object> detail = reviewItemDetail(itemId, schoolId)
                .orElseThrow(() -> new IllegalArgumentException("Review item not found"));
        String verifier = jdbc.sql("""
                        SELECT c.verifier
                        FROM student.student_review_items i
                        JOIN student.student_review_campaigns c ON c.id = i.campaign_id
                        WHERE i.id = :itemId
                        """)
                .param("itemId", itemId)
                .query(String.class)
                .optional()
                .orElse("TEACHER");
        boolean confirmed = Boolean.TRUE.equals(request.get("confirmed"));
        if (confirmed) {
            boolean parentConfirmed = Boolean.TRUE.equals(detail.get("parentConfirmed"));
            boolean teacherConfirmed = Boolean.TRUE.equals(detail.get("teacherConfirmed"));
            if ("PARENT".equals(verifier)) parentConfirmed = true;
            else teacherConfirmed = true;
            boolean done = switch (verifier == null ? "TEACHER" : verifier) {
                case "PARENT" -> parentConfirmed;
                case "BOTH" -> parentConfirmed && teacherConfirmed;
                default -> teacherConfirmed;
            };
            jdbc.sql("""
                            UPDATE student.student_review_items
                            SET parent_confirmed = :parentConfirmed,
                                teacher_confirmed = :teacherConfirmed,
                                status = CASE WHEN :done THEN 'COMPLETED' ELSE status END,
                                completed_at = CASE WHEN :done THEN now() ELSE completed_at END,
                                updated_at = now()
                            WHERE id = :itemId AND school_id = :schoolId
                            """)
                    .param("itemId", itemId)
                    .param("schoolId", schoolId)
                    .param("parentConfirmed", parentConfirmed)
                    .param("teacherConfirmed", teacherConfirmed)
                    .param("done", done)
                    .update();
        } else {
            jdbc.sql("""
                            UPDATE student.student_review_items
                            SET correction_requested = true,
                                suggested_full_name = :suggestedFullName,
                                correction_notes = :correctionNotes,
                                status = 'NEEDS_CORRECTION',
                                updated_at = now()
                            WHERE id = :itemId AND school_id = :schoolId
                            """)
                    .param("itemId", itemId)
                    .param("schoolId", schoolId)
                    .param("suggestedFullName", str(request.get("suggestedFullName"), null))
                    .param("correctionNotes", str(request.get("correctionNotes"), null))
                    .update();
        }
        reviewInvalidation.emitUpserted(itemId);
        return reviewItemDetail(itemId, schoolId).orElseThrow();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> completeCampaign(String campaignId, Long actorId) {
        Map<String, Object> campaign = jdbc.sql("""
                        SELECT status, school_id, review_type
                        FROM student.student_review_campaigns
                        WHERE id = :campaignId
                        """)
                .param("campaignId", campaignId)
                .query((rs, rowNum) -> row(
                        "status", rs.getString("status"),
                        "schoolId", rs.getLong("school_id"),
                        "reviewType", rs.getString("review_type")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Review campaign not found"));
        if (!"ACTIVE".equals(campaign.get("status"))) {
            throw new IllegalArgumentException("This campaign is not active");
        }
        long unresolved = jdbc.sql("""
                        SELECT count(*) FROM student.student_review_items
                        WHERE campaign_id = :campaignId AND status <> 'COMPLETED'
                        """)
                .param("campaignId", campaignId)
                .query(Long.class)
                .single();
        if (unresolved > 0) {
            throw new IllegalArgumentException(unresolved
                    + " item(s) still need review — resolve them before completing the campaign.");
        }
        jdbc.sql("""
                        UPDATE student.student_review_campaigns
                        SET status = 'COMPLETED', completed_at = now(), completed_by = :actorId, updated_at = now()
                        WHERE id = :campaignId
                        """)
                .param("actorId", actorId)
                .param("campaignId", campaignId)
                .update();
        Long schoolId = ((Number) campaign.get("schoolId")).longValue();
        outbox.append("student-review-campaign.completed.v1", "StudentReviewCampaignCompleted:" + campaignId,
                "StudentReviewCampaign", campaignId, schoolId,
                row("campaignId", campaignId, "schoolId", schoolId, "status", "COMPLETED"));
        return "FULL_NAME_VERIFICATION".equals(campaign.get("reviewType"))
                ? fullNameStatus(campaignId)
                : reviewStatus(campaignId);
    }

    /** Rejects a mutation when the item's owning campaign is COMPLETED (frozen archive). */
    private void requireCampaignEditable(String itemId) {
        String campaignStatus = jdbc.sql("""
                        SELECT c.status
                        FROM student.student_review_items i
                        JOIN student.student_review_campaigns c ON c.id = i.campaign_id
                        WHERE i.id = :itemId
                        """)
                .param("itemId", itemId)
                .query(String.class)
                .optional()
                .orElse(null);
        if ("COMPLETED".equals(campaignStatus)) {
            throw new CampaignCompletedException("This campaign is completed and read-only.");
        }
    }

    public List<ReviewCampaignRow> reviewCampaigns(Long schoolId, String reviewType, String status, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, school_id, academic_year_id, review_type, title, status,
                       verifier, initiated_by, initiated_at, due_date, created_at, updated_at
                FROM student.student_review_campaigns
                WHERE 1=1
                """);
        if (schoolId != null) sql.append(" AND school_id = :schoolId");
        if (reviewType != null && !reviewType.isBlank()) sql.append(" AND review_type = :reviewType");
        if (status != null && !status.isBlank()) sql.append(" AND status = :status");
        sql.append(" ORDER BY created_at DESC LIMIT :limit");
        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 500)));
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        if (reviewType != null && !reviewType.isBlank()) spec = spec.param("reviewType", reviewType);
        if (status != null && !status.isBlank()) spec = spec.param("status", status);
        return spec.query(ReviewCampaignRow.class).list();
    }

    public Map<String, Object> idCardReviewStatus(Long schoolId) {
        return activeReviewCampaign(schoolId, "ID_CARD_DETAILS")
                .map(this::idCardStatus)
                .orElseGet(() -> row(
                        "campaignId", null,
                        "totalStudents", 0,
                        "completed", 0,
                        "pending", 0,
                        "needsCorrection", 0,
                        "completionPercent", 0.0,
                        "classWiseStatus", List.of()));
    }

    public Map<String, Object> fullNameVerificationStatus(Long schoolId) {
        return activeReviewCampaign(schoolId, "FULL_NAME_VERIFICATION")
                .map(this::fullNameStatus)
                .orElseGet(() -> row(
                        "campaignId", null,
                        "totalStudents", 0,
                        "confirmed", 0,
                        "correctionRequested", 0,
                        "pending", 0,
                        "completionPercent", 0.0));
    }

    public Map<String, Object> profileVerificationStatus(Long schoolId) {
        return activeReviewCampaign(schoolId, "PROFILE_VERIFICATION")
                .map(this::reviewStatus)
                .orElseGet(this::emptyReviewStatus);
    }

    public Map<String, Object> photoVerificationStatus(Long schoolId) {
        return activeReviewCampaign(schoolId, "PHOTO_VERIFICATION")
                .map(this::reviewStatus)
                .orElseGet(this::emptyReviewStatus);
    }

    public List<ReviewItemRow> reviewItems(String campaignId, Long schoolId, String status, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, campaign_id, student_id, school_id, assigned_to_user_id, status,
                       verified_photo, verified_full_name, verified_admission_no,
                       verified_class_section, verified_roll_no, verified_father_name,
                       verified_father_contact, verified_address, verified_blood_group,
                       current_full_name, suggested_full_name, parent_confirmed,
                       teacher_confirmed, correction_requested, correction_notes,
                       completed_at, created_at, updated_at
                FROM student.student_review_items
                WHERE 1=1
                """);
        if (campaignId != null && !campaignId.isBlank()) sql.append(" AND campaign_id = :campaignId");
        if (schoolId != null) sql.append(" AND school_id = :schoolId");
        if (status != null && !status.isBlank()) sql.append(" AND status = :status");
        sql.append(" ORDER BY updated_at DESC LIMIT :limit");
        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 1000)));
        if (campaignId != null && !campaignId.isBlank()) spec = spec.param("campaignId", campaignId);
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        if (status != null && !status.isBlank()) spec = spec.param("status", status);
        return spec.query(ReviewItemRow.class).list();
    }

    public Map<String, Object> campaignReviewItems(
            Long schoolId, String campaignId, String status, String classId, String sectionId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        StringBuilder where = new StringBuilder("""
                FROM student.student_review_items i
                JOIN student.students s ON s.id = i.student_id
                JOIN tenant_school.school_classes c ON c.id = s.class_id
                JOIN tenant_school.school_sections sec ON sec.id = s.section_id
                WHERE i.campaign_id = :campaignId
                  AND i.school_id = :schoolId
                """);
        if (status != null && !status.isBlank()) where.append(" AND i.status = :status");
        if (classId != null && !classId.isBlank()) where.append(" AND s.class_id = :classId");
        if (sectionId != null && !sectionId.isBlank()) where.append(" AND s.section_id = :sectionId");

        var countSpec = jdbc.sql("SELECT COUNT(*) " + where)
                .param("campaignId", campaignId)
                .param("schoolId", schoolId);
        var listSpec = jdbc.sql("""
                        SELECT i.id, i.student_id, s.full_name, s.admission_no, c.name AS class_name,
                               sec.name AS section_name, s.photo_url,
                               i.current_full_name, i.suggested_full_name,
                               i.status, i.verified_photo, i.verified_full_name, i.verified_admission_no,
                               i.verified_class_section, i.verified_roll_no, i.verified_father_name,
                               i.verified_father_contact, i.verified_address, i.verified_blood_group,
                               i.parent_confirmed, i.teacher_confirmed, i.correction_requested,
                               i.correction_notes, i.completed_at
                        """ + where + """
                        ORDER BY s.full_name ASC, i.updated_at DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .param("campaignId", campaignId)
                .param("schoolId", schoolId)
                .param("limit", safeSize)
                .param("offset", safePage * safeSize);
        if (status != null && !status.isBlank()) {
            countSpec = countSpec.param("status", status);
            listSpec = listSpec.param("status", status);
        }
        if (classId != null && !classId.isBlank()) {
            countSpec = countSpec.param("classId", classId);
            listSpec = listSpec.param("classId", classId);
        }
        if (sectionId != null && !sectionId.isBlank()) {
            countSpec = countSpec.param("sectionId", sectionId);
            listSpec = listSpec.param("sectionId", sectionId);
        }

        Long total = countSpec.query(Long.class).single();
        List<Map<String, Object>> content = listSpec.query((rs, rowNum) -> row(
                        "itemId", rs.getString("id"),
                        "studentId", rs.getLong("student_id"),
                        "studentName", rs.getString("full_name"),
                        "admissionNo", rs.getString("admission_no"),
                        "className", rs.getString("class_name"),
                        "sectionName", rs.getString("section_name"),
                        "photoUrl", photoReference(rs.getLong("student_id"), rs.getString("photo_url")),
                        "currentFullName", rs.getString("current_full_name"),
                        "suggestedFullName", rs.getString("suggested_full_name"),
                        "status", rs.getString("status"),
                        "verifiedPhoto", rs.getBoolean("verified_photo"),
                        "verifiedFullName", rs.getBoolean("verified_full_name"),
                        "verifiedAdmissionNo", rs.getBoolean("verified_admission_no"),
                        "verifiedClassSection", rs.getBoolean("verified_class_section"),
                        "verifiedRollNo", rs.getBoolean("verified_roll_no"),
                        "verifiedFatherName", rs.getBoolean("verified_father_name"),
                        "verifiedFatherContact", rs.getBoolean("verified_father_contact"),
                        "verifiedAddress", rs.getBoolean("verified_address"),
                        "verifiedBloodGroup", rs.getBoolean("verified_blood_group"),
                        "parentConfirmed", rs.getBoolean("parent_confirmed"),
                        "teacherConfirmed", rs.getBoolean("teacher_confirmed"),
                        "correctionRequested", rs.getBoolean("correction_requested"),
                        "correctionNotes", rs.getString("correction_notes"),
                        "completedAt", rs.getObject("completed_at", OffsetDateTime.class)))
                .list();
        return row("content", content, "totalElements", total == null ? 0 : total,
                "page", safePage, "size", safeSize);
    }

    private void insertReviewItems(String campaignId, Long schoolId, List<String> classIds, List<String> sectionIds,
                                   Long assignedToUserId) {
        StringBuilder sql = new StringBuilder("""
                INSERT INTO student.student_review_items
                    (id, campaign_id, student_id, school_id, assigned_to_user_id, status,
                     current_full_name, created_at, updated_at)
                SELECT gen_random_uuid()::text, :campaignId, s.id, :schoolId, :assignedToUserId,
                       'PENDING', s.full_name, now(), now()
                FROM student.students s
                WHERE s.school_id = :schoolId AND s.deleted_at IS NULL
                """);
        if (!classIds.isEmpty()) sql.append(" AND s.class_id IN (:classIds)");
        if (!sectionIds.isEmpty()) sql.append(" AND s.section_id IN (:sectionIds)");
        sql.append("""
                ON CONFLICT (campaign_id, student_id) DO NOTHING
                RETURNING id, campaign_id, school_id, status
                """);
        var spec = jdbc.sql(sql.toString())
                .param("campaignId", campaignId)
                .param("schoolId", schoolId)
                .param("assignedToUserId", assignedToUserId);
        if (!classIds.isEmpty()) spec = spec.param("classIds", classIds);
        if (!sectionIds.isEmpty()) spec = spec.param("sectionIds", sectionIds);
        List<OutboxWriter.Event> events = spec.query((rs, rowNum) -> {
            String itemId = rs.getString("id");
            String returnedCampaignId = rs.getString("campaign_id");
            Long returnedSchoolId = rs.getLong("school_id");
            String status = rs.getString("status");
            return new OutboxWriter.Event(
                    "student-review-item.upserted.v1",
                    "StudentReviewItemUpserted:" + itemId,
                    "StudentReviewItem",
                    itemId,
                    returnedSchoolId,
                    row("id", itemId, "schoolId", returnedSchoolId,
                            "campaignId", returnedCampaignId, "status", status));
        }).list();
        outbox.appendAll(events);
    }

    private String insertSingleReviewItem(String campaignId, Long studentId, Long schoolId) {
        String fullName = jdbc.sql("""
                        SELECT full_name
                        FROM student.students
                        WHERE id = :studentId AND school_id = :schoolId AND deleted_at IS NULL
                        """)
                .param("studentId", studentId)
                .param("schoolId", schoolId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));
        String itemId = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO student.student_review_items
                            (id, campaign_id, student_id, school_id, status, current_full_name, created_at, updated_at)
                        VALUES
                            (:id, :campaignId, :studentId, :schoolId, 'PENDING', :currentFullName, now(), now())
                        ON CONFLICT (campaign_id, student_id) DO NOTHING
                        """)
                .param("id", itemId)
                .param("campaignId", campaignId)
                .param("studentId", studentId)
                .param("schoolId", schoolId)
                .param("currentFullName", fullName)
                .update();
        String resolvedItemId = activeStudentReviewItemId(studentId, schoolId, reviewTypeForCampaign(campaignId)).orElse(itemId);
        reviewInvalidation.emitUpserted(resolvedItemId);
        return resolvedItemId;
    }

    private void validateStudentSchool(Long studentId, Long schoolId) {
        Long count = jdbc.sql("""
                        SELECT COUNT(*)
                        FROM student.students
                        WHERE id = :studentId AND school_id = :schoolId AND deleted_at IS NULL
                        """)
                .param("studentId", studentId)
                .param("schoolId", schoolId)
                .query(Long.class)
                .single();
        if (count == null || count == 0) {
            throw new IllegalArgumentException("Student not found");
        }
    }

    private Map<String, Object> idCardStatus(String campaignId) {
        Map<String, Object> status = reviewStatus(campaignId);
        status.put("classWiseStatus", List.of());
        return status;
    }

    private Map<String, Object> fullNameStatus(String campaignId) {
        ReviewCounts counts = reviewCounts(campaignId);
        return row("campaignId", campaignId,
                "totalStudents", counts.total(),
                "confirmed", counts.completed(),
                "correctionRequested", counts.needsCorrection(),
                "pending", counts.pending(),
                "completionPercent", counts.percent());
    }

    private Map<String, Object> reviewStatus(String campaignId) {
        ReviewCounts counts = reviewCounts(campaignId);
        return row("campaignId", campaignId,
                "totalStudents", counts.total(),
                "completed", counts.completed(),
                "pending", counts.pending(),
                "needsCorrection", counts.needsCorrection(),
                "completionPercent", counts.percent());
    }

    private Map<String, Object> emptyReviewStatus() {
        return row(
                "campaignId", null,
                "totalStudents", 0,
                "completed", 0,
                "pending", 0,
                "needsCorrection", 0,
                "completionPercent", 0.0);
    }

    private Optional<String> activeReviewCampaign(Long schoolId, String reviewType) {
        return jdbc.sql("""
                        SELECT id
                        FROM student.student_review_campaigns
                        WHERE school_id = :schoolId
                          AND review_type = :reviewType
                          AND status = 'ACTIVE'
                        ORDER BY created_at DESC NULLS LAST, initiated_at DESC NULLS LAST, id DESC
                        LIMIT 1
                        """)
                .param("schoolId", schoolId)
                .param("reviewType", reviewType)
                .query(String.class)
                .optional();
    }

    private Optional<String> activeStudentReviewItemId(Long studentId, Long schoolId, String reviewType) {
        return jdbc.sql("""
                        SELECT i.id
                        FROM student.student_review_items i
                        JOIN student.student_review_campaigns c ON c.id = i.campaign_id
                        WHERE i.student_id = :studentId
                          AND i.school_id = :schoolId
                          AND c.review_type = :reviewType
                          AND c.status = 'ACTIVE'
                        ORDER BY i.updated_at DESC NULLS LAST, i.created_at DESC NULLS LAST, i.id DESC
                        LIMIT 1
                        """)
                .param("studentId", studentId)
                .param("schoolId", schoolId)
                .param("reviewType", reviewType)
                .query(String.class)
                .optional();
    }

    private Optional<Map<String, Object>> activeStudentReviewItem(Long studentId, Long schoolId, String reviewType) {
        return activeStudentReviewItemId(studentId, schoolId, reviewType)
                .flatMap(itemId -> reviewItemDetail(itemId, schoolId));
    }

    private String reviewTypeForCampaign(String campaignId) {
        return jdbc.sql("""
                        SELECT review_type
                        FROM student.student_review_campaigns
                        WHERE id = :campaignId
                        """)
                .param("campaignId", campaignId)
                .query(String.class)
                .optional()
                .orElse("");
    }

    private ReviewCounts reviewCounts(String campaignId) {
        long total = countReviewItems(campaignId, null);
        long completed = countReviewItems(campaignId, "COMPLETED");
        long needsCorrection = countReviewItems(campaignId, "NEEDS_CORRECTION");
        long pending = Math.max(0, total - completed - needsCorrection);
        double percent = total == 0 ? 0.0 : Math.round((completed * 10000.0 / total)) / 100.0;
        return new ReviewCounts(total, completed, pending, needsCorrection, percent);
    }

    private long countReviewItems(String campaignId, String status) {
        if (status == null) {
            return jdbc.sql("SELECT COUNT(*) FROM student.student_review_items WHERE campaign_id = :campaignId")
                    .param("campaignId", campaignId)
                    .query(Long.class)
                    .single();
        }
        return jdbc.sql("SELECT COUNT(*) FROM student.student_review_items WHERE campaign_id = :campaignId AND status = :status")
                .param("campaignId", campaignId)
                .param("status", status)
                .query(Long.class)
                .single();
    }

    private Optional<Map<String, Object>> reviewItemDetail(String itemId, Long schoolId) {
        return jdbc.sql("""
                        SELECT i.id, i.student_id, s.full_name, s.admission_no, c.name AS class_name,
                               sec.name AS section_name, s.photo_url,
                               i.current_full_name, i.suggested_full_name,
                               i.status, i.verified_photo, i.verified_full_name, i.verified_admission_no,
                               i.verified_class_section, i.verified_roll_no, i.verified_father_name,
                               i.verified_father_contact, i.verified_address, i.verified_blood_group,
                               i.parent_confirmed, i.teacher_confirmed, i.correction_requested,
                               i.correction_notes, i.completed_at
                        FROM student.student_review_items i
                        JOIN student.students s ON s.id = i.student_id
                        JOIN tenant_school.school_classes c ON c.id = s.class_id
                        JOIN tenant_school.school_sections sec ON sec.id = s.section_id
                        WHERE i.id = :itemId AND i.school_id = :schoolId
                        """)
                .param("itemId", itemId)
                .param("schoolId", schoolId)
                .query((rs, rowNum) -> row(
                        "itemId", rs.getString("id"),
                        "studentId", rs.getLong("student_id"),
                        "studentName", rs.getString("full_name"),
                        "admissionNo", rs.getString("admission_no"),
                        "className", rs.getString("class_name"),
                        "sectionName", rs.getString("section_name"),
                        "photoUrl", photoReference(rs.getLong("student_id"), rs.getString("photo_url")),
                        "currentFullName", rs.getString("current_full_name"),
                        "suggestedFullName", rs.getString("suggested_full_name"),
                        "status", rs.getString("status"),
                        "verifiedPhoto", rs.getBoolean("verified_photo"),
                        "verifiedFullName", rs.getBoolean("verified_full_name"),
                        "verifiedAdmissionNo", rs.getBoolean("verified_admission_no"),
                        "verifiedClassSection", rs.getBoolean("verified_class_section"),
                        "verifiedRollNo", rs.getBoolean("verified_roll_no"),
                        "verifiedFatherName", rs.getBoolean("verified_father_name"),
                        "verifiedFatherContact", rs.getBoolean("verified_father_contact"),
                        "verifiedAddress", rs.getBoolean("verified_address"),
                        "verifiedBloodGroup", rs.getBoolean("verified_blood_group"),
                        "parentConfirmed", rs.getBoolean("parent_confirmed"),
                        "teacherConfirmed", rs.getBoolean("teacher_confirmed"),
                        "correctionRequested", rs.getBoolean("correction_requested"),
                        "correctionNotes", rs.getString("correction_notes"),
                        "completedAt", rs.getObject("completed_at", OffsetDateTime.class)))
                .optional();
    }

    private Map<String, Object> reviewItemFlags(String itemId) {
        return jdbc.sql("""
                        SELECT status, verified_photo, verified_full_name, verified_admission_no,
                               verified_class_section, verified_roll_no, verified_father_name,
                               verified_father_contact, verified_address, verified_blood_group,
                               correction_notes
                        FROM student.student_review_items
                        WHERE id = :itemId
                        """)
                .param("itemId", itemId)
                .query((rs, rowNum) -> row(
                        "status", rs.getString("status"),
                        "verifiedPhoto", rs.getBoolean("verified_photo"),
                        "verifiedFullName", rs.getBoolean("verified_full_name"),
                        "verifiedAdmissionNo", rs.getBoolean("verified_admission_no"),
                        "verifiedClassSection", rs.getBoolean("verified_class_section"),
                        "verifiedRollNo", rs.getBoolean("verified_roll_no"),
                        "verifiedFatherName", rs.getBoolean("verified_father_name"),
                        "verifiedFatherContact", rs.getBoolean("verified_father_contact"),
                        "verifiedAddress", rs.getBoolean("verified_address"),
                        "verifiedBloodGroup", rs.getBoolean("verified_blood_group"),
                        "correctionNotes", rs.getString("correction_notes")))
                .single();
    }

    private String reviewTypeForItem(String itemId) {
        return jdbc.sql("""
                        SELECT c.review_type
                        FROM student.student_review_items i
                        JOIN student.student_review_campaigns c ON c.id = i.campaign_id
                        WHERE i.id = :itemId
                        """)
                .param("itemId", itemId)
                .query(String.class)
                .optional()
                .orElse("");
    }

    public Long schoolIdForReviewItem(String itemId) {
        return jdbc.sql("SELECT school_id FROM student.student_review_items WHERE id = :itemId")
                .param("itemId", itemId)
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    public Long schoolIdForCampaign(String campaignId) {
        return jdbc.sql("SELECT school_id FROM student.student_review_campaigns WHERE id = :campaignId")
                .param("campaignId", campaignId)
                .query(Long.class)
                .optional()
                .orElse(null);
    }
}
