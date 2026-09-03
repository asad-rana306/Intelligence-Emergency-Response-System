# IERS Dispatch & Notification Service — API Documentation

## Overview

Kafka consumer, PostGIS spatial dispatch engine, 30-second auto-escalation, STOMP WebSocket live GPS streaming via Redis, and Twilio SMS notifications. This service receives crash events from the IoT Service via Kafka and orchestrates the full rescue lifecycle from dispatch to hospital handoff.

**Base URL:** `http://localhost:8083` (direct) or `http://localhost:8080` (via gateway)

---

## Dispatch Endpoints

### POST /api/dispatch/{incidentId}/accept

Responder taps "ACCEPT DISPATCH". Locks in the mission, cancels escalation timer, marks responder ON_MISSION.

**Headers:** `X-User-Id: <responder-uuid>`

**Response (200 OK):**
```json
{
  "incidentId": "uuid",
  "status": "ACCEPTED",
  "message": "Dispatch accepted. GPS streaming enabled."
}
```

### POST /api/dispatch/{incidentId}/reject

Responder rejects. Immediately escalates to next-closest available unit.

**Response (200 OK):**
```json
{
  "incidentId": "uuid",
  "status": "REJECTED",
  "message": "Dispatch rejected. Escalating to next responder."
}
```

### POST /api/dispatch/{incidentId}/status

Status transitions: `ARRIVED`, `EN_ROUTE_HOSPITAL`, `RESOLVED`

**Request:**
```json
{
  "status": "EN_ROUTE_HOSPITAL",
  "hospitalId": "HOSP-001"
}
```
`hospitalId` only required for `EN_ROUTE_HOSPITAL`.

**Status transition effects:**

| Status | Effect |
|--------|--------|
| `ARRIVED` | Sets `arrivedAt` timestamp, notifies dispatch center |
| `EN_ROUTE_HOSPITAL` | Sends hospital pre-alert with medical profile and live ETA |
| `RESOLVED` | Closes WebSocket streams, cleans up Redis GPS data, resets responder to ON_DUTY |

---

## Incident Endpoints

### GET /api/incidents/{incidentId}

**Response (200 OK):**
```json
{
  "id": "uuid",
  "crashEventId": "uuid",
  "driverName": "John Doe",
  "priorityScore": 4,
  "gpsLat": 37.7749,
  "gpsLng": -122.4194,
  "status": "DISPATCHED",
  "assignedResponderId": "uuid",
  "bloodType": "O+",
  "allergies": "Penicillin",
  "bystanderAssisting": false,
  "createdAt": "2026-08-10T12:00:00Z",
  "acceptedAt": null
}
```

### GET /api/incidents/active

Returns all incidents in active statuses (DISPATCHING through EN_ROUTE_HOSPITAL).

### POST /api/incidents/{incidentId}/bystander-assist

Bystander taps "I AM ASSISTING" on the locked emergency screen.

**Response (200 OK):**
```json
{
  "incidentId": "uuid",
  "status": "BYSTANDER_NOTIFIED",
  "message": "Dispatchers notified that a bystander is assisting."
}
```

---

## WebSocket — Live GPS Streaming

### Connection
```
ws://localhost:8080/ws/incident?token=<jwt>
```
Uses STOMP over SockJS. The gateway proxies and validates the JWT.

### Responder sends GPS updates
```
STOMP SEND /app/gps/{incidentId}
Body: {
  "responderId": "uuid",
  "latitude": 37.78,
  "longitude": -122.42,
  "heading": 45.0,
  "speed": 60.0,
  "estimatedEta": "4 min"
}
```

### Victim/Dashboard subscribes
```
STOMP SUBSCRIBE /topic/gps/{incidentId}
```
Receives GPS frames every 2-3 seconds with ambulance position and ETA.

### Data path (NO Postgres)
```
Responder WebSocket → Server → Redis SET gps:latest:{incidentId}
                             → STOMP broadcast /topic/gps/{incidentId}
                             → Victim phone / Dispatcher dashboard
```

---

## Kafka Consumer

**Topic:** `crash-events` | **Group:** `dispatch-group` | **Offset Reset:** `earliest`

