package com.custoking.ims.controller;

import com.custoking.ims.common.domain.PermissionConstants;
import com.custoking.ims.service.StudentService;
import com.custoking.ims.service.UserContextService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/students")
@PreAuthorize(PermissionConstants.STUDENT_READ)
public class StudentPhotoController {
    private static final long MAX_SIZE = 2 * 1024 * 1024;
    private final UserContextService userContext;
    private final StudentService studentService;
    private final Path uploadDir = Paths.get("uploads", "student-photos");

    public StudentPhotoController(UserContextService userContext, StudentService studentService) {
        this.userContext = userContext;
        this.studentService = studentService;
    }

    @GetMapping
    public Map<String, Object> listStudents(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestParam(name = "class", required = false) String className,
                                            @RequestParam(name = "section", required = false) String sectionName,
                                            @RequestParam(name = "feeStatus", required = false) String feeStatus,
                                            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
                                            @RequestParam(name = "size", defaultValue = "100") @Min(1) @Max(500) int size,
                                            @RequestParam(name = "schoolId", required = false) Long schoolId) {
        var actor = userContext.requireUser(authorization);
        return studentService.studentsPage(className, sectionName, feeStatus, page, size, actor, schoolId);
    }

    @GetMapping("/{id}")
    public Map<String, Object> getStudent(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @PathVariable long id,
                                          @RequestParam(name = "schoolId", required = false) Long schoolId) {
        var actor = userContext.requireUser(authorization);
        return studentService.studentDetail(id, actor, schoolId);
    }

    @PostMapping(path = "/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(PermissionConstants.STUDENT_UPDATE)
    public Map<String, Object> uploadStudentPhoto(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                  @PathVariable long id,
                                                  @RequestParam("file") MultipartFile file) throws IOException {
        userContext.requireUser(authorization);
        validateFile(file);
        Files.createDirectories(uploadDir);
        String extension = getExtension(file.getOriginalFilename(), file.getContentType());
        String filename = "student-" + id + "-" + UUID.randomUUID() + "." + extension;
        Path target = uploadDir.resolve(filename).normalize();
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        String photoUrl = "/api/v1/students/photo/" + filename;
        Map<String, Object> student = studentService.attachStudentPhoto(id, photoUrl);
        return Map.of("message", "Photo uploaded successfully", "photoUrl", photoUrl, "student", student);
    }

    @GetMapping("/photo/{filename:.+}")
    public ResponseEntity<Resource> serveStudentPhoto(@PathVariable String filename) {
        Path file = uploadDir.resolve(filename).normalize();
        if (!Files.exists(file)) throw new IllegalArgumentException("Photo not found");
        Resource resource = new FileSystemResource(file);
        MediaType mediaType = resolveMediaType(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .contentType(mediaType)
                .body(resource);
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Please choose a photo to upload.");
        if (file.getSize() > MAX_SIZE) throw new IllegalArgumentException("Photo must be 2MB or smaller.");
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!contentType.equals("image/jpeg") && !contentType.equals("image/png") && !contentType.equals("image/webp"))
            throw new IllegalArgumentException("Only JPG, PNG, or WEBP files are allowed.");
    }

    private String getExtension(String originalFilename, String contentType) {
        String extension = StringUtils.getFilenameExtension(originalFilename);
        if (extension != null && !extension.isBlank()) {
            String normalized = extension.toLowerCase(Locale.ROOT);
            if (normalized.equals("jpg") || normalized.equals("jpeg") || normalized.equals("png") || normalized.equals("webp"))
                return normalized.equals("jpeg") ? "jpg" : normalized;
        }
        if ("image/png".equalsIgnoreCase(contentType)) return "png";
        if ("image/webp".equalsIgnoreCase(contentType)) return "webp";
        return "jpg";
    }

    private MediaType resolveMediaType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (name.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        return MediaType.IMAGE_JPEG;
    }
}
