package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.api.dto.AttachPhotoRequest;
import com.custoking.ims.schoolcoreservice.api.dto.ConfirmImportRequest;
import com.custoking.ims.schoolcoreservice.api.dto.CreateStudentRequest;
import com.custoking.ims.schoolcoreservice.api.dto.InitiateIdCardReviewRequest;
import com.custoking.ims.schoolcoreservice.api.dto.InitiateFullNameReviewRequest;
import com.custoking.ims.schoolcoreservice.api.dto.PreviewImportRequest;
import com.custoking.ims.schoolcoreservice.infrastructure.ImageFetchException;
import com.custoking.ims.schoolcoreservice.infrastructure.ImageUrlFetcher;
import com.custoking.ims.schoolcoreservice.persistence.CampaignCompletedException;
import com.custoking.ims.schoolcoreservice.persistence.StudentReadRepository;
import com.custoking.ims.schoolcoreservice.persistence.StudentReadRepository.StudentRow;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.security.TenantScope;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/students")
public class StudentReadController {

    private final StudentReadRepository students;
    private final ImageUrlFetcher fetcher;
    private final String readToken;

    public StudentReadController(
            StudentReadRepository students,
            ImageUrlFetcher fetcher,
            @Value("${student.read-token:}") String readToken) {
        this.students = students;
        this.fetcher = fetcher;
        this.readToken = readToken == null ? "" : readToken.trim();
    }