### CRASH_DETECTED processing
1. Create incident record (status=DISPATCHING)
2. Feign → Auth Service: fetch medical profile (circuit-broken fallback: UNKNOWN)
3. Feign → Auth Service: fetch emergency contacts (circuit-broken fallback: empty list)
4. PostGIS query: find nearest ON_DUTY responder by real distance
5. Create DispatchAttempt #1, send push notification to responder
6. Send Twilio SMS to all emergency contacts with tracking link
7. Start 30-second escalation timer

### CRASH_CANCELLED processing
1. Cancel escalation timer
2. Send stand-down push to assigned responder (if any)
3. Reset responder to ON_DUTY (via Feign to Auth)
4. Send "False Alarm" SMS to emergency contacts
5. Update incident status to CANCELLED_BY_USER, clean up GPS data

---

## Auto-Escalation (30-Second Failsafe)

When the escalation timer fires:
1. Mark current DispatchAttempt as TIMED_OUT
2. Re-run PostGIS query excluding all already-attempted responders
3. Create new DispatchAttempt (attempt #N+1)
4. Send push to next-closest responder
5. Restart 30-second timer
6. Repeat until a responder accepts or all units exhausted

---

## PostGIS Spatial Query

```sql
SELECT * FROM responder_locations
WHERE duty_status = 'ON_DUTY'
  AND latitude IS NOT NULL AND longitude IS NOT NULL
  AND responder_id NOT IN (:excludedIds)
ORDER BY ST_DistanceSphere(
    ST_MakePoint(longitude, latitude),
    ST_MakePoint(:crashLng, :crashLat)
)
LIMIT 1;
```
Responder locations are synced from Auth Service every 15 seconds.

---

## OpenFeign Circuit Breakers

| Client | Target | Fallback |
|--------|--------|----------|
| `AuthServiceClient.getMedicalProfile()` | Auth Service | `bloodType=UNKNOWN`, all fields UNKNOWN |
| `AuthServiceClient.getEmergencyContacts()` | Auth Service | Empty list (SMS skipped) |
| `AuthServiceClient.getAvailableResponders()` | Auth Service | Empty list |
| `AuthServiceClient.updateResponderStatus()` | Auth Service | Log warning, continue |

The dispatch **always proceeds** even if Auth is completely down.

---

## Environment Variables

| Variable             | Required | Default                        | Description                |
|----------------------|----------|--------------------------------|----------------------------|
| `DB_HOST`            | No       | `localhost`                    | PostgreSQL + PostGIS host  |
| `DB_PORT`            | No       | `5432`                         | PostgreSQL port            |
| `DB_NAME`            | No       | `iers_dispatch`               | Database name              |
| `DB_USERNAME`        | No       | `postgres`                    | Database user              |
| `DB_PASSWORD`        | YES      | —                              | Database password          |
| `REDIS_HOST`         | No       | `localhost`                    | Redis host                 |
| `KAFKA_BOOTSTRAP`    | No       | `localhost:9092`               | Kafka broker(s)            |
| `TWILIO_ACCOUNT_SID` | No       | —                              | Twilio Account SID         |
| `TWILIO_AUTH_TOKEN`  | No       | —                              | Twilio Auth Token          |
| `TWILIO_FROM_NUMBER` | No       | —                              | Twilio phone number        |
| `TWILIO_ENABLED`     | No       | `false`                        | Enable real SMS sending    |
| `EUREKA_URL`         | No       | `http://localhost:8761/eureka` | Eureka server URL          |

---

## Database Schema

**PostgreSQL + PostGIS database:** `iers_dispatch`

| Table               | Key Columns |
|---------------------|-------------|
| `incidents`         | id (UUID PK), crash_event_id (unique), driver_id, driver_name, driver_phone, priority_score, gps_lat/lng, status, assigned_responder_id, hospital_id, blood_type, allergies, bystander_assisting, created/accepted/arrived/en_route/resolved_at |
| `dispatch_attempts` | id (UUID PK), incident_id, responder_id, attempt_number, sent_at, responded_at, response (PENDING/ACCEPTED/REJECTED/TIMED_OUT) |
| `responder_locations` | id (UUID PK), responder_id (unique), user_name, vehicle_id, duty_status, latitude, longitude, last_updated |
