package com.custoking.ims.commandcenter;

import com.custoking.ims.commandcenter.dto.BroadcastCreateRequest;
import com.custoking.ims.commandcenter.dto.BroadcastResponse;
import com.custoking.ims.commandcenter.dto.DeliveryStatusResponse;
import com.custoking.ims.model.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class NotificationBroadcastService {

    private final NotificationBroadcastRepository broadcastRepo;
    private final NotificationDeliveryLogRepository deliveryRepo;
    private final CommandCenterService feedService;

    public NotificationBroadcastService(NotificationBroadcastRepository broadcastRepo,
                                        NotificationDeliveryLogRepository deliveryRepo,
                                        CommandCenterService feedService) {
        this.broadcastRepo = broadcastRepo;
        this.deliveryRepo = deliveryRepo;
        this.feedService = feedService;
    }

    @Transactional(readOnly = true)
    public List<BroadcastResponse> getAll(AuthUser actor, Long schoolId) {
        List<NotificationBroadcastEntity> list = actor.platformAdmin()
                ? broadcastRepo.findAllByOrderByCreatedAtDesc()
                : schoolId != null
                    ? broadcastRepo.findBySchoolIdOrderByCreatedAtDesc(schoolId)
                    : List.of();
        return list.stream().map(BroadcastResponse::from).toList();
    }

    public BroadcastResponse create(BroadcastCreateRequest req, AuthUser actor, Long schoolId) {
        NotificationBroadcastEntity b = new NotificationBroadcastEntity();
        b.setSchoolId(schoolId);
        b.setModule(req.module());
        b.setTitle(req.title());
        b.setMessage(req.message());
        b.setAudienceType(req.audienceType());
        b.setChannels(req.channels() != null ? String.join(",", req.channels()) : "SMS");
        b.setStatus("DRAFT");
        b.setScheduledAt(req.scheduledAt());
        b.setCreatedBy(actor.userId());
        broadcastRepo.save(b);
        feedService.recordFeed(schoolId, req.module(), "BROADCAST_CREATED",
                "Broadcast draft created: " + req.title(), "info", actor.userId());
        return BroadcastResponse.from(b);
    }

    public BroadcastResponse send(UUID id, AuthUser actor) {
        NotificationBroadcastEntity b = broadcastRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Broadcast not found"));
        assertAccess(b, actor);
        b.setStatus("SENT");
        b.setSentBy(actor.userId());
        b.setSentAt(OffsetDateTime.now());
        broadcastRepo.save(b);
        feedService.recordFeed(b.getSchoolId(), b.getModule(), "BROADCAST_SENT",
                "Broadcast sent: " + b.getTitle(), "success", actor.userId());
        return BroadcastResponse.from(b);
    }

    public BroadcastResponse approve(UUID id, AuthUser actor) {
        NotificationBroadcastEntity b = broadcastRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Broadcast not found"));
        assertAccess(b, actor);
        b.setStatus("SCHEDULED");
        b.setApprovedBy(actor.userId());
        b.setApprovedAt(OffsetDateTime.now());
        broadcastRepo.save(b);
        feedService.recordFeed(b.getSchoolId(), b.getModule(), "BROADCAST_APPROVED",
                "Broadcast approved: " + b.getTitle(), "success", actor.userId());
        return BroadcastResponse.from(b);
    }

    @Transactional(readOnly = true)
    public DeliveryStatusResponse getDeliveryStatus(UUID id) {
        broadcastRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Broadcast not found"));
        long delivered = deliveryRepo.countByBroadcastIdAndStatus(id, "DELIVERED");
        long failed = deliveryRepo.countByBroadcastIdAndStatus(id, "FAILED");
        long pending = deliveryRepo.countByBroadcastIdAndStatus(id, "PENDING");
        List<String> channels = deliveryRepo.findDistinctChannelsByBroadcastId(id);
        int total = (int) (delivered + failed + pending);
        return new DeliveryStatusResponse(id, total, (int) delivered, (int) failed, (int) pending, channels);
    }

    private void assertAccess(NotificationBroadcastEntity b, AuthUser actor) {
        if (!actor.platformAdmin() && b.getSchoolId() != null) {
            Long actorSchool = com.custoking.ims.context.TenantContext.get();
            if (actorSchool != null && !actorSchool.equals(b.getSchoolId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
            }
        }
    }
}
