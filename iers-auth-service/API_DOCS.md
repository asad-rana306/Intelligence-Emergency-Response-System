# IERS Auth & Identity Service — API Documentation

## Overview

System of record for all user identities, medical profiles, emergency contacts, and responder duty management. Generates JWTs consumed by the API Gateway for edge validation.

**Base URL:** `http://localhost:8081` (direct) or `http://localhost:8080` (via gateway)

**Authentication:** All `/api/**` and `/internal/**` endpoints require the gateway to inject `X-User-Id` and `X-User-Role` headers. `/auth/**` endpoints are open (no auth required).

---

## Auth Endpoints

### POST /auth/register

Create a new user account. Auto-creates a `ResponderProfile` when `role = RESPONDER`.

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

**Errors:** `409` — Email already registered. `400` — Validation failure.

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
`deviceId` is optional — registers the device for push notifications.

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

### PUT /api/responders/status

**Request:**
```json
{
  "status": "ON_DUTY"
}
```
`status`: `ON_DUTY` | `OFF_DUTY` | `ON_MISSION`

**Response (200 OK):**
```json
{
  "id": "rp-uuid-...",
  "userId": "user-uuid-...",
  "dutyStatus": "ON_DUTY",
  "vehicleId": "AMB-001",
  "currentLat": 37.7749,
  "currentLng": -122.4194
}
```

### PUT /api/responders/location

**Request:**
```json
{
  "latitude": 37.7749,
  "longitude": -122.4194
}
```

**Response (200 OK):** `ResponderProfileResponse`

---

## Internal API (Feign Targets for Dispatch Service)

These endpoints are consumed by Service 4 via OpenFeign through the API Gateway.

### GET /internal/users/{userId}/medical-profile

**Response (200 OK):** `MedicalProfileResponse`

### GET /internal/users/{userId}/emergency-contacts

**Response (200 OK):** `EmergencyContactResponse[]`

### GET /internal/responders/available

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

**Response:** `204 No Content`

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
| `responder_profiles`| id (UUID PK), user_id (FK unique), duty_status, vehicle_id, current_lat, current_lng, zone_id |

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
