# IERS Auth & Identity Service — API Documentation

## Overview

System of record for all user identities, medical profiles, emergency contacts, and responder duty management. Generates JWTs consumed by the API Gateway for edge validation.

**Base URL:** `http://localhost:8081` (direct) or `http://localhost:8080` (via gateway)

**Authentication:** All `/api/**` and `/internal/**` endpoints require the gateway to inject `X-User-Id` and `X-User-Role` headers. `/auth/**` endpoints are open (no auth required), including the SMS code endpoints `/auth/otp/**`.

---

## Auth Endpoints

### POST /auth/register

Create a new user account. Auto-creates a `ResponderProfile` when `role = RESPONDER`.

**The phone number must be verified first** (`POST /auth/otp/send`, then `POST /auth/otp/verify`) — unless the server runs with `OTP_REQUIRED=false`. The phone is saved in international form (`0300 1234567` becomes `+923001234567`). One verification can be used for one registration only.

**Request:**
```json
{
  "email": "driver@example.com",
  "password": "securePassword123",
  "fullName": "John Doe",
  "phone": "+14155551234",
  "role": "DRIVER",
  "vehicleId": null
}
```
`role`: `DRIVER` | `RESPONDER` | `ADMIN`
`vehicleId`: Required only when `role = RESPONDER`

**Response (201 Created):**
```json
{
  "accessToken": "eyJhbGciOi...",
  "refreshToken": "eyJhbGciOi...",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "userId": "a1b2c3d4-...",
  "email": "driver@example.com",
  "role": "DRIVER"
}
```

**Errors:** `409` — Email already registered. `400` — Validation failure or invalid phone number. `403` — Phone number not verified.

---

### POST /auth/login

**Request:**
```json
{
  "email": "driver@example.com",
  "password": "securePassword123",
  "deviceId": "fcm-token-abc123"
}
```
`deviceId` is optional — it is the phone's Firebase token. When sent, it is saved as a push token (same as `POST /api/push/tokens`).

**Response (200 OK):** Same shape as register response.

**Errors:** `401` — Invalid email or password.

---

### POST /auth/refresh

Rotate tokens. The old refresh token is invalidated (one-time use).

**Request:**
```json
{
  "refreshToken": "eyJhbGciOi..."
}
```

**Response (200 OK):** Same shape as register response (new token pair).

**Errors:** `401` — Token expired, invalid, wrong type, or already rotated.

---

### POST /auth/logout

Blocklists the access token in Redis and invalidates the refresh token.

**Headers:** `X-User-Id: <uuid>`, `Authorization: Bearer <access_token>`

**Response:** `204 No Content`

---

## SMS Code (OTP) Endpoints

Open endpoints (no login). The gateway rate-limits them by IP, and the service limits them per phone number.

### POST /auth/otp/send

Sends a 6-digit code by SMS.

**Request:**
```json
{ "phone": "0300 1234567" }
```
Any common phone format is accepted (`+92...`, `0092...`, `03xx...`). Numbers starting with `0` use `DEFAULT_COUNTRY_CODE` (default `92`).

**Response (200 OK):**
```json
{ "status": "SENT", "expiresInSeconds": 300, "resendAfterSeconds": 30 }
```
`status` is `QUEUED` when the SMS gateway phone is offline and will send the message when it reconnects.

**Errors:** `400` — invalid phone number. `429` — too soon (cooldown 30 s) or too many codes (3 per hour per phone); the `Retry-After` header says how many seconds to wait. `502` — the SMS gateway did not accept the message.

### POST /auth/otp/verify

**Request:**
```json
{ "phone": "0300 1234567", "code": "123456" }
```

**Response (always 200 OK):**
```json
{ "ok": true }
```
or
```json
{ "ok": false, "reason": "WRONG_CODE", "remainingAttempts": 3 }
```
`reason`: `WRONG_CODE` (tries left in `remainingAttempts`), `EXPIRED` (no code, or older than 5 minutes), `TOO_MANY_ATTEMPTS` (5 wrong guesses; ask for a new code).

