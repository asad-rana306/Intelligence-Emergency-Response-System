package com.iers.iot.dto.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The contract key is "gForce". Lombok's getGForce() must not turn it into "gforce" for Jackson. */
class CrashPayloadRequestJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsGForceKeyFromTheContract() throws Exception {
        String json = "{\"gForce\":12.4,\"rolloverAngle\":8,\"speed\":41,\"gpsLat\":31.5204,\"gpsLng\":74.3587}";
        CrashPayloadRequest r = mapper.readValue(json, CrashPayloadRequest.class);
        assertThat(r.getGForce()).isEqualTo(12.4);
        assertThat(r.getRolloverAngle()).isEqualTo(8.0);
        assertThat(r.getSpeed()).isEqualTo(41.0);
    }

    @Test
    void readsOptionalTimestampAndSensorData() throws Exception {
        String json = "{\"gForce\":15,\"rolloverAngle\":5,\"speed\":95,\"gpsLat\":31.5,\"gpsLng\":74.3,"
                + "\"timestamp\":1760000000000,\"sensorData\":{\"accelX\":-13.5}}";
        CrashPayloadRequest r = mapper.readValue(json, CrashPayloadRequest.class);
        assertThat(r.getGForce()).isEqualTo(15.0);
        assertThat(r.getTimestamp()).isEqualTo(1760000000000L);
        assertThat(r.getSensorData()).containsKey("accelX");
    }
}
