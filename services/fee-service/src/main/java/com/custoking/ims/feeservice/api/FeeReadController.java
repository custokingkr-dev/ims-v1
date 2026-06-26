package com.custoking.ims.feeservice.api;

import com.custoking.ims.feeservice.persistence.FeeReadRepository;
import com.custoking.ims.feeservice.persistence.FeeReadRepository.FeeAssignmentRow;
import com.custoking.ims.feeservice.persistence.FeeReadRepository.FeeBandRow;
import com.custoking.ims.feeservice.persistence.FeeReadRepository.FeeItemRow;
import com.custoking.ims.feeservice.persistence.FeeReadRepository.PaymentRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/fees")
public class FeeReadController {

    private final FeeReadRepository fees;
    private final String readToken;

    public FeeReadController(
            FeeReadRepository fees,
            @Value("${fee.read-token:}") String readToken) {
        this.fees = fees;
        this.readToken = readToken == null ? "" : readToken.trim();
    }

    @GetMapping("/bands")
    public List<FeeBandRow> bands(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @RequestParam(required = false) String academicYearId) {
        requireToken(token, "fee:read");
        return fees.bands(academicYearId);
    }

    @GetMapping("/items")
    public List<FeeItemRow> items(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @RequestParam(required = false) String bandId) {
        requireToken(token, "fee:read");
        return fees.items(bandId);
    }

    @GetMapping("/structure")
    public Map<String, Object> structure(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @RequestParam(required = false) String academicYearId) {
        requireToken(token, "fee:read");
        return execute(() -> fees.feeStructure(academicYearId));
    }

    @GetMapping("/structure/match")
    public Map<String, Object> matchBand(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @RequestParam String classId) {
        requireToken(token, "fee:read");
        return execute(() -> fees.matchBand(classId));
    }

    @GetMapping(value = "/structure/export", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportStructure(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @RequestParam(required = false) String academicYearId,
            @RequestParam(defaultValue = "pdf") String format) {
        requireToken(token, "fee:read");
        if (!"pdf".equalsIgnoreCase(format)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PDF export is supported");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .body(fees.feeStructurePdf(academicYearId));
    }

    @PostMapping("/bands")
    public Map<String, Object> createBand(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "fee:write");
        return execute(() -> fees.createBand(request));
    }

    @PutMapping("/bands/{id}")
    public Map<String, Object> updateBand(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @PathVariable String id,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "fee:write");
        return execute(() -> fees.updateBand(id, request));
    }

    @PatchMapping("/bands/{id}")
    public Map<String, Object> patchBand(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @PathVariable String id,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "fee:write");
        return execute(() -> fees.patchBand(id, request));
    }

    @DeleteMapping("/bands/{id}")
    public Map<String, Object> deleteBand(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @PathVariable String id) {
        requireToken(token, "fee:write");
        return execute(() -> {
            fees.deleteBand(id);
            return Map.of("removed", true, "bandId", id);
        });
    }

    @PostMapping("/items")
    public Map<String, Object> createItem(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "fee:write");
        return execute(() -> fees.createItem(request));
    }

    @PutMapping("/items/{id}")
    public Map<String, Object> updateItem(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @PathVariable String id,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "fee:write");
        return execute(() -> fees.updateItem(id, request));
    }

    @DeleteMapping("/items/{id}")
    public Map<String, Object> deleteItem(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @PathVariable String id) {
        requireToken(token, "fee:write");
        return execute(() -> {
            String bandId = fees.deleteItem(id);
            return Map.of("removed", true, "bandId", bandId);
        });
    }

    @GetMapping("/assignments")
    public List<FeeAssignmentRow> assignments(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) String academicYearId,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "fee:read");
        return fees.assignments(studentId, academicYearId, limit);
    }

    @PostMapping("/assignments")
    public Map<String, Object> assignFeePlan(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "fee:write");
        return execute(() -> fees.assignFeePlan(request));
    }

    @GetMapping("/payments")
    public List<PaymentRow> payments(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) String assignmentId,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "fee:read");
        return fees.payments(studentId, assignmentId, limit);
    }

    @GetMapping("/payments/{paymentId}/receipt")
    public Map<String, Object> receiptByPaymentId(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @PathVariable String paymentId) {
        requireToken(token, "fee:read");
        return execute(() -> fees.receiptByPaymentId(paymentId));
    }

    @GetMapping("/receipts/{receiptNumber}")
    public Map<String, Object> receiptByReceiptNumber(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @PathVariable String receiptNumber) {
        requireToken(token, "fee:read");
        return execute(() -> fees.receiptByReceiptNumber(receiptNumber));
    }

    @GetMapping("/reports/collection")
    public Map<String, Object> feeReport(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @RequestParam String classId,
            @RequestParam String sectionId,
            @RequestParam(required = false) String academicYearId,
            @RequestParam Long schoolId) {
        requireToken(token, "fee:read");
        return fees.feeReport(classId, sectionId, academicYearId, schoolId);
    }

    @GetMapping("/reports/overdue")
    public Map<String, Object> feeOverdue(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @RequestParam String classId,
            @RequestParam String sectionId,
            @RequestParam(required = false) String academicYearId,
            @RequestParam Long schoolId) {
        requireToken(token, "fee:read");
        return fees.feeOverdue(classId, sectionId, academicYearId, schoolId);
    }

    @PostMapping("/reminders/fee")
    public Map<String, Object> feeReminderRequests(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "fee:write");
        return execute(() -> fees.feeReminderRequests(
                text(request.get("classId")),
                text(request.get("sectionId")),
                text(request.get("academicYearId")),
                longValue(request.get("schoolId")),
                longValue(request.get("actorId"))));
    }

    @GetMapping("/dashboard/module")
    public Map<String, Object> feesModule(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @RequestParam(required = false) String academicYearId,
            @RequestParam Long schoolId) {
        requireToken(token, "fee:read");
        return fees.feesModule(academicYearId, schoolId);
    }

    @GetMapping("/dashboard/overdue-count")
    public Map<String, Object> feeOverdueCount(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @RequestParam(required = false) String academicYearId,
            @RequestParam Long schoolId) {
        requireToken(token, "fee:read");
        return fees.feeOverdueCount(academicYearId, schoolId);
    }

    @PostMapping("/payments")
    public Map<String, Object> recordPayment(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "fee:write");
        return execute(() -> fees.recordPayment(request));
    }

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing internal route scope" );
        }
        if (!StringUtils.hasText(readToken) || !readToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid fee service token");
        }
    }

    private Map<String, Object> execute(Command command) {
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
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private interface Command {
        Map<String, Object> run();
    }
}

