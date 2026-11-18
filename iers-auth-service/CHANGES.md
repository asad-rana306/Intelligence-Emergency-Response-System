# Auth Service — what changed (SMS code, 1-hour duty, push tokens)

This version replaces the three loose folders from the earlier step patches (`duty`, `otp`, `push`).
Everything now sits in the normal layers of the service. **Replace the whole project** and delete those three folders. If you keep them next to the new code, Spring will find two controllers for the same URL and will not start.

## Where things are

| Package | New / changed | What it does |
|---|---|---|
| `config` | `OtpProperties`, `SmsProperties`, `DutyProperties`, `PushProperties`, `PropertiesConfig`, `SchedulingConfig`, `TimeConfig` | All numbers (code length, attempts, shift length ...) come from `application.yml`. No magic numbers in code. |
| `util` | `PhoneNormalizer`, `AfterCommit` | One phone format (`+923001234567`). Events are sent only after the database commit. |
| `sms` | `SmsSender`, `SimGateSmsSender`, `LoggingSmsSender`, `SmsResult` | Sending an SMS. A new provider (Twilio ...) = one new class. |
| `store` | `OtpStore`, `RedisOtpStore` | Where the code lives (Redis, expires by itself, works with many instances). |
| `service` | `OtpService`, `PushTokenService`, `DutyExpiryService`; changed: `AuthService`, `ResponderService`, `EmergencyContactService` | Business rules. |
| `scheduler` | `DutyExpiryScheduler` | Every 30 s puts responders OFF duty when the shift is over or the phone is silent. |
| `event` | `DutyEvent`, `DutyEventPublisher`, `KafkaDutyEventPublisher`, `NoopDutyEventPublisher` | Duty events for Dispatch (off by default). |
| `entity` / `repository` | `PushToken`; `ResponderProfile` (+3 time columns) | New table `push_tokens`; 3 new columns in `responder_profiles` (created by `ddl-auto: update`). |
| `controller` / `dto` | `OtpController`, `PushTokenController`; changed: `ResponderController`, `InternalApiController` | New endpoints, see `API_DOCS.md`. |
| `exception` | 3 new exceptions; `GlobalExceptionHandler` | 403 not verified, 429 + `Retry-After`, 502 SMS failed. A missing header/param or bad JSON is now 400 (was 500). |

## New behavior

1. **Register needs a verified phone.** `POST /auth/otp/send` then `POST /auth/otp/verify`, then `POST /auth/register`. One verified phone = one registration.
2. **Phones are stored as `+92...`** (users and emergency contacts), so the accident SMS and the SMS gateway get the same text. A wrong contact number is refused with 400 when it is added.
3. **Responder shift = 1 hour.** ON again starts a new shift. The phone sends `POST /api/responders/heartbeat` about every 30 s; 2 minutes of silence = OFF duty.
4. **Old / stale ON_DUTY rows are cleaned.** Rows without time stamps (all your test users from before) are put OFF duty by the first sweep, about 20–50 s after this version starts. Dispatch only sees responders that are ON_DUTY, have a position, an unfinished shift and a fresh heartbeat. This fixes "another user is on duty and gets the alert".
5. **Dispatch can not switch a responder ON.** `PUT /internal/responders/{id}/status?status=ON_DUTY` works only when the responder is ON_MISSION (mission finished). An OFF responder stays OFF.
6. **Push tokens** are stored per phone (newest 5 per user). Dispatch reads them with `GET /internal/users/{id}/push-tokens`.
7. **Duty events** (optional): `DUTY_EXPIRED` and `DUTY_CHANGED` on Kafka topic `duty-events`, so Dispatch can send "You are now OFF duty".

## Many instances (scaling)

- OTP state is in Redis, not in memory.
- The duty sweep takes a short Redis lock, and every row is changed with a conditional `UPDATE`. Two instances can never send the same event twice. If Redis is down the sweep still runs safely.
- Heartbeat / location / status changes write only the columns they change (`@DynamicUpdate`), so they do not overwrite each other.

## Settings to add where the service runs

```yaml
auth-service:
  environment:
    SMS_PROVIDER: simgate              # "log" only prints the code (development)
    SIMGATE_API_KEY: ${SIMGATE_API_KEY}
    SIMGATE_DEVICE_ID: ${SIMGATE_DEVICE_ID}
    KAFKA_BOOTSTRAP: kafka:9092
    DUTY_EVENTS_ENABLED: "true"        # after Dispatch can read the topic
  depends_on: [kafka]                  # only if events are enabled
```

If `SMS_PROVIDER=simgate` and the key or device id is empty, the service refuses to start (on purpose, so you notice).

## Run the tests

```
mvn clean test
```

I could not run Maven in my environment (no access to Maven Central), so **this code is not compiled yet**. I checked every file for syntax and read it carefully. If `mvn clean test` shows an error, send me the first error and I will fix it.

## Not changed on purpose

- `PUT /api/users/me` can still change the phone without a code, and does not reformat it.
- `ON_MISSION` responders are not expired by the sweep (Dispatch owns that state).
- A duty event is best effort: if Kafka is down the event is lost. The app also knows its own shift end and shows its own notification.
