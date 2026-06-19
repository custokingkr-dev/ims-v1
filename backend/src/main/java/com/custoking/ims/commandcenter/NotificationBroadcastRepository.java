package com.custoking.ims.commandcenter;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface NotificationBroadcastRepository extends JpaRepository<NotificationBroadcastEntity, UUID> {

    List<NotificationBroadcastEntity> findBySchoolIdOrderByCreatedAtDesc(Long schoolId);

    List<NotificationBroadcastEntity> findAllByOrderByCreatedAtDesc();

    boolean existsByTitleAndSchoolId(String title, Long schoolId);
}
