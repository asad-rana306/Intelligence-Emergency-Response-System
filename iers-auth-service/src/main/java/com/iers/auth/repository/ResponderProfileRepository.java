package com.iers.auth.repository;

import com.iers.auth.entity.ResponderProfile;
import com.iers.auth.entity.enums.DutyStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResponderProfileRepository extends JpaRepository<ResponderProfile, UUID> {

    Optional<ResponderProfile> findByUserId(UUID userId);

    /**
     * Responders Dispatch may offer an incident to: on duty, with a known position,
     * shift not finished, and a phone that reported recently.
     * A row without timestamps (older data) is NOT available until the app sends a heartbeat.
     */
    @Query("SELECT rp FROM ResponderProfile rp JOIN FETCH rp.user " +
           "WHERE rp.dutyStatus = :status " +
           "AND rp.currentLat IS NOT NULL AND rp.currentLng IS NOT NULL " +
           "AND rp.dutyExpiresAt > :now " +
           "AND rp.lastHeartbeatAt >= :freshAfter")
    List<ResponderProfile> findAvailable(DutyStatus status, Instant now, Instant freshAfter);

    /**
     * Responders that must go OFF duty: the shift is over, or the phone went silent.
     * A row without timestamps counts as due, so old ON_DUTY rows are cleaned up too.
     */
    @Query("SELECT rp FROM ResponderProfile rp JOIN FETCH rp.user " +
           "WHERE rp.dutyStatus = :activeStatus " +
           "AND (rp.dutyExpiresAt IS NULL OR rp.dutyExpiresAt <= :now " +
           "     OR rp.lastHeartbeatAt IS NULL OR rp.lastHeartbeatAt < :staleBefore) " +
           "ORDER BY rp.id")
    List<ResponderProfile> findDue(DutyStatus activeStatus, Instant now, Instant staleBefore, Pageable page);

    /**
     * Puts ONE responder OFF duty, but only if it is STILL due at this moment.
     * Returns 1 for the caller that really changed the row, 0 for everybody else
     * (another Auth instance was faster, or a heartbeat arrived meanwhile).
     * This is what keeps two instances from sending the same event twice.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ResponderProfile rp " +
           "SET rp.dutyStatus = :offStatus, rp.dutyStartedAt = NULL, rp.dutyExpiresAt = NULL, rp.lastHeartbeatAt = NULL " +
           "WHERE rp.id = :id AND rp.dutyStatus = :activeStatus " +
           "AND (rp.dutyExpiresAt IS NULL OR rp.dutyExpiresAt <= :now " +
           "     OR rp.lastHeartbeatAt IS NULL OR rp.lastHeartbeatAt < :staleBefore)")
    int expireIfStillDue(UUID id, Instant now, Instant staleBefore, DutyStatus activeStatus, DutyStatus offStatus);
}
