# IERS IoT & Telemetry Service — API Documentation

## Overview

High-throughput ingestion layer. Receives crash payloads from car embedded devices (HTTP and SMS), enforces idempotency via Redis, runs ML triage via an external Python service, manages the 10-second cancellation window, and publishes events to Kafka.

**Base URL:** `http://localhost:8082` (direct) or `http://localhost:8080/api/telemetry` (via gateway)

---

## Crash Ingestion Endpoints

### POST /api/telemetry/crash

Primary crash ingestion from car device. Authenticated by device API key at the gateway.

**Headers:** `X-Device-Id: DEV-001`, `X-Device-Api-Key: <key>` (validated at gateway)

**Request:**
```json
{
  "gForce": 8.5,
  "rolloverAngle": 45.0,
  "speed": 120.0,
  "gpsLat": 37.7749,
  "gpsLng": -122.4194,
  "timestamp": 1723300800000,
  "sensorData": {
    "accelerometerX": 2.3,
    "accelerometerY": -8.1,
    "gyroscopeRoll": 44.7
  }
}
```

**Response (202 Accepted):**
```json
{
  "crashEventId": "a1b2c3d4-...",
  "status": "RECEIVED",
  "message": "Crash registered. 10-second cancellation window started."
}
```

**Duplicate Response (200 OK):**
```json
{
  "status": "DUPLICATE",
  "message": "Crash payload already received"
}
```

---

### POST /api/telemetry/crash/sms

SMS failsafe. Twilio forwards the inbound SMS as form-encoded data.

**Body format (car GSM module):** `CRASH|8.5|45.0|120.0|37.7749|-122.4194|DEV-001`

**Response (202 Accepted):** SMS bypasses the 10-second window — goes directly to ML + Kafka.

---

### POST /api/telemetry/crash/{crashEventId}/cancel

Driver taps "I AM OK" within the 10-second window.

**Response (200 OK):**
```json
{
  "crashEventId": "a1b2c3d4-...",
  "status": "CANCELLED",
  "message": "Crash alert cancelled. No dispatch will occur."
}
```

**If window expired:**
```json
{
  "status": "TOO_LATE",
  "message": "Cancellation window expired. Use late-cancel instead."
}
```

---

### POST /api/telemetry/crash/{crashEventId}/late-cancel

Late cancellation after the 10-second window. Publishes `CRASH_CANCELLED` to Kafka.

**Response (200 OK):**
```json
{
  "crashEventId": "a1b2c3d4-...",
  "status": "LATE_CANCELLED",
  "message": "Late cancellation processed. Dispatch will be notified to stand down."
}
```

---

### POST /api/telemetry/crash/{crashEventId}/media

Bystander uploads photo or voice note from the locked emergency screen.

**Content-Type:** `multipart/form-data`

| Field         | Type          | Required | Description              |
|---------------|---------------|----------|--------------------------|
| `file`        | File          | Yes      | Photo or audio file      |
| `type`        | String        | Yes      | `PHOTO` or `VOICE_NOTE`  |
| `description` | String        | No       | Bystander description    |

**Response (201 Created):**
```json
{
  "mediaId": "uuid-...",
  "status": "UPLOADED"
}
```

---

## Heartbeat Endpoint

### POST /api/telemetry/heartbeat

Periodic health ping from car device. Stored in Redis (NOT Postgres) with 60-second TTL.

**Headers:** `X-Device-Id: DEV-001`

**Request:**
```json
{
  "speed": 65.0,
  "gpsLat": 37.7749,
  "gpsLng": -122.4194,
  "fuelLevel": 72.5,
  "hardwareHealth": "OK",
  "timestamp": 1723300800000
}
```

**Response:** `200 OK` (empty body)

---

## Device Endpoints

### POST /api/devices/pair

Pair a car device with a driver. Caches driver name/phone for Kafka messages.

**Headers:** `X-User-Id: <uuid>` (from JWT via gateway)

**Request:**
```json
{
  "deviceId": "DEV-001",
  "driverName": "John Doe",
  "driverPhone": "+14155551234"
}
```

