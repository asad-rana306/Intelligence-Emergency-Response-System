# IERS Dispatch & Notification Service — API Documentation

## Overview

Kafka consumer, PostGIS spatial dispatch engine, 30-second auto-escalation, STOMP WebSocket live GPS streaming via Redis, SMS to emergency contacts (SimGate) and Firebase push to phones. This service receives crash events from the IoT Service via Kafka and orchestrates the full rescue lifecycle from dispatch to hospital handoff.

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

## Kafka

### Reads

| Topic | From | Group | What Dispatch does |
|---|---|---|---|
| `crash-events` | IoT / Telemetry | `dispatch-group` | `CRASH_DETECTED` creates and dispatches an incident, `CRASH_CANCELLED` stands down |
| `duty-events` | Auth | `dispatch-group` | `DUTY_EXPIRED` / `DUTY_CHANGED` to OFF_DUTY: responder leaves the pool at once; `DUTY_EXPIRED` also sends the push "You are now OFF duty". Switch off with `dispatch.kafka.duty-listener-enabled=false` |

Messages are JSON text. A message that cannot be read is logged and dropped (it never blocks the topic).

### Writes

`incident-events` (key = incidentId): `INCIDENT_CREATED`, `INCIDENT_OFFERED`, `INCIDENT_OFFER_EXPIRED`, `INCIDENT_ACCEPTED`, `INCIDENT_STATUS`, `INCIDENT_STAND_DOWN`, `BYSTANDER_ASSISTING`, `NO_RESPONDER_AVAILABLE`, `CHAT_MESSAGE`.

### CRASH_DETECTED processing
1. Ignore a repeated message, and a second crash of a driver who already has an open incident
2. Feign → Auth: medical profile (fallback: UNKNOWN)
3. Create incident (status=DISPATCHING), announce `INCIDENT_CREATED`
4. After the commit, in the background: **alert the emergency contacts by SMS** (see below). This does NOT wait for an ambulance
5. PostGIS query: nearest ON_DUTY responder; claim the offer; push `OFFER` to the responder (after the commit, in the background)
6. Start the 30-second escalation timer

### CRASH_CANCELLED processing
1. Close the incident (`CANCELLED_BY_USER`), cancel the escalation timer
2. Push `STAND_DOWN` to the offered/assigned responder (if any), reset the responder to ON_DUTY
3. "False alarm" SMS to the contacts that got the crash SMS
4. Clean up GPS data

---

## Contact alerts (SMS)

The contacts are told **as soon as the incident exists, even if no ambulance is available**.

| When | Who gets it | Text (short) |
|---|---|---|
| Incident created | every emergency contact | EMERGENCY: *name* may have been in a car accident + map link + tracking link + driver phone |
| A responder accepts | contacts that got the first SMS (+ push `ACCEPTED` to the driver) | an ambulance accepted the call and is on the way |
| 2 minutes without an accept | contacts that got the first SMS | URGENT: no ambulance accepted yet, **call 1122 now** + map link |
| Driver cancels | contacts that got the first SMS | false alarm, *name* is safe |

How it stays reliable:
- Runs in a small background pool, after the database commit. A slow SMS gateway can never delay an ambulance.
- The sweep (every 5 s) retries any active incident whose contacts are not all told, for 30 minutes (`dispatch.notify.give-up-after`).
- Each contact that accepted the SMS is saved at once (`incidents.contacts_sent`). A retry sends only to the contacts still missing.
- One worker owns an alert through a lease (`contacts_lease_until`), so many Dispatch instances never send the same SMS twice.
- If Auth cannot give the contact list, the alert is retried. It is never skipped.
- Follow-up SMS (on the way / no ambulance / false alarm) are best effort, no retry.

Provider: `dispatch.sms.provider` = `log` (prints only, default) or `simgate` (your own SIM, `https://api.simgate.app`). A new provider = one new class implementing `SmsSender`.

---

## Push (Firebase)

All pushes are sent in the background, after the commit. Tokens come from Auth (`GET /internal/users/{id}/push-tokens`); a token that FCM calls dead is sent back to Auth (`POST /internal/push-tokens/invalidate`).

