package com.custoking.ims.service;

import com.custoking.ims.audit.AuditLogService;
import com.custoking.ims.context.TenantContext;
import com.custoking.ims.entity.FirefightingQuotationEntity;
import com.custoking.ims.entity.FirefightingRequestEntity;
import com.custoking.ims.entity.SchoolEntity;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.model.PageResponse;
import com.custoking.ims.repo.FirefightingQuotationRepository;
import com.custoking.ims.repo.FirefightingRequestRepository;
import com.custoking.ims.repo.SchoolRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

@Service
@Transactional
public class FirefightingService {

    private static final Logger log = LoggerFactory.getLogger(FirefightingService.class);

    private final FirefightingRequestRepository firefightingRequestRepository;
    private final FirefightingQuotationRepository firefightingQuotationRepository;
    private final SchoolRepository schoolRepository;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FirefightingService(
            FirefightingRequestRepository firefightingRequestRepository,
            FirefightingQuotationRepository firefightingQuotationRepository,
            SchoolRepository schoolRepository,
            AuditLogService auditLogService
    ) {
        this.firefightingRequestRepository = firefightingRequestRepository;
        this.firefightingQuotationRepository = firefightingQuotationRepository;
        this.schoolRepository = schoolRepository;
        this.auditLogService = auditLogService;
    }