**Response (201 Created):**
```json
{
  "deviceId": "DEV-001",
  "userId": "user-uuid-...",
  "paired": true
}
```

### GET /api/devices/{deviceId}/status

**Response (200 OK):**
```json
{
  "deviceId": "DEV-001",
  "userId": "user-uuid-...",
  "paired": true,
  "lastHeartbeat": "{\"speed\":65.0, ...}"
}
```

---

## Kafka Event Schema

**Topic:** `crash-events`

### CRASH_DETECTED
```json
{
  "eventType": "CRASH_DETECTED",
  "crashEventId": "uuid",
  "driverId": "uuid",
  "deviceId": "DEV-001",
  "driverName": "John Doe",
  "driverPhone": "+14155551234",
  "gpsLat": 37.7749,
  "gpsLng": -122.4194,
  "speed": 120.0,
  "gForce": 8.5,
  "priorityScore": 4,
  "source": "HTTP",
  "reason": null,
  "timestamp": "2026-08-10T12:00:00Z"
}
```

### CRASH_CANCELLED
```json
{
  "eventType": "CRASH_CANCELLED",
  "crashEventId": "uuid",
  "driverId": "uuid",
  "deviceId": "DEV-001",
  "driverName": "John Doe",
  "driverPhone": "+14155551234",
  "reason": "LATE_CANCEL",
  "timestamp": "2026-08-10T12:01:00Z"
}
```

---

## System Design Constraints Implemented

### Idempotency (Redis SETNX)
- Dedup key: `crash:dedup:{deviceId}:{timestamp_floored_to_second}`
- TTL: 5 minutes
- Checked BEFORE any database write or ML call (near-zero cost rejection)

### 10-Second Cancellation Window
- Server-side `ScheduledExecutorService` (4 threads)
- Pending timers tracked in `ConcurrentHashMap<UUID, ScheduledFuture<?>>`
- Cancel removes from map and calls `future.cancel(false)`

### ML Service Circuit Breaker (Resilience4j)
- Sliding window: 10 calls, 50% failure threshold
- Open state: 30 seconds, half-open: 3 permitted calls
- Slow call threshold: 3 seconds
- **Fallback:** `isSevere=true, priorityScore=3` (fail-safe, not fail-open)

---

## Environment Variables

| Variable         | Required | Default                        | Description                   |
|------------------|----------|--------------------------------|-------------------------------|
| `DB_HOST`        | No       | `localhost`                    | PostgreSQL host               |
| `DB_PORT`        | No       | `5432`                         | PostgreSQL port               |
| `DB_NAME`        | No       | `iers_iot`                     | Database name                 |
| `DB_USERNAME`    | No       | `postgres`                     | Database user                 |
| `DB_PASSWORD`    | YES      | —                              | Database password             |
| `REDIS_HOST`     | No       | `localhost`                    | Redis host                    |
| `REDIS_PORT`     | No       | `6379`                         | Redis port                    |
| `KAFKA_BOOTSTRAP`| No       | `localhost:9092`               | Kafka broker(s)               |
| `ML_SERVICE_URL` | No       | `http://localhost:8000`        | Python ML service URL         |
| `EUREKA_URL`     | No       | `http://localhost:8761/eureka` | Eureka server URL             |
| `MEDIA_UPLOAD_DIR`| No      | `/tmp/iers-uploads`            | Bystander media storage path  |

---

## Database Schema

**PostgreSQL database:** `iers_iot`

| Table             | Key Columns                                                                      |
|-------------------|----------------------------------------------------------------------------------|
| `crash_events`    | id (UUID PK), device_id, driver_id, g_force, rollover_angle, speed, gps_lat/lng, raw_payload (JSONB), source, ml_severity, priority_score, status, cancellation_deadline |
| `bystander_media` | id (UUID PK), crash_event_id, media_type, file_path, uploaded_by_description     |
| `device_pairings` | id (UUID PK), device_id (unique), user_id, driver_name, driver_phone             |
