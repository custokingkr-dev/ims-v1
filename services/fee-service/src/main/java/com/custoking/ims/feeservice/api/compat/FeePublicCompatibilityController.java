package com.custoking.ims.feeservice.api.compat;

import com.custoking.ims.feeservice.persistence.FeeReadRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
public class FeePublicCompatibilityController {

    private final FeeReadRepository fees;
    private final String readToken;

    public FeePublicCompatibilityController(
            FeeReadRepository fees,
            @Value("${fee.read-token:}") String readToken) {
        this.fees = fees;
        this.readToken = readToken == null ? "" : readToken.trim();
    }

    @GetMapping("/api/v1/fee-structure")
    public Map<String, Object> feeStructure(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @RequestParam(required = false) String academicYearId) {
        requireToken(token, "fee:read");
        return run(() -> fees.feeStructure(academicYearId));
    }

    @PostMapping("/api/v1/fee-structure/item")
    public Map<String, Object> addItem(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "fee:read");
        return run(() -> fees.createItem(request));
    }

    @PutMapping("/api/v1/fee-structure/item/{id}")
    public Map<String, Object> updateItem(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @PathVariable String id,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "fee:read");
        return run(() -> fees.updateItem(id, request));
    }

    @DeleteMapping("/api/v1/fee-structure/item/{id}")
    public Map<String, Object> deleteItem(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @PathVariable String id) {
        requireToken(token, "fee:read");
        return run(() -> Map.of("removed", true, "bandId", fees.deleteItem(id)));
    }

    @PostMapping("/api/v1/fee-structure/band")
    public Map<String, Object> createBand(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "fee:read");
        return run(() -> fees.createBand(request));
    }

    @PutMapping("/api/v1/fee-structure/band/{id}")
    public Map<String, Object> updateBand(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @PathVariable String id,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "fee:read");
        return run(() -> fees.updateBand(id, request));
    }

    @PatchMapping("/api/v1/fee-structure/band/{id}")
    public Map<String, Object> patchBand(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @PathVariable String id,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "fee:read");
        return run(() -> fees.patchBand(id, request));
    }

    @DeleteMapping("/api/v1/fee-structure/band/{id}")
    public Map<String, Object> deleteBand(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @PathVariable String id) {
        requireToken(token, "fee:read");
        return run(() -> {
            fees.deleteBand(id);
            return Map.of("removed", true, "bandId", id);
        });
    }

    @GetMapping("/api/v1/fee-structure/match")
    public Map<String, Object> matchBand(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @RequestParam String classId) {
        requireToken(token, "fee:read");
        return run(() -> fees.matchBand(classId));
    }

    @GetMapping(value = "/api/v1/fee-structure/export", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportStructure(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @RequestParam(required = false) String academicYearId,
            @RequestParam(defaultValue = "pdf") String format) {
        requireToken(token, "fee:read");
        if (!"pdf".equalsIgnoreCase(format)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PDF export is supported");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=fee-structure.pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(fees.feeStructurePdf(academicYearId));
    }

    @PostMapping({"/api/v1/fee-assignments", "/api/v1/workspace/fees/assign-plan"})
    public Map<String, Object> assignFeePlan(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "fee:read");
        return run(() -> fees.assignFeePlan(request));
    }

    @PostMapping({"/api/v1/payments", "/api/v1/workspace/fees/record-payment"})
    public Map<String, Object> recordPayment(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "fee:read");
        return run(() -> fees.recordPayment(request));
    }

    @GetMapping("/api/v1/fees/report")
    public Object feeReport(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @RequestParam String classId,
            @RequestParam String sectionId,
            @RequestParam(required = false) String academicYearId,
            @RequestParam Long schoolId) {
        requireToken(token, "fee:read");
        return runObject(() -> fees.feeReport(classId, sectionId, academicYearId, schoolId).get("content"));
    }

    @GetMapping("/api/v1/fees/overdue")
    public Object feeOverdue(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @RequestParam String classId,
            @RequestParam String sectionId,
            @RequestParam(required = false) String academicYearId,
            @RequestParam Long schoolId) {
        requireToken(token, "fee:read");
        return runObject(() -> fees.feeOverdue(classId, sectionId, academicYearId, schoolId).get("content"));
    }

    @PostMapping("/api/v1/fees/send-reminders")
    public Map<String, Object> sendReminders(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "fee:read");
        return run(() -> fees.feeReminderRequests(
                text(request.get("classId")),
                text(request.get("sectionId")),
                text(request.get("academicYearId")),
                longValue(request.get("schoolId")),
                longValue(request.get("actorId"))));
    }

    @GetMapping(value = "/api/v1/receipts/{paymentId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> receiptByPaymentIdPdf(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @PathVariable String paymentId) {
        requireToken(token, "fee:read");
        return pdf(paymentId + ".pdf", fees.receiptPdfByPaymentId(paymentId));
    }

    @GetMapping(value = "/api/v1/workspace/fees/receipts/{receiptNumber}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> receiptByReceiptNumberPdf(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @PathVariable String receiptNumber) {
        requireToken(token, "fee:read");
        return pdf(receiptNumber + ".pdf", fees.receiptPdfByReceiptNumber(receiptNumber));
    }

    private ResponseEntity<byte[]> pdf(String filename, byte[] body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=" + filename)
                .contentType(MediaType.APPLICATION_PDF)
                .body(body);
    }

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope) || !StringUtils.hasText(readToken) || !readToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid fee service token");
        }
    }

    private Map<String, Object> run(Command command) {
        try {
            return command.run();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private Object runObject(ObjectCommand command) {
        try {
            return command.run();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Long longValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private interface Command {
        Map<String, Object> run();
    }

    private interface ObjectCommand {
        Object run();
    }
}
