package com.custoking.ims.commandcenter;

import com.custoking.ims.audit.AuditLogService;
import com.custoking.ims.commandcenter.dto.*;
import com.custoking.ims.entity.StudentEntity;
import com.custoking.ims.entity.StudentReviewCampaignEntity;
import com.custoking.ims.entity.StudentReviewItemEntity;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.repo.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class StudentReviewService {

    public static final String REVIEW_TYPE_ID_CARD = "ID_CARD_DETAILS";
    public static final String REVIEW_TYPE_FULL_NAME = "FULL_NAME_VERIFICATION";

    private final StudentReviewCampaignRepository campaignRepository;
    private final StudentReviewItemRepository itemRepository;
    private final StudentRepository studentRepository;
    private final AcademicYearRepository academicYearRepository;
    private final AuditLogService auditLogService;

    public StudentReviewService(StudentReviewCampaignRepository campaignRepository,
                                StudentReviewItemRepository itemRepository,
                                StudentRepository studentRepository,
                                AcademicYearRepository academicYearRepository,
                                AuditLogService auditLogService) {
        this.campaignRepository = campaignRepository;
        this.itemRepository = itemRepository;
        this.studentRepository = studentRepository;
        this.academicYearRepository = academicYearRepository;
        this.auditLogService = auditLogService;
    }

    // ── ID Card Review ────────────────────────────────────────────────────────

    @Transactional
    public IdCardReviewStatusResponse initiateIdCardReview(
            Long schoolId, AuthUser actor, InitiateIdCardReviewRequest request) {

        if (campaignRepository.existsBySchoolIdAndReviewTypeAndStatus(
                schoolId, REVIEW_TYPE_ID_CARD, "ACTIVE")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An active ID Card Details review campaign already exists for this school");
        }

        var activeYear = academicYearRepository.findFirstByActiveTrue()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "No active academic year"));

        StudentReviewCampaignEntity campaign = new StudentReviewCampaignEntity();
        campaign.setId(UUID.randomUUID().toString());
        campaign.setSchoolId(schoolId);
        campaign.setAcademicYearId(activeYear.getId());
        campaign.setReviewType(REVIEW_TYPE_ID_CARD);
        campaign.setTitle("ID Card Details Review — " + activeYear.getLabel());
        campaign.setStatus("ACTIVE");
        campaign.setInitiatedBy(actor.userId());
        campaign.setInitiatedAt(OffsetDateTime.now());
        campaign.setDueDate(request.dueDate());
        campaignRepository.save(campaign);

        List<StudentEntity> students = resolveStudents(schoolId, request.classIds(), request.sectionIds());

        for (StudentEntity student : students) {
            StudentReviewItemEntity item = new StudentReviewItemEntity();
            item.setId(UUID.randomUUID().toString());
            item.setCampaign(campaign);
            item.setStudent(student);
            item.setSchoolId(schoolId);
            item.setAssignedToUserId(request.assignedToUserId());
            item.setStatus("PENDING");
            item.setCurrentFullName(student.getFullName());
            itemRepository.save(item);
        }

        auditLogService.recordEvent("ID_CARD_REVIEW_INITIATED",
                actor.userId(), schoolId, "student_review_campaigns",
                campaign.getId(), null,
                "{\"campaignId\":\"" + campaign.getId() + "\",\"studentCount\":" + students.size() + "}");

        return buildIdCardStatus(campaign);
    }

    @Transactional(readOnly = true)
    public IdCardReviewStatusResponse getIdCardReviewStatus(Long schoolId) {
        return campaignRepository.findFirstBySchoolIdAndReviewTypeAndStatus(
                        schoolId, REVIEW_TYPE_ID_CARD, "ACTIVE")
                .map(this::buildIdCardStatus)
                .orElseGet(() -> new IdCardReviewStatusResponse(
                        null, 0, 0, 0, 0, 0.0, List.of()));
    }

    @Transactional(readOnly = true)
    public Page<ReviewItemDetail> getCampaignItems(
            Long schoolId, String campaignId, String status, String classId, String sectionId,
            int page, int size) {

        // Validate campaign belongs to school
        StudentReviewCampaignEntity campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found"));
        if (!schoolId.equals(campaign.getSchoolId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cross-school access denied");
        }

        return itemRepository.findByCampaignAndSchool(
                        campaignId, schoolId, classId, sectionId, status, PageRequest.of(page, size))
                .map(this::toReviewItemDetail);
    }

    @Transactional
    public ReviewItemDetail updateReviewItem(
            Long schoolId, String itemId, AuthUser actor, UpdateReviewItemRequest request) {

        StudentReviewItemEntity item = itemRepository.findByIdAndSchoolId(itemId, schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Review item not found"));

        if (request.verifiedPhoto() != null)         item.setVerifiedPhoto(request.verifiedPhoto());
        if (request.verifiedFullName() != null)      item.setVerifiedFullName(request.verifiedFullName());
        if (request.verifiedAdmissionNo() != null)   item.setVerifiedAdmissionNo(request.verifiedAdmissionNo());
        if (request.verifiedClassSection() != null)  item.setVerifiedClassSection(request.verifiedClassSection());
        if (request.verifiedRollNo() != null)        item.setVerifiedRollNo(request.verifiedRollNo());
        if (request.verifiedFatherName() != null)    item.setVerifiedFatherName(request.verifiedFatherName());
        if (request.verifiedFatherContact() != null) item.setVerifiedFatherContact(request.verifiedFatherContact());
        if (request.verifiedAddress() != null)       item.setVerifiedAddress(request.verifiedAddress());
        if (request.verifiedBloodGroup() != null)    item.setVerifiedBloodGroup(request.verifiedBloodGroup());
        if (request.correctionNotes() != null)       item.setCorrectionNotes(request.correctionNotes());

        // Auto-determine status
        boolean hasCorrection = item.getCorrectionNotes() != null && !item.getCorrectionNotes().isBlank();
        boolean allRequired = item.isVerifiedPhoto() && item.isVerifiedFullName()
                && item.isVerifiedAdmissionNo() && item.isVerifiedClassSection()
                && item.isVerifiedRollNo() && item.isVerifiedFatherName()
                && item.isVerifiedFatherContact() && item.isVerifiedAddress();

        if (request.status() != null) {
            item.setStatus(request.status());
        } else if (hasCorrection) {
            item.setStatus("NEEDS_CORRECTION");
        } else if (allRequired) {
            item.setStatus("COMPLETED");
            item.setCompletedAt(OffsetDateTime.now());
        }

        item.setUpdatedAt(OffsetDateTime.now());
        itemRepository.save(item);

        auditLogService.recordEvent("ID_CARD_REVIEW_ITEM_UPDATED",
                actor.userId(), schoolId, "student_review_items",
                itemId, null, "{\"status\":\"" + item.getStatus() + "\"}");

        return toReviewItemDetail(item);
    }

    // ── Full Name Verification ────────────────────────────────────────────────

    @Transactional
    public FullNameVerificationStatusResponse initiateFullNameVerification(
            Long schoolId, AuthUser actor, InitiateFullNameVerificationRequest request) {

        if (campaignRepository.existsBySchoolIdAndReviewTypeAndStatus(
                schoolId, REVIEW_TYPE_FULL_NAME, "ACTIVE")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An active Full Name Verification campaign already exists for this school");
        }

        var activeYear = academicYearRepository.findFirstByActiveTrue()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "No active academic year"));

        StudentReviewCampaignEntity campaign = new StudentReviewCampaignEntity();
        campaign.setId(UUID.randomUUID().toString());
        campaign.setSchoolId(schoolId);
        campaign.setAcademicYearId(activeYear.getId());
        campaign.setReviewType(REVIEW_TYPE_FULL_NAME);
        campaign.setTitle("Full Name Verification — " + activeYear.getLabel());
        campaign.setStatus("ACTIVE");
        campaign.setVerifier(request.verifier());
        campaign.setInitiatedBy(actor.userId());
        campaign.setInitiatedAt(OffsetDateTime.now());
        campaign.setDueDate(request.dueDate());
        campaignRepository.save(campaign);

        List<StudentEntity> students = resolveStudents(schoolId, request.classIds(), request.sectionIds());

        for (StudentEntity student : students) {
            StudentReviewItemEntity item = new StudentReviewItemEntity();
            item.setId(UUID.randomUUID().toString());
            item.setCampaign(campaign);
            item.setStudent(student);
            item.setSchoolId(schoolId);
            item.setStatus("PENDING");
            item.setCurrentFullName(student.getFullName());
            itemRepository.save(item);
        }

        auditLogService.recordEvent("FULL_NAME_VERIFICATION_INITIATED",
                actor.userId(), schoolId, "student_review_campaigns",
                campaign.getId(), null,
                "{\"campaignId\":\"" + campaign.getId() + "\",\"verifier\":\"" + request.verifier()
                        + "\",\"studentCount\":" + students.size() + "}");

        return buildFnvStatus(campaign);
    }

    @Transactional(readOnly = true)
    public FullNameVerificationStatusResponse getFullNameVerificationStatus(Long schoolId) {
        return campaignRepository.findFirstBySchoolIdAndReviewTypeAndStatus(
                        schoolId, REVIEW_TYPE_FULL_NAME, "ACTIVE")
                .map(this::buildFnvStatus)
                .orElseGet(() -> new FullNameVerificationStatusResponse(
                        null, 0, 0, 0, 0, 0.0));
    }

    @Transactional
    public ReviewItemDetail verifyFullName(
            Long schoolId, String itemId, AuthUser actor, VerifyFullNameRequest request) {

        StudentReviewItemEntity item = itemRepository.findByIdAndSchoolId(itemId, schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Review item not found"));

        String verifier = item.getCampaign().getVerifier();

        if (request.confirmed()) {
            if ("TEACHER".equals(verifier) || "BOTH".equals(verifier)) item.setTeacherConfirmed(true);
            if ("PARENT".equals(verifier)) item.setParentConfirmed(true);

            boolean done = switch (verifier != null ? verifier : "TEACHER") {
                case "PARENT" -> item.isParentConfirmed();
                case "BOTH"   -> item.isTeacherConfirmed() && item.isParentConfirmed();
                default       -> item.isTeacherConfirmed();
            };
            if (done) {
                item.setStatus("COMPLETED");
                item.setCompletedAt(OffsetDateTime.now());
            }

            auditLogService.recordEvent("FULL_NAME_VERIFICATION_CONFIRMED",
                    actor.userId(), schoolId, "student_review_items",
                    itemId, null, "{\"studentId\":" + item.getStudent().getId() + "}");
        } else {
            item.setCorrectionRequested(true);
            item.setSuggestedFullName(request.suggestedFullName());
            item.setCorrectionNotes(request.correctionNotes());
            item.setStatus("NEEDS_CORRECTION");

            auditLogService.recordEvent("FULL_NAME_CORRECTION_REQUESTED",
                    actor.userId(), schoolId, "student_review_items",
                    itemId, null,
                    "{\"studentId\":" + item.getStudent().getId()
                            + ",\"suggested\":\"" + request.suggestedFullName() + "\"}");
        }

        item.setUpdatedAt(OffsetDateTime.now());
        itemRepository.save(item);

        return toReviewItemDetail(item);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private List<StudentEntity> resolveStudents(Long schoolId,
                                                 List<String> classIds,
                                                 List<String> sectionIds) {
        List<StudentEntity> all = studentRepository.findBySchool_IdOrderByFullNameAsc(schoolId);
        return all.stream()
                .filter(s -> (classIds == null || classIds.isEmpty()
                        || classIds.contains(s.getSchoolClass().getId())))
                .filter(s -> (sectionIds == null || sectionIds.isEmpty()
                        || sectionIds.contains(s.getSection().getId())))
                .collect(Collectors.toList());
    }

    private IdCardReviewStatusResponse buildIdCardStatus(StudentReviewCampaignEntity campaign) {
        long total    = itemRepository.countByCampaign_Id(campaign.getId());
        long completed = itemRepository.countByCampaign_IdAndStatus(campaign.getId(), "COMPLETED");
        long needsCorrection = itemRepository.countByCampaign_IdAndStatus(campaign.getId(), "NEEDS_CORRECTION");
        long pending  = total - completed - needsCorrection;
        double pct    = total == 0 ? 0.0 : Math.round((completed * 10000.0 / total)) / 100.0;

        return new IdCardReviewStatusResponse(campaign.getId(), total, completed,
                Math.max(0, pending), needsCorrection, pct, List.of());
    }

    private FullNameVerificationStatusResponse buildFnvStatus(StudentReviewCampaignEntity campaign) {
        long total = itemRepository.countByCampaign_Id(campaign.getId());
        long confirmed = itemRepository.countByCampaign_IdAndStatus(campaign.getId(), "COMPLETED");
        long correction = itemRepository.countByCampaign_IdAndStatus(campaign.getId(), "NEEDS_CORRECTION");
        long pending = total - confirmed - correction;
        double pct = total == 0 ? 0.0 : Math.round((confirmed * 10000.0 / total)) / 100.0;

        return new FullNameVerificationStatusResponse(campaign.getId(), total, confirmed,
                correction, Math.max(0, pending), pct);
    }

    private ReviewItemDetail toReviewItemDetail(StudentReviewItemEntity item) {
        var student = item.getStudent();
        return new ReviewItemDetail(
                item.getId(), student.getId(), student.getFullName(), student.getAdmissionNo(),
                student.getSchoolClass().getName(), student.getSection().getName(),
                item.getCurrentFullName(), item.getSuggestedFullName(),
                item.getStatus(),
                item.isVerifiedPhoto(), item.isVerifiedFullName(), item.isVerifiedAdmissionNo(),
                item.isVerifiedClassSection(), item.isVerifiedRollNo(), item.isVerifiedFatherName(),
                item.isVerifiedFatherContact(), item.isVerifiedAddress(), item.isVerifiedBloodGroup(),
                item.isParentConfirmed(), item.isTeacherConfirmed(), item.isCorrectionRequested(),
                item.getCorrectionNotes(), item.getCompletedAt()
        );
    }
}
