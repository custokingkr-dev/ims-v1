package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.infrastructure.ImageFetchException;
import com.custoking.ims.schoolcoreservice.infrastructure.ImageUrlFetcher;
import com.custoking.ims.schoolcoreservice.persistence.StudentReadRepository;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StudentPhotoFromUrlControllerTest {

    private final StudentReadRepository students = mock(StudentReadRepository.class);
    private final ImageUrlFetcher fetcher = mock(ImageUrlFetcher.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new StudentReadController(students, fetcher, "tok"))
            .addFilters(new TenantContextFilter())
            .build();

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void storesFetchedPhoto() throws Exception {
        when(students.schoolIdForStudent(42L)).thenReturn(10L);
        when(fetcher.fetch("https://cdn.example.com/a.jpg"))
                .thenReturn(new ImageUrlFetcher.FetchedImage(new byte[]{1, 2, 3}, "image/jpeg"));
        when(students.attachPhoto(eq(42L), any(byte[].class), eq("image/jpeg"))).thenReturn(Map.of("id", 42L));

        mvc.perform(post("/api/v1/students/42/photo-from-url")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://cdn.example.com/a.jpg\"}"))
                .andExpect(status().isOk());
        verify(students).attachPhoto(eq(42L), any(byte[].class), eq("image/jpeg"));
    }

    @Test
    void mapsFetchFailureTo422WithReason() throws Exception {
        when(students.schoolIdForStudent(42L)).thenReturn(10L);
        when(fetcher.fetch(anyString())).thenThrow(new ImageFetchException("not_an_image", "content-type text/html"));

        mvc.perform(post("/api/v1/students/42/photo-from-url")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://x/y\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("not_an_image"));
        verify(students, never()).attachPhoto(anyLong(), any(), any());
    }

    @Test
    void crossTenantIsForbidden() throws Exception {
        when(students.schoolIdForStudent(42L)).thenReturn(99L); // student belongs to school 99
        mvc.perform(post("/api/v1/students/42/photo-from-url")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://x/y\"}"))
                .andExpect(status().isForbidden());
        verify(fetcher, never()).fetch(anyString());
    }
}