| `data.type` | To | When | Other data keys |
|---|---|---|---|
| `OFFER` | responder | an offer is made (high priority, lifetime = offer time, works when the app is closed) | `incidentId`, `lat`, `lng`, `driverName` |
| `ACCEPTED` | driver | a responder accepted | `incidentId`, `responderName` |
| `STAND_DOWN` | responder | the driver cancelled | `incidentId` |
| `OFF_DUTY` | responder | shift over or phone silent (from Auth's `duty-events`) | `reason` = `MAX_DURATION` or `HEARTBEAT_LOST`; `title`, `body`. **Data only** (no system notification) |

Android channels: `iers_offers` (urgent, with sound) and `iers_info`.
Without credentials (`GOOGLE_APPLICATION_CREDENTIALS` / `FIREBASE_PROJECT_ID` empty) pushes are only logged. A key file that cannot be read stops the start-up.

---

## Auto-Escalation (30-Second Failsafe)

When the escalation timer fires (or the sweep finds an overdue offer after a restart):
1. Mark current DispatchAttempt as TIMED_OUT
2. Re-run PostGIS query excluding all already-attempted responders
3. Create new DispatchAttempt (attempt #N+1)
4. Push `OFFER` to the next-closest responder
5. Restart 30-second timer
6. Repeat until a responder accepts. If nobody is free the sweep tries again every 15 s, for 30 minutes.

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
Responder locations are synced from Auth Service every 15 seconds. Auth lists only responders that are really available (ON_DUTY, shift not over, phone reported in the last 2 minutes). If Auth **answers**, everybody ON_DUTY here who is not in the list is set OFF_DUTY (an empty list too: the last responder going off duty must leave). If Auth **does not answer**, nothing is changed.

---

## OpenFeign Circuit Breakers

| Client | Target | Fallback |
|--------|--------|----------|
| `AuthServiceClient.getMedicalProfile()` | Auth Service | `bloodType=UNKNOWN`, all fields UNKNOWN |
| `AuthServiceClient.getEmergencyContacts()` | Auth Service | **Throws `AuthUnavailableException`** (the contact alert is retried, never skipped) |
| `AuthServiceClient.getAvailableResponders()` | Auth Service | **Throws `AuthUnavailableException`** (the sync keeps what it has) |
| `AuthServiceClient.getPushTokens()` | Auth Service | Empty list |
| `AuthServiceClient.invalidatePushTokens()` | Auth Service | Log warning, continue |
| `AuthServiceClient.updateResponderStatus()` | Auth Service | Log warning, continue |

The dispatch **always proceeds** even if Auth is completely down. Timeouts: connect 2 s, read 4 s, circuit-breaker limit 6 s (`application.yml`).

---

## Environment Variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` | No | `localhost` / `5432` / `iers_dispatch` | PostgreSQL + PostGIS |
| `DB_USERNAME` | No | `postgres` | Database user |
| `DB_PASSWORD` | YES | — | Database password |
| `DDL_AUTO` | No | `update` | Hibernate schema mode. Do not use `create` (it erases incidents on every restart) |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | No | `localhost` / `6379` / empty | Redis |
| `KAFKA_BOOTSTRAP` | No | `localhost:9092` | Kafka broker(s) |
| `EUREKA_URL` | No | `http://localhost:8761/eureka` | Eureka server URL |
| `SMS_PROVIDER` | No | `log` | `log` (only prints) or `simgate` (real SMS) |
| `SIMGATE_API_KEY` / `SIMGATE_DEVICE_ID` | If `simgate` | — | SimGate key and device id. The service refuses to start if they are empty |
| `FCM_ENABLED` | No | `true` | `false` = pushes only logged |
| `GOOGLE_APPLICATION_CREDENTIALS` | For push | — | Path of the Firebase service-account JSON inside the container |
| `FIREBASE_PROJECT_ID` | For push | — | e.g. `iers-a6400` |
| `TRACKING_BASE_URL` | No | `http://localhost:3000/track` | Page contacts open; the incident id is added |
| `EMERGENCY_NUMBER` | No | `1122` | National number printed in the "no ambulance" SMS |
| `DUTY_LISTENER_ENABLED` | No | `true` | `false` = do not read Auth's `duty-events` |

Other settings (see `application.yml`): `dispatch.notify.*` (retry interval, lease, give-up time, worker threads), `dispatch.fcm.offer-ttl`, `dispatch.kafka.*` (topic names, duty listener on/off).

---

## Database Schema

**PostgreSQL + PostGIS database:** `iers_dispatch`

| Table               | Key Columns |
|---------------------|-------------|
| `incidents`         | id (UUID PK), crash_event_id (unique), driver_id, driver_name, driver_phone, priority_score, gps_lat/lng, status, assigned_responder_id, hospital_id, blood_type, allergies, bystander_assisting, no_responder_notified, **contacts_notified_at, contacts_lease_until, contacts_sent** (SMS alert state), created/accepted/arrived/en_route/resolved_at |
| `dispatch_attempts` | id (UUID PK), incident_id, responder_id, attempt_number, sent_at, responded_at, response (PENDING/ACCEPTED/REJECTED/TIMED_OUT) |
| `responder_locations` | id (UUID PK), responder_id (unique), user_name, vehicle_id, duty_status, latitude, longitude, last_updated |
