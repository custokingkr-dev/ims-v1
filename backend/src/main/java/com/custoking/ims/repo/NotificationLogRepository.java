package com.custoking.ims.repo;

import com.custoking.ims.entity.NotificationLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationLogRepository extends JpaRepository<NotificationLogEntity, String> {

    Optional<NotificationLogEntity> findTopByStudentIdAndNotificationTypeOrderBySentAtDesc(
            Long studentId, String notificationType);

    List<NotificationLogEntity> findByStudentIdInAndNotificationType(
            List<Long> studentIds, String notificationType);
}
