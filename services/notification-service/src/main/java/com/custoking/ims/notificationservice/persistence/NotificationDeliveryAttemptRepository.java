package com.custoking.ims.notificationservice.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationDeliveryAttemptRepository extends JpaRepository<NotificationDeliveryAttempt, Long> {
    long countByStatus(String status);

    List<NotificationDeliveryAttempt> findByEventIdOrderByAttemptedAtDesc(String eventId);
}
