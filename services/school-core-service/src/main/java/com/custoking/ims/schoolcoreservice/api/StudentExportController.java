package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.security.TenantScope;
import com.custoking.ims.schoolcoreservice.studentexport.StudentExportRepository.SchoolOption;
import com.custoking.ims.schoolcoreservice.studentexport.StudentExportService;
import com.custoking.ims.schoolcoreservice.studentexport.StudentExportService.PreparedExport;
import com.custoking.ims.schoolcoreservice.studentexport.StudentExportService.ExportProgress;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/students/export")
public class StudentExportController {

    private final StudentExportService exports;
    private final String studentToken;

    public StudentExportController(
            StudentExportService exports,
            @Value("${student.read-token:}") String studentToken) {
        this.exports = exports;
        this.studentToken = studentToken == null ? "" : studentToken.trim();
    }

    @GetMapping("/context")
    public Map<String, Object> context(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token) {
        authorize(token);
        List<SchoolOption> schools = exports.allowedSchools();
        return Map.of(
                "schools", schools,
                "fileNameRule", "Each exported photo is named with the student's admission number.",
                "workbookFileName", "Student-Details.xlsx");
    }

    @GetMapping(value = "/archive", produces = "application/zip")
    public void archive(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @RequestParam Long schoolId,
            HttpServletResponse response) throws IOException {
        authorize(token);
        Long scope = TenantScope.resolvePlatformReadScope(schoolId);
        if (scope == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "schoolId is required");
        }
        try (PreparedExport prepared = exports.prepare(scope)) {
            String filename = archiveFileName(prepared.data().school().shortCode());
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/zip");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + filename + "\"; filename*=UTF-8''"
                            + URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20"));
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, max-age=0");
            response.setHeader(HttpHeaders.PRAGMA, "no-cache");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("X-Student-Count", String.valueOf(prepared.data().students().size()));
            response.setHeader("X-Student-Export-Id", prepared.auditId().toString());
            exports.write(prepared, response.getOutputStream());
        }
    }

    @GetMapping("/{exportId}/progress")
    public ExportProgress progress(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable UUID exportId,
            @RequestParam Long schoolId) {
        authorize(token);
        Long scope = TenantScope.resolvePlatformReadScope(schoolId);
        if (scope == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "schoolId is required");
        }
        return exports.progress(exportId, scope);
    }

    private void authorize(String token) {
        if (!StringUtils.hasText(studentToken) || !studentToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid student service token");
        }
        TenantScope.requireOperationsOrSuperAdmin();
        TenantScope.requirePermission("student:export");
    }

    static String archiveFileName(String shortCode) {
        String safe = StringUtils.hasText(shortCode) ? shortCode.trim() : "school";
        safe = safe.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("(^-+|-+$)", "");
        if (safe.isBlank()) safe = "school";
        return safe + "-student-details-and-photos-" + LocalDate.now() + ".zip";
    }
}
