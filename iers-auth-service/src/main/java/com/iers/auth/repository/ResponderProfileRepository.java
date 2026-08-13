package com.iers.auth.repository;

import com.iers.auth.entity.ResponderProfile;
import com.iers.auth.entity.enums.DutyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResponderProfileRepository extends JpaRepository<ResponderProfile, UUID> {
    Optional<ResponderProfile> findByUserId(UUID userId);

    @Query("SELECT rp FROM ResponderProfile rp JOIN FETCH rp.user " +
           "WHERE rp.dutyStatus = :status AND rp.currentLat IS NOT NULL AND rp.currentLng IS NOT NULL")
    List<ResponderProfile> findAllByDutyStatusWithUser(DutyStatus status);
}
