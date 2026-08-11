package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.ImportAdmissionException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationExceptionHandlerImportAdmissionTest {

    @Test
    void admissionRejectionReturnsRetryable429Contract() {
        var registry = new SimpleMeterRegistry();
        var response = new ValidationExceptionHandler(registry).onImportAdmission(
                new ImportAdmissionException("school_import_active", "School import busy", 5));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
        assertThat(response.getBody())
                .containsEntry("code", "school_import_active")
                .containsEntry("retryAfterSeconds", 5)
                .containsEntry("message", "School import busy");
        assertThat(registry.get("ims.student.import.admission.rejections")
                .tag("reason", "school_import_active")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void wrappedAdmissionRejectionStillReturnsRetryable429AndTelemetry() {
        var registry = new SimpleMeterRegistry();
        var wrapped = new InvalidDataAccessApiUsageException("repository wrapper",
                new ImportAdmissionException("import_capacity_busy", "Fleet import capacity busy", 5));

        var response = new ValidationExceptionHandler(registry).onDataAccessApiUsage(wrapped);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
        assertThat(response.getBody())
                .containsEntry("code", "import_capacity_busy")
                .containsEntry("retryAfterSeconds", 5)
                .containsEntry("message", "Fleet import capacity busy");
        assertThat(registry.get("ims.student.import.admission.rejections")
                .tag("reason", "import_capacity_busy")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void admissionRejectionHasPriorityOverIntermediateValidationWrapper() {
        var registry = new SimpleMeterRegistry();
        var wrapped = new InvalidDataAccessApiUsageException("repository wrapper",
                new IllegalArgumentException("intermediate validation wrapper",
                        new ImportAdmissionException(
                                "school_import_active", "School import busy", 5)));

        var response = new ValidationExceptionHandler(registry).onDataAccessApiUsage(wrapped);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).containsEntry("code", "school_import_active");
        assertThat(registry.get("ims.student.import.admission.rejections")
                .tag("reason", "school_import_active")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void unexpectedReasonCannotCreateAnUnboundedMetricTag() {
        var registry = new SimpleMeterRegistry();

        new ValidationExceptionHandler(registry).onImportAdmission(
                new ImportAdmissionException("caller-controlled-value", "Busy", 5));

        assertThat(registry.get("ims.student.import.admission.rejections")
                .tag("reason", "unknown")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.find("ims.student.import.admission.rejections")
                .tag("reason", "caller-controlled-value").counter()).isNull();
    }
}
