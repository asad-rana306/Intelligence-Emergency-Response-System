package com.iers.auth.repository;

import com.iers.auth.entity.PushToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PushTokenRepository extends JpaRepository<PushToken, UUID> {

    Optional<PushToken> findByToken(String token);

    /** Newest first. */
    List<PushToken> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    @Modifying
    @Query("DELETE FROM PushToken t WHERE t.token = :token AND t.userId = :userId")
    int deleteByTokenAndUser(String token, UUID userId);

    @Modifying
    @Query("DELETE FROM PushToken t WHERE t.token IN :tokens")
    int deleteAllByTokenIn(Collection<String> tokens);
}
