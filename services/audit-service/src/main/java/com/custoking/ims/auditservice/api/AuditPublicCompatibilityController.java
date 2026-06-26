package com.custoking.ims.auditservice.api;

import com.custoking.ims.auditservice.persistence.AuditEvent;
import com.custoking.ims.auditservice.persistence.AuditEventRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@RestController
public class AuditPublicCompatibilityController {

    private final AuditEventRepository repository;
    private final String ingestToken;

    public AuditPublicCompatibilityController(
            AuditEventRepository repository,
            @Value("${audit.ingest-token:}") String ingestToken) {
        this.repository = repository;
        this.ingestToken = ingestToken;
    }

    @GetMapping("/api/v1/audit-logs")
    public AuditIngestController.AuditEventPage auditLogs(
            @RequestHeader(value = "X-Audit-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) Integer limit) {
        requireToken(token, "audit:read");
        int safePage = Math.max(page, 0);
        int safeSize = limit == null ? Math.max(1, Math.min(size, 200)) : Math.max(1, Math.min(limit, 200));
        var result = repository.findAll(specification(schoolId, userId, action, from, to),
                PageRequest.of(safePage, safeSize, Sort.by("eventTimestamp").descending()));
        return new AuditIngestController.AuditEventPage(
                result.getContent().stream().map(AuditIngestController.AuditEventEntry::from).toList(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.getNumber(),
                result.getSize());
    }

    private Specification<AuditEvent> specification(Long schoolId, Long userId, String action, OffsetDateTime from, OffsetDateTime to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (schoolId != null) predicates.add(cb.equal(root.get("schoolId"), schoolId));
            if (userId != null) predicates.add(cb.equal(root.get("userId"), userId));
            if (StringUtils.hasText(action)) predicates.add(cb.equal(root.get("action"), action));
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("eventTimestamp"), from));
            if (to != null) predicates.add(cb.lessThanOrEqualTo(root.get("eventTimestamp"), to));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope) || !StringUtils.hasText(ingestToken) || !ingestToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid audit ingest token");
        }
    }
}
