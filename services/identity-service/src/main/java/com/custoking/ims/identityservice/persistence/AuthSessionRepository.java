package com.custoking.ims.identityservice.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface AuthSessionRepository extends JpaRepository<AuthSessionEntity, String> {

    Optional<AuthSessionEntity> findByRefreshTokenHash(String refreshTokenHash);
    long deleteByExpiresAtBefore(OffsetDateTime cutoff);

    List<AuthSessionEntity> findByFamilyId(String familyId);

    // Literal 'REVOKED' must equal AuthSessionEntity.REVOKED — literal preferred for JPA provider reliability.
    @Modifying
    @Query("update AuthSessionEntity s set s.status = 'REVOKED' where s.familyId = :familyId")
    int revokeFamily(@Param("familyId") String familyId);
}
