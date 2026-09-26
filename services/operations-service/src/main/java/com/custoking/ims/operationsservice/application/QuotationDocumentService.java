package com.custoking.ims.operationsservice.application;

import com.custoking.ims.operationsservice.api.dto.QuotationDocumentResponse;
import com.custoking.ims.operationsservice.infrastructure.QuotationDocumentStorage;
import com.custoking.ims.operationsservice.security.TenantContext;
import com.custoking.ims.operationsservice.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.concurrent.TimeUnit;

@Service
public class QuotationDocumentService {
    private static final Logger log = LoggerFactory.getLogger(QuotationDocumentService.class);
    private final JdbcClient jdbc;
    private final QuotationDocumentStorage storage;
    private final TransactionTemplate transaction;
    private final TransactionTemplate cleanupTransaction;
    private final LongSupplier monotonicNanos;

    @Autowired
    public QuotationDocumentService(JdbcClient jdbc, QuotationDocumentStorage storage, PlatformTransactionManager manager) {
        this(jdbc, storage, manager, System::nanoTime);
    }

    QuotationDocumentService(JdbcClient jdbc, QuotationDocumentStorage storage, PlatformTransactionManager manager, LongSupplier monotonicNanos) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.monotonicNanos = monotonicNanos;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        cleanupTransaction = new TransactionTemplate(manager);
        cleanupTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        cleanupTransaction.setTimeout(45);
    }

    public Map<String, Object> capabilities() {
        authorize(false);
        String reason = null;
        try { storage.requireAvailable(); }
        catch (ResponseStatusException ex) { reason = ex.getReason(); }
        var result = new LinkedHashMap<String, Object>();
        result.put("available", reason == null);
        result.put("canUpload", reason == null && (TenantContext.get().isSuperAdmin() || TenantContext.get().hasPermission("firefighting:update")));
        result.put("maxBytes", QuotationDocumentStorage.MAX_BYTES);
        result.put("allowedContentTypes", QuotationDocumentStorage.CONTENT_TYPES);
        result.put("unavailableReason", reason);
        return result;
    }

    public QuotationDocumentResponse upload(String code, String quotationId, byte[] bytes, String filename, String contentType) {
        authorize(true);
        QuoteContext quote = quote(code, quotationId, false);
        requireDraft(quote);
        var document = storage.validate(bytes, filename, contentType);
        storage.requireAvailable();
        String id = UUID.randomUUID().toString();
        String key = "schools/" + quote.schoolId() + "/firefighting/quotations/" + id + document.extension();
        // Reserve durably BEFORE an upload. A lost response or process crash leaves
        // a recoverable cleanup record, never an untracked private object.
        transaction.executeWithoutResult(status -> jdbc.sql("""
                INSERT INTO firefighting.quotation_documents
                  (id, school_id, request_code, quotation_id, object_key, filename, content_type,
                   size_bytes, checksum_sha256, uploaded_by)
                VALUES (:id, :school, :code, :quote, :key, :filename, :type, :size, :checksum, :actor)
                """)
                .param("id", id).param("school", quote.schoolId()).param("code", code).param("quote", quotationId)
                .param("key", key).param("filename", document.filename()).param("type", document.contentType())
                .param("size", bytes.length).param("checksum", document.checksumSha256())
                .param("actor", TenantContext.get().userId()).update());
        try {
            storage.write(key, document);
            return transaction.execute(status -> {
                // The request lock serializes attachment writes with submission.
                QuoteContext latest = quote(code, quotationId, true);
                requireDraft(latest);
                int changed = jdbc.sql("""
                        UPDATE firefighting.quotation_documents SET status = 'READY', uploaded_at = now()
                        WHERE id = :id AND status = 'PENDING' AND created_at > now() - interval '1 hour'
                        """).param("id", id).update();
                if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "The upload expired. Choose the file again.");
                jdbc.sql("UPDATE firefighting.ff_quotations SET document_id = :id WHERE id = :quote AND request_id = :code")
                        .param("id", id).param("quote", quotationId).param("code", code).update();
                return current(code, quotationId).response();
            });
        } catch (RuntimeException failure) {
            try {
                transaction.executeWithoutResult(status -> jdbc.sql("""
                        UPDATE firefighting.quotation_documents d SET status = 'RETIRED', next_cleanup_at = now()
                        WHERE id = :id AND status = 'PENDING'
                          AND NOT EXISTS (SELECT 1 FROM firefighting.ff_quotations q WHERE q.document_id = d.id)
                        """).param("id", id).update());
            } catch (RuntimeException cleanupFailure) {
                // Its original reservation still provides the one-hour crash recovery path.
                log.warn("Quotation upload {} could not mark its cleanup reservation", id);
            }
            throw failure;
        }
    }

    public Download download(String code, String quotationId) {
        authorize(false);
        quote(code, quotationId, false); // Enforce school scope before contacting storage.
        DocumentRow document = current(code, quotationId);
        byte[] bytes = storage.read(document.objectKey());
        try {
            if (bytes.length != document.sizeBytes() || !MessageDigest.isEqual(
                    MessageDigest.getInstance("SHA-256").digest(bytes), HexFormat.of().parseHex(document.checksumSha256()))) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The stored quotation file failed its integrity check");
            }
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        return new Download(document.response(), bytes);
    }

    public void remove(String code, String quotationId) {
        authorize(true);
        transaction.executeWithoutResult(status -> {
            requireDraft(quote(code, quotationId, true));
            jdbc.sql("UPDATE firefighting.ff_quotations SET document_id = NULL WHERE id = :quote AND request_id = :code")
                    .param("quote", quotationId).param("code", code).update();
        });
    }

    /** Trusted maintenance, also invoked by the existing IAM-protected Scheduler relay route. */
    @Scheduled(fixedDelayString = "${firefighting.quotation-documents.cleanup-delay-ms:300000}", initialDelayString = "${firefighting.quotation-documents.cleanup-delay-ms:300000}")
    public int cleanup() {
        if (!storage.configured()) return 0;
        long deadline = monotonicNanos.getAsLong() + TimeUnit.SECONDS.toNanos(20);
        TenantContext previous = TenantContext.get();
        try {
            TenantContext.set(new TenantContext(null, null, "SUPERADMIN", null, null));
            return cleanupTransaction.execute(status -> {
                List<CleanupRow> candidates = jdbc.sql("""
                        SELECT d.id, d.object_key FROM firefighting.quotation_documents d
                        WHERE (d.status IN ('RETIRED', 'DELETED') OR (d.status = 'PENDING' AND d.created_at <= now() - interval '1 hour'))
                          AND d.next_cleanup_at <= now()
                          AND NOT EXISTS (SELECT 1 FROM firefighting.ff_quotations q WHERE q.document_id = d.id)
                        ORDER BY d.created_at LIMIT 20 FOR UPDATE OF d SKIP LOCKED
                        """).query(CleanupRow.class).list();
                int checked = 0;
                for (CleanupRow candidate : candidates) {
                    if (monotonicNanos.getAsLong() >= deadline) break;
                    checked++;
                    try {
                        storage.delete(candidate.objectKey());
                        // Retain and periodically reconcile tombstones too: a storage write whose
                        // response was lost could finish after the first cleanup attempt.
                        jdbc.sql("UPDATE firefighting.quotation_documents SET status = 'DELETED', cleanup_attempts = cleanup_attempts + 1, next_cleanup_at = now() + interval '1 day' WHERE id = :id")
                                .param("id", candidate.id()).update();
                    } catch (RuntimeException failure) {
                        jdbc.sql("""
                                UPDATE firefighting.quotation_documents
                                SET cleanup_attempts = cleanup_attempts + 1, next_cleanup_at = now() + interval '10 minutes'
                                WHERE id = :id
                                """).param("id", candidate.id()).update();
                        log.warn("Private quotation cleanup will retry document {}", candidate.id());
                    }
                }
                return checked;
            });
        } finally { TenantContext.set(previous); }
    }

    private QuoteContext quote(String code, String id, boolean lock) {
        Long scope = TenantScope.resolveSchoolId(null);
        if (lock) {
            // All quotation mutations lock the parent first, then the quotation.
            // Keep the same order as FirefightingReadRepository to avoid a
            // request/quotation deadlock with simultaneous submit or delete.
            jdbc.sql("""
                    SELECT code FROM firefighting.firefighting_requests
                    WHERE code = :code AND (:scope::bigint IS NULL OR school_id = :scope)
                    FOR UPDATE
                    """).param("code", code).param("scope", scope).query(String.class).optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Quotation not found"));
        }
        return jdbc.sql("""
                SELECT q.school_id, r.status
                FROM firefighting.ff_quotations q
                JOIN firefighting.firefighting_requests r ON r.code = q.request_id AND r.school_id = q.school_id
                WHERE q.id = :id AND q.request_id = :code AND (:scope::bigint IS NULL OR q.school_id = :scope)
                """ + (lock ? " FOR UPDATE OF q" : ""))
                .param("id", id).param("code", code).param("scope", scope)
                .query(QuoteContext.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Quotation not found"));
    }

    private DocumentRow current(String code, String quoteId) {
        return jdbc.sql("""
                SELECT d.id, d.filename, d.content_type, d.size_bytes, d.uploaded_at, d.object_key, d.checksum_sha256
                FROM firefighting.quotation_documents d
                JOIN firefighting.ff_quotations q ON q.document_id = d.id AND q.school_id = d.school_id
                WHERE q.id = :id AND q.request_id = :code AND d.status = 'READY'
                """).param("id", quoteId).param("code", code).query(DocumentRow.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No quotation file is attached"));
    }
    private static void authorize(boolean write) {
        if (TenantContext.get().userId() == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "An authenticated user is required");
        TenantScope.requirePermission(write ? "firefighting:update" : "firefighting:read");
    }
    private static void requireDraft(QuoteContext quote) {
        if (!"DRAFT".equals(quote.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Quotation files can only be changed while the request is a draft");
    }
    record QuoteContext(long schoolId, String status) {}
    record CleanupRow(String id, String objectKey) {}
    record DocumentRow(String id, String filename, String contentType, long sizeBytes, OffsetDateTime uploadedAt, String objectKey, String checksumSha256) {
        QuotationDocumentResponse response() { return new QuotationDocumentResponse(id, filename, contentType, sizeBytes, uploadedAt); }
    }
    public record Download(QuotationDocumentResponse metadata, byte[] bytes) {}
}
