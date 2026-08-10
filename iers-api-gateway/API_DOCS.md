# IERS API Gateway — API Documentation

## Overview

The API Gateway is the **single entry point** for all client traffic. No client ever talks directly to a downstream microservice. The gateway handles JWT validation, device API-key authentication, Redis token blocklisting, rate limiting, circuit breaking, and route proxying.

**Base URL:** `http://localhost:8080`

---

## Authentication Schemes

### 1. JWT Bearer (User Endpoints)

All user-facing endpoints require a valid JWT in the `Authorization` header.

```
Authorization: Bearer <jwt_token>
```

On successful validation, the gateway strips the `Authorization` header and injects three downstream headers:

| Header        | Value                              | Source          |
|---------------|------------------------------------|-----------------|
| `X-User-Id`   | User UUID (from JWT `sub` claim)   | JWT payload     |
| `X-User-Role` | `DRIVER`, `RESPONDER`, or `ADMIN`  | JWT `role` claim|
| `X-User-Email` | User's email address              | JWT `email` claim|
| `X-Auth-Type` | `JWT`                              | Gateway         |

**Downstream services trust these headers implicitly — they never re-parse the JWT.**

### 2. Device API Key (IoT Endpoints)

Car embedded devices authenticate with a static API key:

```
X-Device-Api-Key: <device_api_key>
X-Device-Id: <hardware_device_uuid>
```

On success, the gateway injects:

| Header        | Value           |
|---------------|-----------------|
| `X-Auth-Type` | `DEVICE`        |
| `X-Device-Id` | Device UUID     |

### 3. Open (No Auth)

The following endpoints require no authentication:

| Endpoint             | Purpose                |
|----------------------|------------------------|
| `POST /auth/login`   | User login             |
| `POST /auth/register`| User registration      |
| `POST /auth/refresh` | Refresh token rotation |
| `GET /actuator/health`| Gateway health check  |

---

## Route Map

Every request is proxied to a downstream service discovered via Eureka.

### Auth & Identity Service (`auth-service`)

| Method | Gateway Path                          | Downstream Path (unchanged)          | Auth      |
|--------|---------------------------------------|--------------------------------------|-----------|
| POST   | `/auth/register`                      | `/auth/register`                     | Open      |
| POST   | `/auth/login`                         | `/auth/login`                        | Open      |
| POST   | `/auth/refresh`                       | `/auth/refresh`                      | Open      |
| POST   | `/auth/logout`                        | `/auth/logout`                       | JWT       |
| GET    | `/api/users/me`                       | `/api/users/me`                      | JWT       |
| PUT    | `/api/users/me`                       | `/api/users/me`                      | JWT       |
| POST   | `/api/medical-profiles`               | `/api/medical-profiles`              | JWT       |
| GET    | `/api/medical-profiles/me`            | `/api/medical-profiles/me`           | JWT       |
| PUT    | `/api/medical-profiles/me`            | `/api/medical-profiles/me`           | JWT       |
| POST   | `/api/emergency-contacts`             | `/api/emergency-contacts`            | JWT       |
| GET    | `/api/emergency-contacts/me`          | `/api/emergency-contacts/me`         | JWT       |
| DELETE | `/api/emergency-contacts/{id}`        | `/api/emergency-contacts/{id}`       | JWT       |
| PUT    | `/api/responders/status`              | `/api/responders/status`             | JWT       |
| PUT    | `/api/responders/location`            | `/api/responders/location`           | JWT       |
| GET    | `/internal/users/{id}/medical-profile`| `/internal/users/{id}/medical-profile`| JWT/Internal |
| GET    | `/internal/users/{id}/emergency-contacts`| `/internal/users/{id}/emergency-contacts`| JWT/Internal |
| GET    | `/internal/responders/available`      | `/internal/responders/available`     | JWT/Internal |

### IoT & Telemetry Service (`iot-telemetry-service`)

| Method | Gateway Path                              | Auth       |
|--------|-------------------------------------------|------------|
| POST   | `/api/telemetry/crash`                    | Device Key |
| POST   | `/api/telemetry/crash/sms`                | Device Key |
| POST   | `/api/telemetry/heartbeat`                | Device Key |
| POST   | `/api/telemetry/crash/{id}/cancel`        | JWT        |
| POST   | `/api/telemetry/crash/{id}/late-cancel`   | JWT        |
| POST   | `/api/telemetry/crash/{id}/media`         | JWT        |
| POST   | `/api/devices/pair`                       | JWT        |
| GET    | `/api/devices/{id}/status`                | JWT        |

### Dispatch & Notification Service (`dispatch-notification-service`)

