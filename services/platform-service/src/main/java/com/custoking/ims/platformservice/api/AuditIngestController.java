package com.custoking.ims.platformservice.api;

import com.custoking.ims.platformservice.persistence.AuditEvent;
import com.custoking.ims.platformservice.persistence.AuditEventRepository;
import com.custoking.ims.platformservice.security.TenantScope;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/audit/events")
public class AuditIngestController {

    private static final Logger log = LoggerFactory.getLogger(AuditIngestController.class);

    private final AuditEventRepository repository;
    private final String ingestToken;

    public AuditIngestController(
            AuditEventRepository repository,
            @Value("${audit.ingest-token:}") String ingestToken) {
        this.repository = repository;
        this.ingestToken = ingestToken;
    }

    @PostMapping
    public ResponseEntity<AuditEventResponse> ingest(
            @RequestHeader(value = "X-Audit-Service-Token", required = false) String token,
            @RequestBody AuditEventRequest request) {
        requireToken(token, "audit:ingest");
        if (!StringUtils.hasText(request.action())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action is required");
        }

        AuditEvent event = new AuditEvent();
        event.setAction(request.action());
        event.setUserId(request.userId());
        event.setSchoolId(request.schoolId());
        event.setEntityType(request.entityType());
        event.setEntityId(request.entityId());
        event.setIpAddress(trim(request.ipAddress(), 64));
        event.setUserAgent(trim(request.userAgent(), 512));
        event.setRequestId(trim(request.requestId(), 64));
        event.setActorEmail(trim(request.actorEmail(), 255));
        event.setOldValue(request.oldValue());
        event.setNewValue(request.newValue());
        event.setOutcome(StringUtils.hasText(request.outcome()) ? request.outcome() : "SUCCESS");
        event.setEventTimestamp(request.timestamp() != null ? request.timestamp() : OffsetDateTime.now());

        AuditEvent saved = repository.save(event);
        log.info("ingested audit event id={} action={} userId={} schoolId={} outcome={}",
                saved.getId(), saved.getAction(), saved.getUserId(), saved.getSchoolId(), saved.getOutcome());
        return ResponseEntity.status(HttpStatus.CREATED).body(new AuditEventResponse(saved.getId(), "ACCEPTED"));
    }

    @GetMapping
    public AuditEventPage query(
            @RequestHeader(value = "X-Audit-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        requireToken(token, "audit:read");
        schoolId = TenantScope.resolveSchoolId(schoolId);
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(1, Math.min(size, 200));
        PageRequest pageable = PageRequest.of(safePage, safeSize, Sort.by("eventTimestamp").descending());
        Page<AuditEvent> result = repository.findAll(specification(schoolId, userId, action, from, to), pageable);
        return new AuditEventPage(
                result.getContent().stream().map(AuditEventEntry::from).toList(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.getNumber(),
                result.getSize());
    }

    private Specification<AuditEvent> specification(
            Long schoolId,
            Long userId,
            String action,
            OffsetDateTime from,
            OffsetDateTime to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (schoolId != null) {
                predicates.add(cb.equal(root.get("schoolId"), schoolId));
            }
            if (userId != null) {
                predicates.add(cb.equal(root.get("userId"), userId));
            }
            if (StringUtils.hasText(action)) {
                predicates.add(cb.equal(root.get("action"), action));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("eventTimestamp"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("eventTimestamp"), to));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing internal route scope" );
        }
        if (!StringUtils.hasText(ingestToken) || !ingestToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid audit ingest token");
        }
    }

    private String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public record AuditEventRequest(
            String action,
            Long userId,
            Long schoolId,
            String entityType,
            String entityId,
            String ipAddress,
            String userAgent,
            String requestId,
            String actorEmail,
            String oldValue,
            String newValue,
            String outcome,
            OffsetDateTime timestamp) {
    }

    public record AuditEventResponse(Long id, String status) {
    }

    public record AuditEventPage(
            List<AuditEventEntry> content,
            long totalElements,
            int totalPages,
            int page,
            int size) {
    }

    public record AuditEventEntry(
            Long id,
            String action,
            Long userId,
            Long schoolId,
            String entityType,
            String entityId,
            String ipAddress,
            String userAgent,
            String requestId,
            String actorEmail,
            String oldValue,
            String newValue,
            String outcome,
            OffsetDateTime timestamp) {

        public static AuditEventEntry from(AuditEvent event) {
            return new AuditEventEntry(
                    event.getId(),
                    event.getAction(),
                    event.getUserId(),
                    event.getSchoolId(),
                    event.getEntityType(),
                    event.getEntityId(),
                    event.getIpAddress(),
                    event.getUserAgent(),
                    event.getRequestId(),
                    event.getActorEmail(),
                    event.getOldValue(),
                    event.getNewValue(),
                    event.getOutcome(),
                    event.getEventTimestamp());
        }
    }
}

