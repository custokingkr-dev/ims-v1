package com.custoking.ims.repo;
import com.custoking.ims.entity.AuthSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface AuthSessionRepository extends JpaRepository<AuthSessionEntity, String> { Optional<AuthSessionEntity> findByAccessToken(String accessToken); Optional<AuthSessionEntity> findByRefreshToken(String refreshToken); void deleteByUser_Id(Long userId); }