| Method | Gateway Path                                  | Auth  |
|--------|-----------------------------------------------|-------|
| GET    | `/api/incidents/{id}`                         | JWT   |
| GET    | `/api/incidents/active`                       | JWT   |
| POST   | `/api/dispatch/{incidentId}/accept`           | JWT   |
| POST   | `/api/dispatch/{incidentId}/reject`           | JWT   |
| POST   | `/api/dispatch/{incidentId}/status`           | JWT   |
| POST   | `/api/incidents/{id}/bystander-assist`        | JWT   |
| WS     | `/ws/incident/{incidentId}?token=<jwt>`       | JWT (query param) |

---

## Rate Limiting

The gateway uses a **Redis-backed Token Bucket** algorithm.

### Default Limits (all routes)

| Parameter          | Value                     |
|--------------------|---------------------------|
| Replenish Rate     | 20 requests/second        |
| Burst Capacity     | 40 requests               |
| Tokens per Request | 1                         |
| Key Resolution     | Client IP address         |

### IoT Telemetry Override (higher throughput)

| Parameter          | Value                     |
|--------------------|---------------------------|
| Replenish Rate     | 50 requests/second        |
| Burst Capacity     | 100 requests              |

### Rate Limit Response

When the limit is exceeded, the gateway returns:

```
HTTP/1.1 429 Too Many Requests
X-RateLimit-Remaining: 0
X-RateLimit-Retry-After-Seconds: 1
```

---

## Circuit Breakers

Each downstream service has a dedicated Resilience4j circuit breaker.

| Circuit Breaker        | Protects              | Fallback Endpoint    |
|------------------------|-----------------------|----------------------|
| `authCircuitBreaker`   | Auth & Identity       | `/fallback/auth`     |
| `iotCircuitBreaker`    | IoT & Telemetry       | `/fallback/iot`      |
| `dispatchCircuitBreaker`| Dispatch & Notification| `/fallback/dispatch`|

### Configuration (all instances)

| Parameter                            | Value  |
|--------------------------------------|--------|
| Sliding Window Size                  | 10     |
| Failure Rate Threshold               | 50%    |
| Wait Duration in Open State          | 30s    |
| Permitted Calls in Half-Open State   | 3      |
| Slow Call Duration Threshold          | 5s     |
| Slow Call Rate Threshold             | 80%    |
| Timeout Duration (TimeLimiter)       | 10s    |

### Fallback Response (all services)

```json
{
  "status": 503,
  "error": "Service Unavailable",
  "message": "Auth & Identity Service is temporarily unavailable",
  "path": "/api/users/me",
  "timestamp": "2026-08-10T12:00:00Z"
}
```

---

## WebSocket Proxying

WebSocket connections for live GPS streaming are proxied to the Dispatch Service.

**Connection URL:**
```
ws://localhost:8080/ws/incident/{incidentId}?token=<jwt>
```

The JWT is passed as a query parameter (since WebSocket upgrade requests don't support custom headers in browser clients). The `JwtAuthenticationFilter` extracts and validates it from the `token` query param for any path starting with `/ws/`.

---

## Error Response Format

All gateway errors follow this JSON structure:

```json
{
  "status": 401,
  "error": "Unauthorized",
  "message": "Invalid or expired token",
  "path": "/api/users/me",
  "timestamp": "2026-08-10T12:00:00Z"
}
```

### Common Error Codes

| Status | Meaning                      | Cause                                        |
|--------|------------------------------|----------------------------------------------|
| 401    | Unauthorized                 | Missing/invalid/expired/revoked JWT or API key|
| 429    | Too Many Requests            | Rate limit exceeded                          |
| 503    | Service Unavailable          | Downstream service down / circuit breaker open|
| 504    | Gateway Timeout              | Downstream service did not respond in 10s    |

---

## Environment Variables

| Variable          | Required | Default                         | Description                       |
|-------------------|----------|---------------------------------|-----------------------------------|
| `JWT_SECRET`      | YES      | —                               | HMAC signing key (min 32 chars)   |
| `DEVICE_API_KEY`  | YES      | —                               | Static key for car device auth    |
| `REDIS_HOST`      | No       | `localhost`                     | Redis hostname                    |
| `REDIS_PORT`      | No       | `6379`                          | Redis port                        |
| `REDIS_PASSWORD`  | No       | (blank)                         | Redis password                    |
| `EUREKA_URL`      | No       | `http://localhost:8761/eureka`  | Eureka server URL                 |

---

## Health Check

```
GET /actuator/health
```

Response:
```json
{
  "status": "UP",
  "components": {
    "discoveryComposite": { "status": "UP" },
    "redis": { "status": "UP" }
  }
}
```
