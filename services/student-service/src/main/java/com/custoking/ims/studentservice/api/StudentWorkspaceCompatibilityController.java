package com.custoking.ims.studentservice.api;

import com.custoking.ims.studentservice.persistence.StudentReadRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
public class StudentWorkspaceCompatibilityController {

    private final StudentReadRepository students;
    private final String readToken;

    public StudentWorkspaceCompatibilityController(
            StudentReadRepository students,
            @Value("${student.read-token:}") String readToken) {
        this.students = students;
        this.readToken = readToken == null ? "" : readToken.trim();
    }

    @PostMapping("/api/v1/workspace/students")
    public Map<String, Object> createFromWorkspace(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "student:read");
        try {
            return students.createStudent(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/api/v1/classes/{classId}/sections/{sectionId}/students")
    public Object studentsForClassSection(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable String classId,
            @PathVariable String sectionId,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(defaultValue = "500") int limit) {
        requireToken(token, "student:read");
        return students.list(schoolId, classId, sectionId, limit);
    }

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope) || !StringUtils.hasText(readToken) || !readToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid student service token");
        }
    }
}
