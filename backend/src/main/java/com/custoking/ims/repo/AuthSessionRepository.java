package com.custoking.ims.repo;

import com.custoking.ims.entity.AuthSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface AuthSessionRepository extends JpaRepository<AuthSessionEntity, String> {

    Optional<AuthSessionEntity> findByRefreshTokenHash(String refreshTokenHash);

    void deleteByUser_Id(Long userId);

    long deleteByExpiresAtBefore(OffsetDateTime cutoff);
}
