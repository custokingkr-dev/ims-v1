package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.api.dto.AssignFeePlanRequest;
import com.custoking.ims.schoolcoreservice.api.dto.CreateBandRequest;
import com.custoking.ims.schoolcoreservice.api.dto.CreateItemRequest;
import com.custoking.ims.schoolcoreservice.api.dto.PatchBandRequest;
import com.custoking.ims.schoolcoreservice.api.dto.RecordPaymentRequest;
import com.custoking.ims.schoolcoreservice.api.dto.UpdateBandRequest;
import com.custoking.ims.schoolcoreservice.api.dto.UpdateItemRequest;
import com.custoking.ims.schoolcoreservice.persistence.FeeReadRepository;
import com.custoking.ims.schoolcoreservice.persistence.FeeReadRepository.FeeAssignmentRow;
import com.custoking.ims.schoolcoreservice.persistence.FeeReadRepository.FeeBandRow;
import com.custoking.ims.schoolcoreservice.persistence.FeeReadRepository.FeeItemRow;
import com.custoking.ims.schoolcoreservice.persistence.FeeReadRepository.PaymentRow;
import com.custoking.ims.schoolcoreservice.security.TenantScope;
import jakarta.validation.Valid;
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

import java.util.HashMap;
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
            @Valid @RequestBody CreateBandRequest req) {
        requireToken(token, "fee:write");
        Map<String, Object> body = new HashMap<>();
        body.put("name", req.name());
        if (req.classFrom() != null) body.put("classFrom", req.classFrom());
        if (req.classTo() != null) body.put("classTo", req.classTo());
        if (req.schedules() != null) body.put("schedules", req.schedules());
        if (req.discount() != null) body.put("discount", req.discount());
        return execute(() -> fees.createBand(body));
    }

    @PutMapping("/bands/{id}")
    public Map<String, Object> updateBand(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @PathVariable String id,
            @Valid @RequestBody UpdateBandRequest req) {
        requireToken(token, "fee:write");
        Map<String, Object> body = new HashMap<>();
        // name is not containsKey-gated in repo (textOrDefault falls back to current), omit when null
        if (req.name() != null) body.put("name", req.name());
        // classFrom, classTo, discount are containsKey-gated — only put when explicitly sent
        if (req.classFrom() != null) body.put("classFrom", req.classFrom());
        if (req.classTo() != null) body.put("classTo", req.classTo());
        if (req.schedules() != null) body.put("schedules", req.schedules());
        if (req.discount() != null) body.put("discount", req.discount());
        return execute(() -> fees.updateBand(id, body));
    }

    @PatchMapping("/bands/{id}")
    public Map<String, Object> patchBand(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @PathVariable String id,
            @Valid @RequestBody PatchBandRequest req) {
        requireToken(token, "fee:write");
        Map<String, Object> body = new HashMap<>();
        // discount and bandDiscount are containsKey-gated (repo checks OR of both keys)
        if (req.discount() != null) body.put("discount", req.discount());
        if (req.bandDiscount() != null) body.put("bandDiscount", req.bandDiscount());
        if (req.schedules() != null) body.put("schedules", req.schedules());
        return execute(() -> fees.patchBand(id, body));
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
            @Valid @RequestBody CreateItemRequest req) {
        requireToken(token, "fee:write");
        Map<String, Object> body = new HashMap<>();
        body.put("bandId", req.bandId());
        body.put("name", req.name());
        if (req.frequency() != null) body.put("frequency", req.frequency());
        if (req.amount() != null) body.put("amount", req.amount());
        return execute(() -> fees.createItem(body));
    }

    @PutMapping("/items/{id}")
    public Map<String, Object> updateItem(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @PathVariable String id,
            @Valid @RequestBody UpdateItemRequest req) {
        requireToken(token, "fee:write");
        Map<String, Object> body = new HashMap<>();
        // All item fields are containsKey-gated in repo — only put when explicitly sent
        // itemName takes priority over name (firstPresent prefers itemName)
        if (req.itemName() != null) body.put("itemName", req.itemName());
        if (req.name() != null) body.put("name", req.name());
        if (req.frequency() != null) body.put("frequency", req.frequency());
        if (req.amount() != null) body.put("amount", req.amount());
        return execute(() -> fees.updateItem(id, body));
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
            @Valid @RequestBody AssignFeePlanRequest req) {
        requireToken(token, "fee:write");
        Map<String, Object> body = new HashMap<>();
        body.put("studentId", req.studentId());
        body.put("bandId", req.bandId());
        body.put("schedule", req.schedule());
        // bandDiscount uses containsKey in repo — only put when explicitly sent
        if (req.bandDiscount() != null) body.put("bandDiscount", req.bandDiscount());
        if (req.manualDiscount() != null) body.put("manualDiscount", req.manualDiscount());
        if (req.surcharge() != null) body.put("surcharge", req.surcharge());
        // actorId uses containsKey in repo — only put when explicitly sent
        if (req.actorId() != null) body.put("actorId", req.actorId());
        return execute(() -> fees.assignFeePlan(body));
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
            @RequestParam(required = false) Long schoolId) {
        requireToken(token, "fee:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return fees.feeReport(classId, sectionId, academicYearId, scope);
    }

    @GetMapping("/reports/overdue")
    public Map<String, Object> feeOverdue(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @RequestParam String classId,
            @RequestParam String sectionId,
            @RequestParam(required = false) String academicYearId,
            @RequestParam(required = false) Long schoolId) {
        requireToken(token, "fee:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return fees.feeOverdue(classId, sectionId, academicYearId, scope);
    }

    @PostMapping("/reminders/fee")
    public Map<String, Object> feeReminderRequests(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "fee:write");
        applyResolvedSchool(request);
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
            @RequestParam(required = false) Long schoolId) {
        requireToken(token, "fee:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return fees.feesModule(academicYearId, scope);
    }

    @GetMapping("/dashboard/overdue-count")
    public Map<String, Object> feeOverdueCount(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @RequestParam(required = false) String academicYearId,
            @RequestParam(required = false) Long schoolId) {
        requireToken(token, "fee:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return fees.feeOverdueCount(academicYearId, scope);
    }

    @PostMapping("/payments")
    public Map<String, Object> recordPayment(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @Valid @RequestBody RecordPaymentRequest req) {
        requireToken(token, "fee:write");
        Map<String, Object> body = new HashMap<>();
        body.put("studentId", req.studentId());
        body.put("amount", req.amount());
        if (req.paidAt() != null) body.put("paidAt", req.paidAt());
        if (req.mode() != null) body.put("mode", req.mode());
        if (req.notes() != null) body.put("notes", req.notes());
        // actorId uses containsKey in repo — only put when explicitly sent
        if (req.actorId() != null) body.put("actorId", req.actorId());
        return execute(() -> fees.recordPayment(body));
    }

    /** Recipe B helper: resolve and overwrite the schoolId inside a request body map. */
    private void applyResolvedSchool(Map<String, Object> request) {
        Long requested;
        if (request.get("schoolId") == null) {
            requested = null;
        } else {
            try {
                requested = Long.valueOf(String.valueOf(request.get("schoolId")));
            } catch (NumberFormatException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid schoolId");
            }
        }
        request.put("schoolId", TenantScope.resolveSchoolId(requested));
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

