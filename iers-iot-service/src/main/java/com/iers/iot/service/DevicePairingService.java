package com.iers.iot.service;

import com.iers.iot.dto.request.DevicePairRequest;
import com.iers.iot.dto.response.DeviceStatusResponse;
import com.iers.iot.entity.DevicePairing;
import com.iers.iot.repository.DevicePairingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DevicePairingService {

    private final DevicePairingRepository pairingRepository;
    private final HeartbeatService heartbeatService;

    @Transactional
    public DeviceStatusResponse pairDevice(UUID userId, DevicePairRequest request) {
        // Upsert: if device already paired, update the pairing
        DevicePairing pairing = pairingRepository.findByDeviceId(request.getDeviceId())
                .orElse(DevicePairing.builder().deviceId(request.getDeviceId()).build());

        pairing.setUserId(userId);
        pairing.setDriverName(request.getDriverName());
        pairing.setDriverPhone(request.getDriverPhone());
        pairing = pairingRepository.save(pairing);

        log.info("Device paired: device={}, user={}", request.getDeviceId(), userId);

        return DeviceStatusResponse.builder()
                .deviceId(pairing.getDeviceId())
                .userId(userId.toString())
                .paired(true)
                .build();
    }

    public DeviceStatusResponse getDeviceStatus(String deviceId) {
        DevicePairing pairing = pairingRepository.findByDeviceId(deviceId).orElse(null);
        String lastHeartbeat = heartbeatService.getLatestHeartbeat(deviceId);

        return DeviceStatusResponse.builder()
                .deviceId(deviceId)
                .userId(pairing != null ? pairing.getUserId().toString() : null)
                .paired(pairing != null)
                .lastHeartbeat(lastHeartbeat)
                .build();
    }
}
