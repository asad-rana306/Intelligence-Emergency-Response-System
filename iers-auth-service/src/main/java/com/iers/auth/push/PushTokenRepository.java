package com.iers.auth.push;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PushTokenRepository extends JpaRepository<PushTokenEntity, UUID> {
    List<PushTokenEntity> findAllByUserId(UUID userId);
    Optional<PushTokenEntity> findByToken(String token);

    @Transactional
    void deleteByToken(String token);
}
