package com.custoking.ims.controller;

import com.custoking.ims.service.DatabaseStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/students")
public class StudentPhotoController {
    private static final long MAX_SIZE = 2 * 1024 * 1024;
    private final DatabaseStore store;
    private final Path uploadDir = Paths.get("uploads", "student-photos");

    public StudentPhotoController(DatabaseStore store) {
        this.store = store;
    }

    @GetMapping
    public Map<String, Object> listStudents(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestParam(name = "class", required = false) String className,
                                            @RequestParam(name = "section", required = false) String sectionName,
                                            @RequestParam(name = "feeStatus", required = false) String feeStatus,
                                            @RequestParam(name = "page", defaultValue = "0") int page,
                                            @RequestParam(name = "size", defaultValue = "100") int size,
                                            @RequestParam(name = "schoolId", required = false) Long schoolId) {
        var actor = store.requireUser(authorization);
        return store.studentsPage(className, sectionName, feeStatus, page, size, actor, schoolId);
    }

    @GetMapping("/{id}")
    public Map<String, Object> getStudent(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @PathVariable long id,
                                          @RequestParam(name = "schoolId", required = false) Long schoolId) {
        var actor = store.requireUser(authorization);
        return store.studentDetail(id, actor, schoolId);
    }

    @PostMapping(path = "/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadStudentPhoto(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                  @PathVariable long id,
                                                  @RequestParam("file") MultipartFile file) throws IOException {
        store.requireUser(authorization);
        validateFile(file);
        Files.createDirectories(uploadDir);

        String extension = getExtension(file.getOriginalFilename(), file.getContentType());
        String filename = "student-" + id + "-" + UUID.randomUUID() + "." + extension;
        Path target = uploadDir.resolve(filename).normalize();
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

        String photoUrl = "/api/students/photo/" + filename;
        Map<String, Object> student = store.attachStudentPhoto(id, photoUrl);
        return Map.of(
                "message", "Photo uploaded successfully",
                "photoUrl", photoUrl,
                "student", student
        );
    }

    @GetMapping("/photo/{filename:.+}")
    public ResponseEntity<Resource> serveStudentPhoto(@PathVariable String filename) {
        Path file = uploadDir.resolve(filename).normalize();
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("Photo not found");
        }
        Resource resource = new FileSystemResource(file);
        MediaType mediaType = resolveMediaType(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .contentType(mediaType)
                .body(resource);
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Please choose a photo to upload.");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new IllegalArgumentException("Photo must be 2MB or smaller.");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!contentType.equals("image/jpeg") && !contentType.equals("image/png") && !contentType.equals("image/webp")) {
            throw new IllegalArgumentException("Only JPG, PNG, or WEBP files are allowed.");
        }
    }

    private String getExtension(String originalFilename, String contentType) {
        String extension = StringUtils.getFilenameExtension(originalFilename);
        if (extension != null && !extension.isBlank()) {
            String normalized = extension.toLowerCase(Locale.ROOT);
            if (normalized.equals("jpg") || normalized.equals("jpeg") || normalized.equals("png") || normalized.equals("webp")) {
                return normalized.equals("jpeg") ? "jpg" : normalized;
            }
        }
        if ("image/png".equalsIgnoreCase(contentType)) return "png";
        if ("image/webp".equalsIgnoreCase(contentType)) return "webp";
        return "jpg";
    }

    private MediaType resolveMediaType(Path file) {
        String filename = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (filename.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (filename.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        return MediaType.IMAGE_JPEG;
    }
}
