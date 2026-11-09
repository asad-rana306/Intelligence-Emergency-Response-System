package com.iers.iot.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.Map;

/**
 * PHASE 6: the driver's phone works as the car box (for cars without the IoT box).
 * Same sensor fields as the box, plus:
 *   manual       true = the driver pressed and held the SOS button (no ML check, always dispatched)
 *   driverName   the phone sends its own user's name and phone, so the responder sees them
 *   driverPhone  (a box gets these from its pairing)
 */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class PhoneCrashRequest {
    @NotNull @JsonProperty("gForce") private Double gForce;
    @NotNull private Double rolloverAngle;
    @NotNull private Double speed;
    @NotNull private Double gpsLat;
    @NotNull private Double gpsLng;
    private Long timestamp;
    private Map<String, Object> sensorData;

    private Boolean manual;
    private String driverName;
    private String driverPhone;

    public boolean isManualSos() {
        return Boolean.TRUE.equals(manual);
    }

    public CrashPayloadRequest toCrashPayload() {
        return CrashPayloadRequest.builder()
                .gForce(gForce).rolloverAngle(rolloverAngle).speed(speed)
                .gpsLat(gpsLat).gpsLng(gpsLng)
                .timestamp(timestamp).sensorData(sensorData)
                .build();
    }
}