After `ok: true` the phone stays verified for 10 minutes, so the app can call `/auth/register`.

---

## User Endpoints

### GET /api/users/me

**Headers:** `X-User-Id: <uuid>`

**Response (200 OK):**
```json
{
  "id": "a1b2c3d4-...",
  "email": "driver@example.com",
  "fullName": "John Doe",
  "phone": "+14155551234",
  "role": "DRIVER",
  "createdAt": "2026-08-10T12:00:00Z"
}
```

### PUT /api/users/me

**Request:** (all fields optional — partial update)
```json
{
  "fullName": "John Updated",
  "phone": "+14155559999",
  "deviceId": "new-fcm-token"
}
```

**Response (200 OK):** Updated `UserResponse`.

---

## Medical Profile Endpoints

### POST /api/medical-profiles

**Headers:** `X-User-Id: <uuid>`

**Request:**
```json
{
  "bloodType": "O+",
  "allergies": "Penicillin, Shellfish",
  "medications": "Metformin 500mg",
  "chronicConditions": "Type 2 Diabetes",
  "emergencyNotes": "Patient carries insulin pen"
}
```

**Response (201 Created):**
```json
{
  "id": "mp-uuid-...",
  "userId": "user-uuid-...",
  "bloodType": "O+",
  "allergies": "Penicillin, Shellfish",
  "medications": "Metformin 500mg",
  "chronicConditions": "Type 2 Diabetes",
  "emergencyNotes": "Patient carries insulin pen"
}
```

**Errors:** `409` — Profile already exists.

### GET /api/medical-profiles/me

**Response (200 OK):** `MedicalProfileResponse`

**Errors:** `404` — No profile found.

### PUT /api/medical-profiles/me

**Request:** Partial update (same structure, all fields optional).

**Response (200 OK):** Updated `MedicalProfileResponse`.

---

## Emergency Contact Endpoints

### POST /api/emergency-contacts

The phone is saved in international form (`+92...`). An invalid number is refused with `400` here, so the accident SMS never goes to a wrong number. The app should show the number to the driver and ask them to confirm it before calling this endpoint.

**Request:**
```json
{
  "contactName": "Jane Doe",
  "phone": "+14155559876",
  "relationship": "Spouse"
}
```

**Response (201 Created):**
```json
{
  "id": "ec-uuid-...",
  "contactName": "Jane Doe",
  "phone": "+14155559876",
  "relationship": "Spouse"
}
```

### GET /api/emergency-contacts/me

**Response (200 OK):** `EmergencyContactResponse[]`

### DELETE /api/emergency-contacts/{contactId}

**Response:** `204 No Content`

**Errors:** `404` — Contact not found. `401` — Not the owner of this contact.

---

## Responder Endpoints

### Duty rules

- A shift lasts **1 hour** (`DUTY_MAX_HOURS`). When it ends the server puts the responder `OFF_DUTY`.
- Pressing ON again (or "Go on duty again" in the notification) starts a **new** 1-hour shift.
- While `ON_DUTY` or `ON_MISSION` the app must send a heartbeat about every **30 seconds**. If nothing arrives for **2 minutes**, the server puts the responder `OFF_DUTY`.
- The sweep runs every 30 seconds, so the change can come up to about 30 seconds late.
- `ON_MISSION` is set by Dispatch and is not expired by the sweep.
- Only responders that are `ON_DUTY`, have a GPS position, an unfinished shift and a recent heartbeat are offered incidents.

All responder endpoints below return the same `ResponderProfileResponse`:
```json
{
  "id": "rp-uuid-...",
  "userId": "user-uuid-...",
  "dutyStatus": "ON_DUTY",
  "vehicleId": "AMB-001",
  "currentLat": 37.7749,
  "currentLng": -122.4194,
  "dutyStartedAt": "2026-01-01T10:00:00Z",
  "dutyExpiresAt": "2026-01-01T11:00:00Z",
  "secondsRemaining": 3540,
  "serverTime": "2026-01-01T10:01:00Z"
}
```
`dutyStartedAt`, `dutyExpiresAt` and `secondsRemaining` are `null` when `OFF_DUTY`. `serverTime` lets the app correct a wrong phone clock.

