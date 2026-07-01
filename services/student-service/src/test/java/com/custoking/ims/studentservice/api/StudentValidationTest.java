package com.custoking.ims.studentservice.api;

import com.custoking.ims.studentservice.persistence.StudentReadRepository;
import com.custoking.ims.studentservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StudentValidationTest {

    private static final String VALID_TOKEN = "student-token";

    StudentReadRepository repo;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        repo = mock(StudentReadRepository.class);
        StudentReadController controller = new StudentReadController(repo, VALID_TOKEN);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ValidationExceptionHandler())
                .build();
        // Use SUPERADMIN so TenantScope passes through provided schoolId
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // --- POST / (create student) ---

    @Test
    void createStudent_blankAdmissionNumber_returns400() throws Exception {
        mvc.perform(post("/api/v1/students")
                        .header("X-Student-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"admissionNumber\":\"\",\"fullName\":\"Aarav Sharma\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.admissionNumber").exists());
        verify(repo, never()).createStudent(anyMap());
    }

    @Test
    void createStudent_blankFullName_returns400() throws Exception {
        mvc.perform(post("/api/v1/students")
                        .header("X-Student-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"admissionNumber\":\"ADM-001\",\"fullName\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.fullName").exists());
        verify(repo, never()).createStudent(anyMap());
    }

    @Test
    void createStudent_missingBothRequired_returns400WithBothFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/students")
                        .header("X-Student-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"gender\":\"Male\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.admissionNumber").exists())
                .andExpect(jsonPath("$.fieldErrors.fullName").exists());
        verify(repo, never()).createStudent(anyMap());
    }

    @Test
    void createStudent_valid_callsRepositoryWithExpectedKeys() throws Exception {
        when(repo.createStudent(anyMap())).thenReturn(Map.of("id", 1L));
        mvc.perform(post("/api/v1/students")
                        .header("X-Student-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"admissionNumber\":\"ADM-001\",\"fullName\":\"Aarav Sharma\"," +
                                 "\"schoolId\":4,\"sectionName\":\"B\",\"gender\":\"Male\"}"))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(repo).createStudent(captor.capture());
        Map<String, Object> captured = captor.getValue();
        assertEquals("ADM-001", captured.get("admissionNumber"));
        assertEquals("ADM-001", captured.get("admissionNo"));
        assertEquals("Aarav Sharma", captured.get("fullName"));
        assertNotNull(captured.get("schoolId"));
        assertEquals("B", captured.get("sectionName"));
        assertEquals("Male", captured.get("gender"));
    }

    // --- POST /reviews/id-card/initiate ---

    @Test
    void initiateIdCardReview_valid_callsRepositoryWithExpectedKeys() throws Exception {
        when(repo.initiateIdCardReview(anyMap())).thenReturn(Map.of("campaignId", "c1"));
        mvc.perform(post("/api/v1/students/reviews/id-card/initiate")
                        .header("X-Student-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"schoolId\":4,\"actorId\":10,\"dueDate\":\"2026-08-01\"," +
                                 "\"classIds\":[\"cls-1\"],\"sectionIds\":[\"sec-1\"]}"))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(repo).initiateIdCardReview(captor.capture());
        Map<String, Object> captured = captor.getValue();
        assertNotNull(captured.get("schoolId"));
        assertEquals(10L, captured.get("actorId"));
        assertEquals("2026-08-01", captured.get("dueDate"));
        assertNotNull(captured.get("classIds"));
        assertNotNull(captured.get("sectionIds"));
    }

    @Test
    void initiateIdCardReview_noSchoolId_superadminPassesNullToRepo() throws Exception {
        when(repo.initiateIdCardReview(anyMap())).thenReturn(Map.of("campaignId", "c2"));
        mvc.perform(post("/api/v1/students/reviews/id-card/initiate")
                        .header("X-Student-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"actorId\":10}"))
                .andExpect(status().isOk());
        // repo is called (SUPERADMIN with null schoolId is allowed by TenantScope)
        verify(repo).initiateIdCardReview(anyMap());
    }

    // --- POST /reviews/full-name/initiate ---

    @Test
    void initiateFullNameReview_valid_callsRepositoryWithExpectedKeys() throws Exception {
        when(repo.initiateFullNameVerification(anyMap())).thenReturn(Map.of("campaignId", "c3"));
        mvc.perform(post("/api/v1/students/reviews/full-name/initiate")
                        .header("X-Student-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"schoolId\":4,\"actorId\":10,\"verifier\":\"PARENT\"," +
                                 "\"classIds\":[\"cls-2\"]}"))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(repo).initiateFullNameVerification(captor.capture());
        Map<String, Object> captured = captor.getValue();
        assertNotNull(captured.get("schoolId"));
        assertEquals(10L, captured.get("actorId"));
        assertEquals("PARENT", captured.get("verifier"));
        assertNotNull(captured.get("classIds"));
    }

    @Test
    void initiateFullNameReview_noSchoolId_superadminPassesNullToRepo() throws Exception {
        when(repo.initiateFullNameVerification(anyMap())).thenReturn(Map.of("campaignId", "c4"));
        mvc.perform(post("/api/v1/students/reviews/full-name/initiate")
                        .header("X-Student-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"verifier\":\"TEACHER\"}"))
                .andExpect(status().isOk());
        verify(repo).initiateFullNameVerification(anyMap());
    }
}
