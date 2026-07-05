package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.StudentReadRepository;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StudentValidationTest {

    private static final String VALID_TOKEN = "student-token";

    StudentReadRepository repo;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        repo = mock(StudentReadRepository.class);
        com.custoking.ims.schoolcoreservice.infrastructure.ImageUrlFetcher fetcher =
                mock(com.custoking.ims.schoolcoreservice.infrastructure.ImageUrlFetcher.class);
        StudentReadController controller = new StudentReadController(repo, fetcher, VALID_TOKEN);
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
        assertEquals(4L, captured.get("schoolId"));
        assertEquals("B", captured.get("sectionName"));
        assertEquals("Male", captured.get("gender"));
    }

    // --- POST /{id}/photo (multipart upload) ---

    @Test
    void attachPhoto_missingFile_returns400() throws Exception {
        mvc.perform(multipart("/api/v1/students/42/photo")
                        .header("X-Student-Service-Token", VALID_TOKEN))
                .andExpect(status().isBadRequest());
        verify(repo, never()).attachPhoto(anyLong(), any(byte[].class), any());
    }

    @Test
    void attachPhoto_valid_callsRepositoryWithBytesAndContentType() throws Exception {
        when(repo.attachPhoto(anyLong(), any(byte[].class), any())).thenReturn(Map.of("id", 42L));
        byte[] jpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", jpeg);

        mvc.perform(multipart("/api/v1/students/42/photo")
                        .file(file)
                        .header("X-Student-Service-Token", VALID_TOKEN))
                .andExpect(status().isOk());

        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(repo).attachPhoto(eq(42L), bytesCaptor.capture(), eq("image/jpeg"));
        assertArrayEquals(jpeg, bytesCaptor.getValue());
    }

    // --- POST /imports/preview ---

    @Test
    void previewImport_valid_callsRepositoryWithRowsAndSchoolId() throws Exception {
        when(repo.previewImport(anyMap())).thenReturn(Map.of("validCount", 1));
        mvc.perform(post("/api/v1/students/imports/preview")
                        .header("X-Student-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"schoolId\":4,\"rows\":[{\"Name\":\"Aarav\",\"Class\":\"9\"}]}"))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(repo).previewImport(captor.capture());
        Map<String, Object> captured = captor.getValue();
        assertEquals(4L, captured.get("schoolId"));
        assertNotNull(captured.get("rows"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) captured.get("rows");
        assertEquals(1, rows.size());
        assertEquals("Aarav", rows.get(0).get("Name"));
    }

    // --- POST /imports/confirm ---

    @Test
    void confirmImport_missingFileToken_returns400() throws Exception {
        mvc.perform(post("/api/v1/students/imports/confirm")
                        .header("X-Student-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"schoolId\":4}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.fileToken").exists());
        verify(repo, never()).confirmImport(anyMap());
    }

    @Test
    void confirmImport_valid_callsRepositoryWithFileTokenAndSchoolId() throws Exception {
        when(repo.confirmImport(anyMap())).thenReturn(Map.of("inserted", 1));
        mvc.perform(post("/api/v1/students/imports/confirm")
                        .header("X-Student-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"fileToken\":\"tok-abc-123\",\"schoolId\":4}"))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(repo).confirmImport(captor.capture());
        Map<String, Object> captured = captor.getValue();
        assertEquals("tok-abc-123", captured.get("fileToken"));
        assertEquals(4L, captured.get("schoolId"));
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
        assertEquals(4L, captured.get("schoolId"));
        assertEquals(10L, captured.get("actorId"));
        assertEquals("2026-08-01", captured.get("dueDate"));
        assertNotNull(captured.get("classIds"));
        assertNotNull(captured.get("sectionIds"));
    }

    @Test
    void initiateIdCardReview_noSchoolId_superadmin_returns400() throws Exception {
        // TenantScope passes null schoolId through for SUPERADMIN, but the real repository
        // rejects it via requireLong(null, "schoolId is required") → IllegalArgumentException.
        // The controller's execute() wrapper maps IllegalArgumentException → 400 BAD_REQUEST.
        when(repo.initiateIdCardReview(anyMap()))
                .thenThrow(new IllegalArgumentException("schoolId is required"));
        mvc.perform(post("/api/v1/students/reviews/id-card/initiate")
                        .header("X-Student-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"actorId\":10}"))
                .andExpect(status().isBadRequest());
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
        assertEquals(4L, captured.get("schoolId"));
        assertEquals(10L, captured.get("actorId"));
        assertEquals("PARENT", captured.get("verifier"));
        assertNotNull(captured.get("classIds"));
    }

    @Test
    void initiateFullNameReview_noSchoolId_superadmin_returns400() throws Exception {
        // TenantScope passes null schoolId through for SUPERADMIN, but the real repository
        // rejects it via requireLong(null, "schoolId is required") → IllegalArgumentException.
        // The controller's execute() wrapper maps IllegalArgumentException → 400 BAD_REQUEST.
        when(repo.initiateFullNameVerification(anyMap()))
                .thenThrow(new IllegalArgumentException("schoolId is required"));
        mvc.perform(post("/api/v1/students/reviews/full-name/initiate")
                        .header("X-Student-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"verifier\":\"TEACHER\"}"))
                .andExpect(status().isBadRequest());
        verify(repo).initiateFullNameVerification(anyMap());
    }
}
