package com.iers.auth.repository;

import com.iers.auth.entity.MedicalProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MedicalProfileRepository extends JpaRepository<MedicalProfile, UUID> {
    Optional<MedicalProfile> findByUserId(UUID userId);
    boolean existsByUserId(UUID userId);
}