### GET /api/responders/me

My status, shift times and seconds left.

### PUT /api/responders/status

**Request:**
```json
{
  "status": "ON_DUTY"
}
```
`status`: `ON_DUTY` | `OFF_DUTY` | `ON_MISSION`

### PUT /api/responders/location

**Request:**
```json
{
  "latitude": 37.7749,
  "longitude": -122.4194
}
```
Also counts as a heartbeat while the responder is on duty.

### POST /api/responders/heartbeat

No body. Call about every 30 seconds from the background service. The answer carries the current status, so the app learns at once when the server put it `OFF_DUTY` (and can show the notification and play the sound).

**Errors:** `404` — the user has no responder profile.

---

## Push Token Endpoints

The phone tells the server its Firebase (FCM) token. The server only stores it; the Dispatch service sends the pushes.

### POST /api/push/tokens

**Request:**
```json
{ "token": "fcm-token-abc123", "platform": "android" }
```
`platform` is optional (`android` | `ios` | `web`). **Response:** `204 No Content`.

The same token sent by another user (a shared phone) moves to that user. Each user keeps the newest 5 tokens.

### DELETE /api/push/tokens?token=fcm-token-abc123

Call on logout. **Response:** `204 No Content` (also when the token is unknown).

---

## Internal API (Feign Targets for Dispatch Service)

These endpoints are consumed by Service 4 via OpenFeign through the API Gateway.

### GET /internal/users/{userId}/medical-profile

**Response (200 OK):** `MedicalProfileResponse`

### GET /internal/users/{userId}/emergency-contacts

**Response (200 OK):** `EmergencyContactResponse[]` — phones are in international form (`+92...`), ready for the SMS gateway.

### GET /internal/responders/available

Only `ON_DUTY` responders with a GPS position, an unfinished shift and a heartbeat from the last 2 minutes.

**Response (200 OK):**
```json
[
  {
    "responderId": "rp-uuid-...",
    "userId": "user-uuid-...",
    "fullName": "Jane Responder",
    "phone": "+14155551111",
    "vehicleId": "AMB-001",
    "latitude": 37.7749,
    "longitude": -122.4194
  }
]
```

### PUT /internal/responders/{userId}/status?status=ON_MISSION

`ON_DUTY` is applied only when the responder is `ON_MISSION` now (end of a mission); a responder who is `OFF_DUTY` stays `OFF_DUTY`. Returning from a mission starts a new 1-hour shift.

**Response:** `204 No Content`

### GET /internal/users/{userId}/push-tokens

**Response (200 OK):** `["fcm-token-newest", "fcm-token-older"]`

### POST /internal/push-tokens/invalidate

Dispatch reports tokens that Firebase rejected.

**Request:** `{ "tokens": ["dead-token-1", "dead-token-2"] }` (1 to 500). **Response:** `204 No Content`

---

## Error Codes

| Code | Meaning |
|------|---------|
| 400  | Validation failure, invalid phone number, missing header/parameter, body is not valid JSON |
| 401  | Wrong credentials / bad token |
| 403  | Phone number not verified (register) |
| 404  | Resource not found |
| 409  | Duplicate (email already registered) |
| 429  | Too many SMS code requests (see `Retry-After`) |
| 502  | SMS gateway did not accept the message |

---

## JWT Token Structure

### Access Token Claims
```json
{
  "jti": "random-uuid",
  "sub": "user-uuid",
  "email": "user@example.com",
  "role": "DRIVER",
  "name": "John Doe",
  "type": "ACCESS",
  "iat": 1723300800,
  "exp": 1723301700
}
```

