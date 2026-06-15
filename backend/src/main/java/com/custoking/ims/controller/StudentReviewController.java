package com.custoking.ims.controller;

import com.custoking.ims.commandcenter.StudentReviewService;
import com.custoking.ims.commandcenter.dto.*;
import com.custoking.ims.common.domain.PermissionConstants;
import com.custoking.ims.context.TenantContext;
import com.custoking.ims.model.AuthUser;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class StudentReviewController {

    private final StudentReviewService studentReviewService;

    public StudentReviewController(StudentReviewService studentReviewService) {
        this.studentReviewService = studentReviewService;
    }

    // ── ID Card Review ────────────────────────────────────────────────────────

    @PostMapping("/api/v1/dashboard/student-lifecycle/id-card-review/initiate")
    @PreAuthorize(PermissionConstants.STUDENT_REVIEW_INITIATE)
    public IdCardReviewStatusResponse initiateIdCardReview(
            @Valid @RequestBody InitiateIdCardReviewRequest request,
            @AuthenticationPrincipal AuthUser actor) {
        return studentReviewService.initiateIdCardReview(TenantContext.get(), actor, request);
    }

    @GetMapping("/api/v1/dashboard/student-lifecycle/id-card-review/status")
    @PreAuthorize(PermissionConstants.STUDENT_REVIEW_INITIATE)
    public IdCardReviewStatusResponse getIdCardReviewStatus() {
        return studentReviewService.getIdCardReviewStatus(TenantContext.get());
    }

    // ── Full Name Verification ────────────────────────────────────────────────

    @PostMapping("/api/v1/dashboard/student-lifecycle/full-name-verification/initiate")
    @PreAuthorize(PermissionConstants.STUDENT_REVIEW_INITIATE)
    public FullNameVerificationStatusResponse initiateFullNameVerification(
            @Valid @RequestBody InitiateFullNameVerificationRequest request,
            @AuthenticationPrincipal AuthUser actor) {
        return studentReviewService.initiateFullNameVerification(TenantContext.get(), actor, request);
    }

    @GetMapping("/api/v1/dashboard/student-lifecycle/full-name-verification/status")
    @PreAuthorize(PermissionConstants.STUDENT_REVIEW_INITIATE)
    public FullNameVerificationStatusResponse getFullNameVerificationStatus() {
        return studentReviewService.getFullNameVerificationStatus(TenantContext.get());
    }

    // ── Campaign items (shared) ───────────────────────────────────────────────

    @GetMapping("/api/v1/student-review-campaigns/{campaignId}/items")
    @PreAuthorize(PermissionConstants.STUDENT_REVIEW_INITIATE)
    public Page<ReviewItemDetail> getCampaignItems(
            @PathVariable String campaignId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String classId,
            @RequestParam(required = false) String sectionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return studentReviewService.getCampaignItems(
                TenantContext.get(), campaignId, status, classId, sectionId, page, size);
    }

    @PutMapping("/api/v1/student-review-items/{itemId}")
    @PreAuthorize(PermissionConstants.STUDENT_REVIEW_UPDATE)
    public ReviewItemDetail updateReviewItem(
            @PathVariable String itemId,
            @RequestBody UpdateReviewItemRequest request,
            @AuthenticationPrincipal AuthUser actor) {
        return studentReviewService.updateReviewItem(TenantContext.get(), itemId, actor, request);
    }

    @PutMapping("/api/v1/student-review-items/{itemId}/full-name-verification")
    @PreAuthorize(PermissionConstants.STUDENT_REVIEW_UPDATE)
    public ReviewItemDetail verifyFullName(
            @PathVariable String itemId,
            @RequestBody VerifyFullNameRequest request,
            @AuthenticationPrincipal AuthUser actor) {
        return studentReviewService.verifyFullName(TenantContext.get(), itemId, actor, request);
    }
}
