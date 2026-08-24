package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.photoimport.DrivePhotoImportException;
import com.custoking.ims.schoolcoreservice.photoimport.PhotoImportRepository.Batch;
import com.custoking.ims.schoolcoreservice.photoimport.PhotoImportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/student-photo-imports")
public class StudentPhotoImportController {
    private static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
    private final PhotoImportService service;
    private final String studentToken;

    public StudentPhotoImportController(
            PhotoImportService service,
            @Value("${student.read-token:}") String studentToken) {
        this.service = service;
        this.studentToken = studentToken == null ? "" : studentToken.trim();
    }

    @GetMapping("/context")
    public Map<String, Object> context(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token) {
        requireToken(token, "student:read");
        return service.context();
    }

    @PostMapping
    public Batch create(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @Valid @RequestBody CreatePhotoImportRequest request) {
        requireToken(token, "student:write");
        return service.create(request.schoolId(), request.academicYearId(), request.driveFolderUrl());
    }

    @PostMapping("/folders/{schoolId}/provision")
    public Object provision(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable Long schoolId) {
        requireToken(token, "student:write");
        return service.provision(schoolId);
    }

    @GetMapping
    public List<Batch> list(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @RequestParam Long schoolId) {
        requireToken(token, "student:read");
        return service.list(schoolId);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable UUID id) {
        requireToken(token, "student:read");
        return service.detail(id);
    }

    @PostMapping("/{id}/scan")
    public Batch scan(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable UUID id) {
        requireToken(token, "student:write");
        return service.scan(id);
    }

    @PostMapping("/{id}/freeze")
    public Batch freeze(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable UUID id) {
        requireToken(token, "student:write");
        return service.freeze(id);
    }

    @PostMapping("/{batchId}/rows/{rowId}")
    public Map<String, Object> updateRow(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable UUID batchId,
            @PathVariable UUID rowId,
            @Valid @RequestBody RowReviewRequest request) {
        requireToken(token, "student:write");
        return service.updateRow(
                batchId,
                rowId,
                new PhotoImportService.RowReviewUpdate(
                        request.admissionNo(),
                        request.imageNo(),
                        request.excluded(),
                        request.cropX(),
                        request.cropY()));
    }

    @PostMapping("/{id}/cancel")
    public Batch cancel(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable UUID id) {
        requireToken(token, "student:write");
        return service.cancel(id);
    }

    @PostMapping("/{id}/access-revoked")
    public Object markAccessRevoked(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable UUID id) {
        requireToken(token, "student:write");
        return service.markAccessRevoked(id);
    }

    @PostMapping("/{id}/execute")
    public Batch execute(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable UUID id) {
        requireToken(token, "student:write");
        return service.execute(id);
    }

    @PostMapping("/{id}/recover")
    public PhotoImportService.RecoveryBatchResult recover(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable UUID id,
            @Valid @RequestBody RecoverAppliedPhotosRequest request) {
        requireToken(token, "student:write");
        return service.recoverAppliedRows(id, request.rowIds());
    }

    @GetMapping("/{batchId}/rows/{rowId}/preview")
    public ResponseEntity<byte[]> preview(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable UUID batchId,
            @PathVariable UUID rowId) {
        requireToken(token, "student:read");
        var preview = service.preview(batchId, rowId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_JPEG)
                .body(preview.bytes());
    }

    @GetMapping("/{id}/result")
    public ResponseEntity<byte[]> result(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable UUID id) {
        requireToken(token, "student:read");
        byte[] csv = service.resultCsv(id).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=student-photo-import-" + id + ".csv")
                .header(X_CONTENT_TYPE_OPTIONS, "nosniff")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .contentLength(csv.length)
                .body(csv);
    }

    @ExceptionHandler(DrivePhotoImportException.class)
    public ResponseEntity<Map<String, Object>> driveError(DrivePhotoImportException ex) {
        HttpStatus status = switch (ex.code()) {
            case "source_changed", "source_already_imported" -> HttpStatus.CONFLICT;
            case "drive_not_configured" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "drive_access_denied", "not_a_folder", "file_too_large" -> HttpStatus.UNPROCESSABLE_CONTENT;
            case "drive_rate_limited" -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).body(Map.of("code", ex.code(), "message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> conflict(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", "This Google Drive folder already has an active photo import batch"));
    }

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing internal route scope");
        }
        if (!StringUtils.hasText(studentToken) || !studentToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid student service token");
        }
    }

    public record CreatePhotoImportRequest(
            @NotNull Long schoolId,
            @NotBlank String academicYearId,
            String driveFolderUrl) {
    }

    public record RowReviewRequest(
            String admissionNo,
            String imageNo,
            Boolean excluded,
            @DecimalMin("0.0") @DecimalMax("1.0") Double cropX,
            @DecimalMin("0.0") @DecimalMax("1.0") Double cropY) {
    }

    public record RecoverAppliedPhotosRequest(
            @NotEmpty @Size(max = 100) List<@NotNull UUID> rowIds) {
    }
}
