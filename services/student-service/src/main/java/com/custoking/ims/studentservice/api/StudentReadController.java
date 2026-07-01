package com.custoking.ims.studentservice.api;

import com.custoking.ims.studentservice.persistence.StudentReadRepository;
import com.custoking.ims.studentservice.persistence.StudentReadRepository.StudentRow;
import com.custoking.ims.studentservice.security.TenantScope;
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
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/students")
public class StudentReadController {

    private final StudentReadRepository students;
    private final String readToken;

    public StudentReadController(
            StudentReadRepository students,
            @Value("${student.read-token:}") String readToken) {
        this.students = students;
        this.readToken = readToken == null ? "" : readToken.trim();
    }

    @GetMapping
    public StudentListResponse list(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String classId,
            @RequestParam(required = false) String sectionId,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "student:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return new StudentListResponse(
                students.list(scope, classId, sectionId, limit),
                students.count(scope));
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
            @RequestBody Map<String, Object> request) {
        requireToken(token, "student:write");
        applyResolvedSchool(request);
        return execute(() -> students.createStudent(request));
    }

    @PostMapping("/{id}/photo")
    public Map<String, Object> attachPhoto(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable Long id,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "student:write");
        return execute(() -> students.attachPhoto(id, request));
    }

    @PostMapping("/imports/preview")
    public Map<String, Object> previewImport(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "student:write");
        applyResolvedSchool(request);
        return execute(() -> students.previewImport(request));
    }

    @PostMapping("/import/preview")
    public Map<String, Object> previewLegacyImport(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> request) {
        return previewImport(token, request);
    }

    @PostMapping("/imports/confirm")
    public Map<String, Object> confirmImport(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "student:write");
        applyResolvedSchool(request);
        return execute(() -> students.confirmImport(request));
    }

    @PostMapping("/import/confirm")
    public Map<String, Object> confirmLegacyImport(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> request) {
        return confirmImport(token, request);
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
            @RequestBody Map<String, Object> request) {
        requireToken(token, "student:write");
        applyResolvedSchool(request);
        return execute(() -> students.initiateIdCardReview(request));
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
            @RequestBody Map<String, Object> request) {
        requireToken(token, "student:write");
        applyResolvedSchool(request);
        return execute(() -> students.initiateFullNameVerification(request));
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
            @RequestParam Long schoolId,
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