    // The Students workspace grid + its class/section filter dropdowns consume this response's
    // {items, filters:{classes,sections,feeStatuses}, ...}. The frontend sends class/section/feeStatus.
    @GetMapping
    public Map<String, Object> list(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(name = "class", required = false) String className,
            @RequestParam(name = "section", required = false) String sectionName,
            @RequestParam(required = false) String feeStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "500") int size) {
        requireToken(token, "student:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return students.workspaceStudents(scope, className, sectionName, feeStatus, page, size);
    }

    @GetMapping("/{id}")
    public StudentRow get(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable Long id) {
        requireToken(token, "student:read");
        return students.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "student not found"));
    }

    @GetMapping("/workspace")
    public Map<String, Object> workspaceStudents(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @RequestParam Long schoolId,
            @RequestParam(required = false) String className,
            @RequestParam(required = false) String sectionName,
            @RequestParam(required = false) String feeStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "500") int size) {
        requireToken(token, "student:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return students.workspaceStudents(scope, className, sectionName, feeStatus, page, size);
    }

    @GetMapping("/{id}/workspace")
    public Map<String, Object> workspaceStudent(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable Long id) {
        requireToken(token, "student:read");
        return execute(() -> students.workspaceStudentDetail(id));
    }

    @PostMapping
    public Map<String, Object> create(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @Valid @RequestBody CreateStudentRequest req) {
        requireToken(token, "student:write");
        Map<String, Object> params = new HashMap<>();
        params.put("admissionNumber", req.admissionNumber());
        params.put("admissionNo", req.admissionNumber());
        params.put("fullName", req.fullName());
        params.put("schoolId", req.schoolId());
        if (req.classId() != null) params.put("classId", req.classId());
        if (req.sectionId() != null) params.put("sectionId", req.sectionId());
        if (req.gradeLevel() != null) params.put("gradeLevel", req.gradeLevel());
        if (req.className() != null) params.put("className", req.className());
        if (req.sectionName() != null) params.put("sectionName", req.sectionName());
        if (req.rollNo() != null) params.put("rollNo", req.rollNo());
        if (req.boardRegistrationNumber() != null) params.put("boardRegistrationNumber", req.boardRegistrationNumber());
        if (req.dateOfBirth() != null) params.put("dateOfBirth", req.dateOfBirth());
        if (req.gender() != null) params.put("gender", req.gender());
        if (req.fatherName() != null) params.put("fatherName", req.fatherName());
        if (req.fatherContactNumber() != null) params.put("fatherContactNumber", req.fatherContactNumber());
        if (req.fatherContact() != null) params.put("fatherContact", req.fatherContact());
        if (req.motherName() != null) params.put("motherName", req.motherName());
        if (req.phone() != null) params.put("phone", req.phone());
        if (req.houseNumber() != null) params.put("houseNumber", req.houseNumber());
        if (req.street() != null) params.put("street", req.street());
        if (req.locality() != null) params.put("locality", req.locality());
        if (req.city() != null) params.put("city", req.city());
        if (req.state() != null) params.put("state", req.state());
        if (req.pinCode() != null) params.put("pinCode", req.pinCode());
        if (req.photoUrl() != null) params.put("photoUrl", req.photoUrl());
        applyResolvedSchool(params);
        return execute(() -> students.createStudent(params));
    }

    @PostMapping("/{id}/photo")
    public Map<String, Object> attachPhoto(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) {
        requireToken(token, "student:write");
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the uploaded photo", ex);
        }
        return execute(() -> students.attachPhoto(id, data, file.getContentType()));
    }

    @PostMapping("/{id}/photo-from-url")
    public ResponseEntity<Map<String, Object>> attachPhotoFromUrl(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        requireToken(token, "student:write");
        Long schoolId;
        try {
            schoolId = students.schoolIdForStudent(id);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "student not found", ex);
        }
        TenantScope.resolveSchoolId(schoolId);
        String url = body.get("url") == null ? "" : String.valueOf(body.get("url"));
        try {
            ImageUrlFetcher.FetchedImage img = fetcher.fetch(url);
            return ResponseEntity.ok(execute(() -> students.attachPhoto(id, img.data(), img.contentType())));
        } catch (ImageFetchException ex) {
            return ResponseEntity.unprocessableEntity().body(Map.of("reason", ex.reason(), "ok", false));
        }
    }

    @PostMapping("/imports/preview")
    public Map<String, Object> previewImport(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @Valid @RequestBody PreviewImportRequest req) {
        requireToken(token, "student:write");
        Map<String, Object> params = new HashMap<>();
        if (req.schoolId() != null) params.put("schoolId", req.schoolId());
        if (req.rows() != null) params.put("rows", req.rows());
        applyResolvedSchool(params);
        return execute(() -> students.previewImport(params));
    }

    @PostMapping("/import/preview")
    public Map<String, Object> previewLegacyImport(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @Valid @RequestBody PreviewImportRequest req) {
        return previewImport(token, req);
    }

    @PostMapping("/imports/confirm")
    public Map<String, Object> confirmImport(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @Valid @RequestBody ConfirmImportRequest req) {
        requireToken(token, "student:write");
        Map<String, Object> params = new HashMap<>();
        params.put("fileToken", req.fileToken());
        if (req.schoolId() != null) params.put("schoolId", req.schoolId());
        applyResolvedSchool(params);
        return execute(() -> students.confirmImport(params));
    }

    @PostMapping("/import/confirm")
    public Map<String, Object> confirmLegacyImport(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @Valid @RequestBody ConfirmImportRequest req) {
        return confirmImport(token, req);
    }

    @GetMapping("/imports/status/{jobId}")
    public Map<String, Object> importStatus(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable String jobId) {
        requireToken(token, "student:read");
        return execute(() -> students.importStatus(jobId));
    }

    @GetMapping("/import/status/{jobId}")
    public Map<String, Object> legacyImportStatus(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable String jobId) {
        return importStatus(token, jobId);
    }

    @GetMapping(value = "/import/template", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> legacyImportTemplate(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token) {
        requireToken(token, "student:read");
        String body = "Name,Class,Section,AdmissionNo,DateOfBirth,Gender,FatherName,Phone,Address,BoardRegistrationNo\n"
                + "Aryan Mehta,Class 9,B,ADM-1001,2010-05-12,Male,R. Mehta,9876543210,Hyderabad,BRN1001\n";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=student-import-template.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/reviews/id-card/initiate")
    public Map<String, Object> initiateIdCardReview(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @Valid @RequestBody InitiateIdCardReviewRequest req) {
        requireToken(token, "student:write");
        Map<String, Object> params = new HashMap<>();
        params.put("schoolId", req.schoolId());
        params.put("actorId", TenantContext.get().userId());
        params.put("dueDate", req.dueDate());
        params.put("classIds", req.classIds());
        params.put("sectionIds", req.sectionIds());
        params.put("assignedToUserId", req.assignedToUserId());
        applyResolvedSchool(params);
        return execute(() -> students.initiateIdCardReview(params));
    }

    @GetMapping("/reviews/id-card/status")
    public Map<String, Object> idCardReviewStatus(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @RequestParam Long schoolId) {
        requireToken(token, "student:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return students.idCardReviewStatus(scope);
    }

    @PostMapping("/reviews/full-name/initiate")
    public Map<String, Object> initiateFullNameVerification(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @Valid @RequestBody InitiateFullNameReviewRequest req) {
        requireToken(token, "student:write");
        Map<String, Object> params = new HashMap<>();
        params.put("schoolId", req.schoolId());
        params.put("actorId", TenantContext.get().userId());
        params.put("dueDate", req.dueDate());
        params.put("verifier", req.verifier());
        params.put("classIds", req.classIds());
        params.put("sectionIds", req.sectionIds());
        applyResolvedSchool(params);
        return execute(() -> students.initiateFullNameVerification(params));
    }

    @GetMapping("/reviews/full-name/status")
    public Map<String, Object> fullNameVerificationStatus(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @RequestParam Long schoolId) {
        requireToken(token, "student:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return students.fullNameVerificationStatus(scope);
    }

    @PostMapping("/reviews/items/{itemId}")
    public Map<String, Object> updateReviewItem(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable String itemId,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "student:write");
        Long itemSchool = students.schoolIdForReviewItem(itemId);
        TenantScope.resolveSchoolId(itemSchool);
        return execute(() -> students.updateReviewItem(itemId, request));
    }

    @PostMapping("/reviews/items/{itemId}/full-name-verification")
    public Map<String, Object> verifyFullName(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable String itemId,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "student:write");
        Long itemSchool = students.schoolIdForReviewItem(itemId);
        TenantScope.resolveSchoolId(itemSchool);
        return execute(() -> students.verifyFullName(itemId, request));
    }

    @PostMapping("/review-campaigns/{campaignId}/complete")
    public Map<String, Object> completeCampaign(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable String campaignId) {
        requireToken(token, "student:write");
        Long campaignSchool = students.schoolIdForCampaign(campaignId);
        TenantScope.resolveSchoolId(campaignSchool);
        Long actorId = TenantContext.get().userId();
        return execute(() -> students.completeCampaign(campaignId, actorId));
    }

    @GetMapping("/imports/batches")
    public Object importBatches(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "student:read");
        return students.importBatches(limit);
    }

    @GetMapping("/imports/rows")
    public Object importRows(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @RequestParam(required = false) String batchId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "student:read");
        return students.importRows(batchId, status, limit);
    }

    @GetMapping("/review-campaigns")
    public Object reviewCampaigns(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String reviewType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "student:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return students.reviewCampaigns(scope, reviewType, status, limit);
    }

    @GetMapping("/review-items")
    public Object reviewItems(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @RequestParam(required = false) String campaignId,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "student:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return students.reviewItems(campaignId, scope, status, limit);
    }

    @GetMapping("/review-campaigns/{campaignId}/items")
    public Map<String, Object> campaignReviewItems(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable String campaignId,
            // Optional + resolved from JWT scope for admins (consistent with the sibling
            // /review-campaigns and /review-items endpoints); required=true previously 400'd every call.
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String classId,
            @RequestParam(required = false) String sectionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        requireToken(token, "student:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return students.campaignReviewItems(scope, campaignId, status, classId, sectionId, page, size);
    }

    private void applyResolvedSchool(Map<String, Object> request) {
        Long requested;
        if (request.get("schoolId") == null) {
            requested = null;
        } else {
            try {
                requested = Long.valueOf(String.valueOf(request.get("schoolId")));
            } catch (NumberFormatException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid schoolId");
            }
        }
        request.put("schoolId", TenantScope.resolveSchoolId(requested));
    }

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing internal route scope" );
        }
        if (!StringUtils.hasText(readToken) || !readToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid student service token");
        }
    }

    private Map<String, Object> execute(Command command) {
        try {
            return command.run();
        } catch (CampaignCompletedException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private interface Command {
        Map<String, Object> run();
    }

    public record StudentListResponse(List<StudentRow> content, long totalElements) {
    }
}