### Refresh Token Claims
```json
{
  "jti": "random-uuid",
  "sub": "user-uuid",
  "type": "REFRESH",
  "iat": 1723300800,
  "exp": 1723905600
}
```

---

## Database Schema

**PostgreSQL database:** `iers_auth`

| Table               | Key Columns                                                    |
|---------------------|----------------------------------------------------------------|
| `users`             | id (UUID PK), email (unique), password_hash, full_name, phone, role, device_id, refresh_token_hash |
| `medical_profiles`  | id (UUID PK), user_id (FK unique), blood_type, allergies, medications, chronic_conditions, emergency_notes |
| `emergency_contacts`| id (UUID PK), user_id (FK), contact_name, phone, relationship  |
| `responder_profiles`| id (UUID PK), user_id (FK unique), duty_status, vehicle_id, current_lat, current_lng, zone_id, duty_started_at, duty_expires_at, last_heartbeat_at |
| `push_tokens`       | id (UUID PK), user_id, token (unique, max 512), platform, created_at, updated_at |

OTP state is not in the database. It lives in Redis (`otp:*` keys, all with an expiry) and the duty sweep lock is `lock:duty-sweep`.

---

## Environment Variables

| Variable       | Required | Default                        | Description                     |
|----------------|----------|--------------------------------|---------------------------------|
| `JWT_SECRET`   | YES      | —                              | HMAC key (must match Gateway)   |
| `DB_HOST`      | No       | `localhost`                    | PostgreSQL host                 |
| `DB_PORT`      | No       | `5432`                         | PostgreSQL port                 |
| `DB_NAME`      | No       | `iers_auth`                   | Database name                   |
| `DB_USERNAME`  | No       | `postgres`                    | Database user                   |
| `DB_PASSWORD`  | YES      | —                              | Database password               |
| `REDIS_HOST`   | No       | `localhost`                    | Redis host                      |
| `REDIS_PORT`   | No       | `6379`                         | Redis port                      |
| `REDIS_PASSWORD`| No      | (blank)                        | Redis password                  |
| `EUREKA_URL`   | No       | `http://localhost:8761/eureka` | Eureka server URL               |
| `SMS_PROVIDER` | No       | `log`                          | `log` = print the code in the log (development only), `simgate` = real SMS |
| `SIMGATE_API_KEY` | YES when `simgate` | —                  | SimGate API key                 |
| `SIMGATE_DEVICE_ID` | YES when `simgate` | —                | SimGate device id               |
| `OTP_REQUIRED` | No       | `true`                         | `false` lets users register without a code (tests only) |
| `DEFAULT_COUNTRY_CODE` | No | `92`                         | Used for numbers typed as `03xx...` |
| `DUTY_MAX_HOURS` | No     | `1`                            | Length of one responder shift   |
| `DUTY_EVENTS_ENABLED` | No | `false`                       | `true` publishes duty events to Kafka |
| `KAFKA_BOOTSTRAP` | No    | `localhost:9092`               | Kafka address (used for duty events) |

Other settings (code length, attempts, cooldown, heartbeat timeout, sweep interval, tokens per user) are in `application.yml` under `auth.otp`, `auth.duty` and `auth.push`.

---

## Duty Events (Kafka, optional)

With `DUTY_EVENTS_ENABLED=true`, Auth publishes JSON text messages on topic `duty-events` (key = user id) so the Dispatch service can send the "You are now OFF duty" push:

```json
{ "type": "DUTY_EXPIRED", "userId": "user-uuid", "status": "OFF_DUTY", "reason": "MAX_DURATION", "occurredAt": "2026-01-01T11:00:00Z" }
```
`type`: `DUTY_EXPIRED` (the server put the responder OFF duty) or `DUTY_CHANGED` (the responder or Dispatch changed the status). `reason` (only for `DUTY_EXPIRED`): `MAX_DURATION` (shift over) or `HEARTBEAT_LOST` (phone went silent).
