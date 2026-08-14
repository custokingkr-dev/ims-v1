package com.custoking.ims.schoolcoreservice.studentexport;

import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.studentexport.StudentExportArchiveWriter.Result;
import com.custoking.ims.schoolcoreservice.studentexport.StudentExportRepository.ExportData;
import com.custoking.ims.schoolcoreservice.studentexport.StudentExportRepository.SchoolOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class StudentExportService {

    private static final Logger log = LoggerFactory.getLogger(StudentExportService.class);
    private final StudentExportRepository repository;
    private final StudentExportArchiveWriter archiveWriter;
    private final Semaphore slots;

    public StudentExportService(
            StudentExportRepository repository,
            StudentExportArchiveWriter archiveWriter,
            @Value("${student.export.max-concurrent:2}") int maxConcurrent) {
        this.repository = repository;
        this.archiveWriter = archiveWriter;
        this.slots = new Semaphore(Math.max(1, maxConcurrent), true);
    }

    public List<SchoolOption> allowedSchools() {
        return repository.allowedSchools();
    }

    public PreparedExport prepare(long schoolId) {
        if (!slots.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Another student export is already running. Please retry shortly.");
        }
        try {
            ExportData data = repository.load(schoolId);
            UUID auditId = repository.startAudit(schoolId, TenantContext.get().userId(), data.students().size());
            return new PreparedExport(data, auditId, slots);
        } catch (RuntimeException ex) {
            slots.release();
            throw ex;
        }
    }

    public Result write(PreparedExport prepared, OutputStream output) throws IOException {
        long started = System.nanoTime();
        try {
            Result result = archiveWriter.write(prepared.data(), output);
            finishAudit(prepared, "COMPLETED", result.exportedPhotoCount(), result.missingPhotoCount(), null);
            log.info("student_export_completed schoolId={} requestedBy={} students={} exportedPhotos={} missingPhotos={} durationMs={}",
                    prepared.data().school().id(), TenantContext.get().userId(), result.studentCount(),
                    result.exportedPhotoCount(), result.missingPhotoCount(), elapsedMillis(started));
            return result;
        } catch (IOException | RuntimeException ex) {
            finishAudit(prepared, "FAILED", 0, 0, ex.getClass().getSimpleName());
            log.warn("student_export_failed schoolId={} requestedBy={} durationMs={} errorType={}",
                    prepared.data().school().id(), TenantContext.get().userId(), elapsedMillis(started),
                    ex.getClass().getSimpleName());
            throw ex;
        }
    }

    private void finishAudit(PreparedExport prepared, String status, int exportedPhotos,
                             int missingPhotos, String failureReason) {
        try {
            repository.finishAudit(prepared.auditId(), prepared.data().school().id(), status,
                    exportedPhotos, missingPhotos, failureReason);
        } catch (RuntimeException auditError) {
            log.error("student_export_audit_update_failed auditId={} schoolId={} status={}",
                    prepared.auditId(), prepared.data().school().id(), status, auditError);
        }
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    public static final class PreparedExport implements AutoCloseable {
        private final ExportData data;
        private final UUID auditId;
        private final Semaphore slots;
        private final AtomicBoolean closed = new AtomicBoolean();

        private PreparedExport(ExportData data, UUID auditId, Semaphore slots) {
            this.data = data;
            this.auditId = auditId;
            this.slots = slots;
        }

        public ExportData data() { return data; }
        public UUID auditId() { return auditId; }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) slots.release();
        }
    }
}
