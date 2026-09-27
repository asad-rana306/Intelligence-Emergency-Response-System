package com.iers.iot.repository;

import com.iers.iot.entity.DevicePairing;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface DevicePairingRepository extends JpaRepository<DevicePairing, UUID> {
    Optional<DevicePairing> findByDeviceId(String deviceId);
    boolean existsByDeviceId(String deviceId);
}