    // ── List requests ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> listFireRequests(AuthUser actor) {
        return listFireRequests(actor, null, 0, 20);
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> listFireRequests(AuthUser actor, Long requestedSchoolId) {
        return listFireRequests(actor, requestedSchoolId, 0, 20);
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> listFireRequests(AuthUser actor, Long requestedSchoolId,
                                                               int page, int size) {
        Long schoolId = TenantContext.get() != null ? TenantContext.get() : requestedSchoolId;
        int clampedSize = Math.max(1, Math.min(size, 200));
        PageRequest pageable = PageRequest.of(Math.max(0, page), clampedSize);
        Page<FirefightingRequestEntity> dbPage = (schoolId == null)
                ? firefightingRequestRepository.findAllByOrderByCreatedAtDesc(pageable)
                : firefightingRequestRepository.findBySchool_IdOrderByCreatedAtDesc(schoolId, pageable);
        return PageResponse.of(dbPage.map(this::fireRequestRow));
    }

    // ── Stats ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> fireRequestStats(AuthUser actor) {
        return fireRequestStats(actor, null);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> fireRequestStats(AuthUser actor, Long requestedSchoolId) {
        Long schoolId = TenantContext.get() != null ? TenantContext.get() : requestedSchoolId;
        List<FirefightingRequestEntity> rows = (schoolId == null)
                ? firefightingRequestRepository.findAllByOrderByCreatedAtDesc()
                : firefightingRequestRepository.findBySchool_IdOrderByCreatedAtDesc(schoolId);
        long activeRequests = rows.stream()
                .filter(r -> !List.of("FULFILLED", "REJECTED").contains(
                        String.valueOf(r.getStatus()).toUpperCase(Locale.ROOT)))
                .count();
        long totalValue = rows.stream()
                .mapToLong(r -> r.getWinnerAmount() == null ? r.getEstimatedBudget() : r.getWinnerAmount())
                .sum();
        long custokingWins = rows.stream()
                .filter(r -> "Custoking".equalsIgnoreCase(r.getWinnerVendor())
                        || List.of("CUSTOKING_APPROVED", "FULFILLED").contains(
                                String.valueOf(r.getStatus()).toUpperCase(Locale.ROOT)))
                .count();
        long fulfilled = rows.stream()
                .filter(r -> "FULFILLED".equalsIgnoreCase(r.getStatus()))
                .count();
        return row("activeRequests", activeRequests, "totalValue", totalValue,
                "custokingWins", custokingWins, "fulfilled", fulfilled);
    }

    // ── Create request (status = DRAFT) ─────────────────────────

    public Map<String, Object> createFireRequest(Map<String, Object> request, AuthUser actor) {
        Long reqSchoolId = request.get("schoolId") == null ? null
                : longNum(request.get("schoolId"), -1) < 0 ? null
                : longNum(request.get("schoolId"), -1);
        Long schoolId = TenantContext.get() != null ? TenantContext.get() : reqSchoolId;
        FirefightingRequestEntity e = new FirefightingRequestEntity();
        e.setCode(nextFireCode());
        e.setSchool(resolveSchool(schoolId));
        e.setTitle(str(request.get("title"), "Request"));
        e.setCategory(str(request.get("category"), "Other"));
        e.setUrgency(str(request.get("urgency"), "MEDIUM").toUpperCase(Locale.ROOT));
        e.setRequiredByDate(parseNullableDate(str(request.get("requiredByDate"), "")));
        e.setEstimatedBudget(longNum(request.get("estimatedBudget"), 0));
        e.setDescription(str(firstPresent(request, "description", "summary"), ""));
        e.setReferenceFileUrl(trimToNull(str(request.get("referenceFileUrl"), "")));
        e.setRaisedBy(actor.userId());
        e.setStatus("DRAFT");
        e.setCustokingCriteriaJson(toJson(custokingCriteria(e.getCategory())));
        firefightingRequestRepository.save(e);
        log.info("fire.request.created requestId={} schoolId={} actorId={}",
                e.getCode(), e.getSchool() != null ? e.getSchool().getId() : null, actor.userId());
        return fireRequestDetailRow(e);
    }

    // ── Update request — DRAFT only ─────────────────────────────

    public Map<String, Object> updateFireRequest(String id, Map<String, Object> request, AuthUser actor) {
        FirefightingRequestEntity ff = firefightingRequestRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
        assertSchoolOwnership("request", ff.getSchool().getId(), TenantContext.get());
        if (!"DRAFT".equalsIgnoreCase(ff.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Request can only be edited in DRAFT status");
        }
        if (request.containsKey("title"))          ff.setTitle(str(request.get("title"), ff.getTitle()));
        if (request.containsKey("category"))       ff.setCategory(str(request.get("category"), ff.getCategory()));
        if (request.containsKey("urgency"))        ff.setUrgency(str(request.get("urgency"), ff.getUrgency()).toUpperCase(Locale.ROOT));
        if (request.containsKey("requiredByDate")) ff.setRequiredByDate(parseNullableDate(str(request.get("requiredByDate"), "")));
        if (request.containsKey("estimatedBudget")) ff.setEstimatedBudget(longNum(request.get("estimatedBudget"), ff.getEstimatedBudget()));
        if (request.containsKey("description"))   ff.setDescription(str(request.get("description"), ff.getDescription()));
        firefightingRequestRepository.save(ff);
        return fireRequestDetailRow(ff);
    }

    // ── Update quotation ─────────────────────────────────────────

    public Map<String, Object> updateFireQuotation(String id, String quotationId, Map<String, Object> request, AuthUser actor) {
        FirefightingRequestEntity ff = firefightingRequestRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
        assertSchoolOwnership("request", ff.getSchool().getId(), TenantContext.get());
        FirefightingQuotationEntity q = firefightingQuotationRepository.findById(quotationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Quotation not found"));
        if (request.containsKey("vendorName")) {
            q.setVendorName(str(request.get("vendorName"), q.getVendorName()));
            q.setCustoking("Custoking".equalsIgnoreCase(q.getVendorName()));
        }
        if (request.containsKey("amount"))           q.setAmount(longNum(request.get("amount"), q.getAmount()));
        if (request.containsKey("deliveryTimeline")) q.setDeliveryTimeline(str(request.get("deliveryTimeline"), q.getDeliveryTimeline()));
        if (request.containsKey("notes"))            q.setNotes(trimToNull(str(request.get("notes"), "")));
        if (request.containsKey("documentUrl"))      q.setDocumentUrl(trimToNull(str(request.get("documentUrl"), "")));
        firefightingQuotationRepository.save(q);
        return quotationRow(q);
    }

    // ── Detail ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> fireRequestDetail(String id, AuthUser actor) {
        return fireRequestDetail(id, actor, null);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> fireRequestDetail(String id, AuthUser actor, Long requestedSchoolId) {
        FirefightingRequestEntity e = firefightingRequestRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
        assertSchoolOwnership("request", e.getSchool().getId(),
                TenantContext.get() != null ? TenantContext.get() : requestedSchoolId);
        return fireRequestDetailRow(e);
    }

    // ── Add quotation ────────────────────────────────────────────
    // isCustoking = true when vendorName is "Custoking" (case-insensitive)

    public Map<String, Object> addFireQuotation(String id, Map<String, Object> request, AuthUser actor) {
        FirefightingRequestEntity ff = firefightingRequestRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
        assertSchoolOwnership("request", ff.getSchool().getId(), TenantContext.get());
        FirefightingQuotationEntity q = new FirefightingQuotationEntity();
        q.setId(UUID.randomUUID().toString());
        q.setRequest(ff);
        q.setVendorName(str(request.get("vendorName"), "Vendor"));
        q.setAmount(longNum(request.get("amount"), 0));
        q.setDeliveryTimeline(str(request.get("deliveryTimeline"), ""));
        q.setNotes(trimToNull(str(request.get("notes"), "")));
        q.setDocumentUrl(trimToNull(str(request.get("documentUrl"), "")));
        q.setCustoking("Custoking".equalsIgnoreCase(q.getVendorName()));
        firefightingQuotationRepository.save(q);
        return quotationRow(q);
    }

    // ── Delete quotation — only allowed in DRAFT status ──────────

    public Map<String, Object> deleteFireQuotation(String id, String quotationId, AuthUser actor) {
        FirefightingRequestEntity ff = firefightingRequestRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
        assertSchoolOwnership("request", ff.getSchool().getId(), TenantContext.get());
        if (!"DRAFT".equalsIgnoreCase(ff.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Quotation can be removed only in DRAFT status");
        }
        firefightingQuotationRepository.deleteById(quotationId);
        return row("ok", true);
    }

    // ── Submit — quotations optional ─────────────────────────────

    public Map<String, Object> submitFireRequest(String id, AuthUser actor) {
        FirefightingRequestEntity ff = firefightingRequestRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
        assertSchoolOwnership("request", ff.getSchool().getId(), TenantContext.get());
        if (!"DRAFT".equalsIgnoreCase(ff.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Request can only be submitted from DRAFT status (current: " + ff.getStatus() + ")");
        }
        ff.setStatus("AWAITING_BURSAR");
        firefightingRequestRepository.save(ff);
        auditLogService.statusTransition("ff_request", id, "DRAFT", "AWAITING_BURSAR", actor.userId());
        return fireRequestDetailRow(ff);
    }

    // ── Bursar approval ──────────────────────────────────────────

    public Map<String, Object> approveFireBursar(String id, Map<String, Object> request, AuthUser actor) {
        FirefightingRequestEntity ff = firefightingRequestRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
        assertSchoolOwnership("request", ff.getSchool().getId(), TenantContext.get());
        if (!"AWAITING_BURSAR".equalsIgnoreCase(ff.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Finance Review requires AWAITING_BURSAR status (current: " + ff.getStatus() + ")");
        }
        String oldStatus = ff.getStatus();
        ff.setBursarNote(trimToNull(str(request.get("note"), "")));
        ff.setBursarApprovedAt(OffsetDateTime.now());
        ff.setStatus("AWAITING_PRINCIPAL");
        firefightingRequestRepository.save(ff);
        log.info("fire.request.bursarApproved requestId={} actorId={} status={}", id, actor.userId(), ff.getStatus());
        auditLogService.statusTransition("ff_request", id, oldStatus, "AWAITING_PRINCIPAL", actor.userId());
        return fireRequestDetailRow(ff);
    }

    // ── Principal approval — winnerVendor/winnerAmount set only when quotation selected ──

    public Map<String, Object> approveFirePrincipal(String id, Map<String, Object> request, AuthUser actor) {
        FirefightingRequestEntity ff = firefightingRequestRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
        assertSchoolOwnership("request", ff.getSchool().getId(), TenantContext.get());
        if (!"AWAITING_PRINCIPAL".equalsIgnoreCase(ff.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Admin Approval requires AWAITING_PRINCIPAL status (current: " + ff.getStatus() + ")");
        }
        String oldStatus = ff.getStatus();
        ff.setPrincipalNote(trimToNull(str(request.get("note"), "")));
        ff.setPrincipalApprovedAt(OffsetDateTime.now());
        String qid = str(request.get("selectedQuotationId"), "");
        if (!qid.isBlank()) {
            firefightingQuotationRepository.findById(qid).ifPresent(q -> {
                ff.setWinnerVendor(q.getVendorName());
                ff.setWinnerAmount(q.getAmount());
            });
        }
        ff.setStatus("APPROVED");
        firefightingRequestRepository.save(ff);
        log.info("fire.request.principalApproved requestId={} actorId={} status={}", id, actor.userId(), ff.getStatus());
        auditLogService.statusTransition("ff_request", id, oldStatus, "APPROVED", actor.userId());
        return fireRequestDetailRow(ff);
    }

    // ── Custoking approval (superadmin) — APPROVED → CUSTOKING_APPROVED ──

    public Map<String, Object> approveCustoking(String id, AuthUser actor) {
        FirefightingRequestEntity ff = firefightingRequestRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
        if (!"APPROVED".equalsIgnoreCase(ff.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Move to Fulfilment requires APPROVED status (current: " + ff.getStatus() + ")");
        }
        String oldStatus = ff.getStatus();
        ff.setStatus("CUSTOKING_APPROVED");
        firefightingRequestRepository.save(ff);
        auditLogService.statusTransition("ff_request", id, oldStatus, "CUSTOKING_APPROVED", actor.userId());
        return fireRequestDetailRow(ff);
    }

    // ── Reject ───────────────────────────────────────────────────

    public Map<String, Object> rejectFireRequest(String id, Map<String, Object> request, AuthUser actor) {
        FirefightingRequestEntity ff = firefightingRequestRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
        assertSchoolOwnership("request", ff.getSchool().getId(), TenantContext.get());
        String oldStatus = ff.getStatus();
        ff.setRejectedBy(str(request.get("rejectedBy"), actor.fullName()));
        ff.setRejectedReason(str(firstPresent(request, "reason", "rejectedReason"), ""));
        ff.setStatus("REJECTED");
        firefightingRequestRepository.save(ff);
        auditLogService.statusTransition("ff_request", id, oldStatus, "REJECTED", actor.userId());
        return fireRequestDetailRow(ff);
    }

    // ── Fulfill (superadmin) ─────────────────────────────────────

    public Map<String, Object> fulfillFireRequest(String id, AuthUser actor) {
        FirefightingRequestEntity ff = firefightingRequestRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
        if (!"CUSTOKING_APPROVED".equalsIgnoreCase(ff.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Mark as Delivered requires CUSTOKING_APPROVED status (current: " + ff.getStatus() + ")");
        }
        String oldStatus = ff.getStatus();
        ff.setStatus("FULFILLED");
        firefightingRequestRepository.save(ff);
        auditLogService.statusTransition("ff_request", id, oldStatus, "FULFILLED", actor.userId());
        return fireRequestDetailRow(ff);
    }

    // ── Pending approvals (bursar + principal) ───────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> pendingFireApprovals(AuthUser actor) {
        return pendingFireApprovals(actor, null);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> pendingFireApprovals(AuthUser actor, Long requestedSchoolId) {
        Long schoolId = TenantContext.get() != null ? TenantContext.get() : requestedSchoolId;
        List<FirefightingRequestEntity> pending = new ArrayList<>();
        pending.addAll(firefightingRequestRepository.findBySchool_IdAndStatus(schoolId, "AWAITING_BURSAR"));
        pending.addAll(firefightingRequestRepository.findBySchool_IdAndStatus(schoolId, "AWAITING_PRINCIPAL"));
        pending.sort(Comparator.comparing(FirefightingRequestEntity::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return pending.stream().map(this::fireRequestDetailRow).toList();
    }

    // ── Timeline — always exactly 6 steps ────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> fireRequestTimeline(String id, AuthUser actor) {
        return fireRequestTimeline(id, actor, null);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> fireRequestTimeline(String id, AuthUser actor, Long requestedSchoolId) {
        FirefightingRequestEntity ff = firefightingRequestRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
        assertSchoolOwnership("request", ff.getSchool().getId(),
                TenantContext.get() != null ? TenantContext.get() : requestedSchoolId);
        String status = ff.getStatus();
        boolean submitted  = !List.of("DRAFT").contains(status);
        boolean bursarDone = ff.getBursarApprovedAt() != null;
        boolean prinDone   = ff.getPrincipalApprovedAt() != null;
        boolean custAppr   = List.of("CUSTOKING_APPROVED", "FULFILLED").contains(status);
        boolean fulfilled  = "FULFILLED".equals(status);
        return List.of(
                timeline("Request raised",       ff.getCreatedAt(), null, "done"),
                timeline("Submitted for approval",
                        submitted ? ff.getCreatedAt().plusMinutes(1) : null, null,
                        submitted ? "done" : "pending"),
                timeline("Bursar approved",      ff.getBursarApprovedAt(), ff.getBursarNote(),
                        bursarDone ? "done" : ("AWAITING_BURSAR".equals(status) ? "active" : "pending")),
                timeline("Principal approved",   ff.getPrincipalApprovedAt(), ff.getPrincipalNote(),
                        prinDone ? "done" : ("AWAITING_PRINCIPAL".equals(status) ? "active" : "pending")),
                row("title", "Custoking coordinating",
                        "meta",  ff.getWinnerVendor() == null ? "Pending vendor finalisation" : ff.getWinnerVendor(),
                        "note",  null,
                        "state", List.of("APPROVED", "CUSTOKING_APPROVED", "FULFILLED").contains(status) ? "done" : "pending"),
                row("title", "Custoking fulfils approved",
                        "meta",  custAppr ? "Approved by Custoking" : "Pending Custoking approval",
                        "note",  null,
                        "state", custAppr ? "done" : ("APPROVED".equals(status) ? "active" : "pending")),
                row("title", "Delivery & invoice",
                        "meta",  fulfilled ? "Fulfilled" : "Pending delivery",
                        "note",  null,
                        "state", fulfilled ? "done" : (custAppr ? "active" : "pending"))
        );
    }

    // ── Private helpers ──────────────────────────────────────────

    // FF-{nnn}: format("FF-%03d", maxExistingCode + 1)
    private String nextFireCode() {
        return String.format("FF-%03d",
                firefightingRequestRepository.findAll().stream()
                        .map(FirefightingRequestEntity::getCode)
                        .map(c -> c.replaceAll("\\D+", ""))
                        .filter(s -> !s.isBlank())
                        .mapToInt(Integer::parseInt)
                        .max().orElse(2) + 1);
    }

    private Map<String, Object> custokingCriteria(String category) {
        boolean met = List.of("Furniture & fixtures", "Lab equipment", "Sports & playground",
                "Services & AMC", "Events & occasions", "Health").contains(category);
        return row("met", met, "details", List.of("Category supported", "Deadline feasible",
                "Quantity feasible", "Budget aligned", "GST invoice ready", "Delivery support"));
    }

    private Map<String, Object> quotationRow(FirefightingQuotationEntity q) {
        return row("id", q.getId(), "vendorName", q.getVendorName(), "amount", q.getAmount(),
                "deliveryTimeline", q.getDeliveryTimeline(), "notes", q.getNotes(),
                "documentUrl", q.getDocumentUrl(), "isCustoking", q.isCustoking(),
                "isRecommended", q.isRecommended(),
                "createdAt", q.getCreatedAt() == null ? null : q.getCreatedAt().toString());
    }

    private Map<String, Object> fireRequestRow(FirefightingRequestEntity e) {
        List<Map<String, Object>> quotes = firefightingQuotationRepository.findByRequest_Code(e.getCode())
                .stream().map(this::quotationRow).toList();
        long best = quotes.stream().mapToLong(q -> longNum(q.get("amount"), 0))
                .min().orElse(e.getEstimatedBudget());
        return row("code", e.getCode(), "title", e.getTitle(), "category", e.getCategory(),
                "summary", e.getDescription(), "description", e.getDescription(),
                "amount", best, "estimatedBudget", e.getEstimatedBudget(),
                "quotesCount", quotes.size(),
                "winner", e.getWinnerVendor(), "winnerVendor", e.getWinnerVendor(),
                "winnerAmount", e.getWinnerAmount(), "status", e.getStatus(),
                "date", e.getCreatedAt() == null ? null : e.getCreatedAt().toLocalDate().toString(),
                "urgency", e.getUrgency(),
                "requiredByDate", e.getRequiredByDate() == null ? null : e.getRequiredByDate().toString());
    }

    private Map<String, Object> fireRequestDetailRow(FirefightingRequestEntity e) {
        Map<String, Object> m = new LinkedHashMap<>(fireRequestRow(e));
        m.put("quotations", firefightingQuotationRepository.findByRequest_Code(e.getCode())
                .stream().map(this::quotationRow).toList());
        m.put("bursarNote",       e.getBursarNote());
        m.put("principalNote",    e.getPrincipalNote());
        m.put("rejectedReason",   e.getRejectedReason());
        m.put("custokingCriteria", parseJsonMap(e.getCustokingCriteriaJson()));
        return m;
    }

    private Map<String, Object> timeline(String title, OffsetDateTime at, String note, String state) {
        return row("title", title,
                "meta",  at == null ? "Pending" : at.toLocalDate().toString(),
                "note",  note,
                "state", state);
    }

    private SchoolEntity resolveSchool(Long schoolId) {
        return schoolRepository.findById(schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "School not found"));
    }

    private void assertSchoolOwnership(String entityLabel, Long entitySchoolId, Long actorSchoolId) {
        if (actorSchoolId != null && entitySchoolId != null && !actorSchoolId.equals(entitySchoolId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You do not have access to this " + entityLabel);
        }
    }

    private LocalDate parseNullableDate(String input) {
        if (input == null || input.isBlank()) return null;
        return LocalDate.parse(input);
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Object firstPresent(Map<String, Object> request, String... keys) {
        for (String k : keys)
            if (request.containsKey(k) && request.get(k) != null) return request.get(k);
        return null;
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> row(Object... kv) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put(String.valueOf(kv[i]), kv[i + 1]);
        return map;
    }

    private String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private long longNum(Object value, long fallback) {
        if (value == null) return fallback;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(value).replace(",", "").trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
