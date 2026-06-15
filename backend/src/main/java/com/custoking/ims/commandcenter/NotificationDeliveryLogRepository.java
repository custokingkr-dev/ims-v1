package com.custoking.ims.commandcenter;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface NotificationDeliveryLogRepository extends JpaRepository<NotificationDeliveryLogEntity, UUID> {

    List<NotificationDeliveryLogEntity> findByBroadcastId(UUID broadcastId);

    long countByBroadcastIdAndStatus(UUID broadcastId, String status);

    @Query("SELECT DISTINCT l.channel FROM NotificationDeliveryLogEntity l WHERE l.broadcastId = :broadcastId")
    List<String> findDistinctChannelsByBroadcastId(@Param("broadcastId") UUID broadcastId);

    boolean existsByBroadcastId(UUID broadcastId);
}
