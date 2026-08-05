package com.custoking.ims.platformservice.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface NotificationInboxRepository extends JpaRepository<NotificationInboxEvent, String> {
    List<NotificationInboxEvent> findByStatusOrderByReceivedAtAsc(String status, Pageable pageable);

    @Query("SELECT e FROM NotificationInboxEvent e WHERE e.status = :status AND (e.nextAttemptAt IS NULL OR e.nextAttemptAt <= :now) ORDER BY e.receivedAt ASC")
    List<NotificationInboxEvent> findRetryable(@Param("status") String status,
                                                @Param("now") OffsetDateTime now,
                                                Pageable pageable);

    long countByStatus(String status);

    @Query("SELECT MIN(e.receivedAt) FROM NotificationInboxEvent e WHERE e.status = :status")
    Optional<OffsetDateTime> findOldestReceivedAtByStatus(@Param("status") String status);
}
