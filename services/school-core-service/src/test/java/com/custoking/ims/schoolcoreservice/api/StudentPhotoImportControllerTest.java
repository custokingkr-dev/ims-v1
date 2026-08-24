package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.photoimport.PhotoImportService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudentPhotoImportControllerTest {

    private static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
    private final PhotoImportService service = mock(PhotoImportService.class);
    private final StudentPhotoImportController controller =
            new StudentPhotoImportController(service, "student-token");

    @Test
    void resultReturnsUtf8CsvAsNosniffAttachmentBytes() {
        UUID batchId = UUID.fromString("c5a377f4-02c7-431a-a3e3-ce22c0a2e91c");
        String csv = "admissionNo,status\nA-1,<script>alert(1)</script>\n";
        when(service.resultCsv(batchId)).thenReturn(csv);

        ResponseEntity<byte[]> response = controller.result("student-token", batchId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=student-photo-import-" + batchId + ".csv");
        assertThat(response.getHeaders().getFirst(X_CONTENT_TYPE_OPTIONS)).isEqualTo("nosniff");
        assertThat(response.getHeaders().getContentType()).hasToString("text/csv;charset=UTF-8");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(csv.getBytes(StandardCharsets.UTF_8).length);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).isEqualTo(csv);
    }

    @Test
    void resultRejectsInvalidServiceTokenBeforeReadingCsv() {
        UUID batchId = UUID.fromString("a8b84c37-66fa-4abe-9549-fb466532175f");

        assertThatThrownBy(() -> controller.result("wrong-token", batchId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(service, never()).resultCsv(batchId);
    }

    @Test
    void recoveryForwardsOnlyExplicitlySelectedRowsAfterTokenValidation() {
        UUID batchId = UUID.randomUUID();
        UUID rowId = UUID.randomUUID();
        var request = new StudentPhotoImportController.RecoverAppliedPhotosRequest(List.of(rowId));
        var expected = new PhotoImportService.RecoveryBatchResult(
                batchId, 7L, 1, 1, 0, 0, 0, List.of());
        when(service.recoverAppliedRows(batchId, List.of(rowId))).thenReturn(expected);

        var result = controller.recover("student-token", batchId, request);

        assertThat(result).isSameAs(expected);
        verify(service).recoverAppliedRows(batchId, List.of(rowId));
    }
}
