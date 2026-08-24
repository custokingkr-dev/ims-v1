package com.custoking.ims.identityservice.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface AuthSessionRepository extends JpaRepository<AuthSessionEntity, String> {

    Optional<AuthSessionEntity> findByRefreshTokenHash(String refreshTokenHash);

    /**
     * Locks the presented token row for the duration of refresh rotation.
     *
     * <p>Without the write lock, two transactions can both read {@code ACTIVE}, retire the same
     * token, and issue independent successors. A waiter resumes after the first transaction commits
     * and observes the new lifecycle state, allowing the service's normal reuse-detection path to
     * revoke and audit the family.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AuthSessionEntity s where s.refreshTokenHash = :refreshTokenHash")
    Optional<AuthSessionEntity> findByRefreshTokenHashForUpdate(
            @Param("refreshTokenHash") String refreshTokenHash);

    long deleteByExpiresAtBefore(OffsetDateTime cutoff);

    List<AuthSessionEntity> findByFamilyId(String familyId);

    // Literal 'REVOKED' must equal AuthSessionEntity.REVOKED — literal preferred for JPA provider reliability.
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update AuthSessionEntity s set s.status = 'REVOKED' where s.familyId = :familyId")
    int revokeFamily(@Param("familyId") String familyId);
}
