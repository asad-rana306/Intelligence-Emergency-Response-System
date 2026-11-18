# Dispatch Service — what changed (SMS to contacts, push, duty events)

This version replaces the loose `push` folder from the earlier step patches. **Replace the whole project.**
The old files `push/AuthPushClient`, `push/IncidentPushListener`, `push/FirebasePushConfig`, `push/PushService` and `push/README-dispatch-changes.md` are gone. If you keep them next to the new code, the service will not start.

## What this fixes

| Problem | Now |
|---|---|
| Contacts were told only inside the dispatch transaction, one SMS at a time, and never if Auth was down | Contacts are alerted in the background after the commit, with retries, **even when no ambulance is available** |
| Nobody told the contacts to call the emergency number when no ambulance accepted | After 2 minutes: "No ambulance accepted yet. Please call 1122 now." |
| Responder push was only a log line; the Kafka push listener could not read its messages | Real Firebase push (OFFER / ACCEPTED / STAND_DOWN / OFF_DUTY), sent in the background |
| `ddl-auto: create` erased all incidents on every restart | `ddl-auto: ${DDL_AUTO:update}` |
| The last responder going OFF duty stayed ON_DUTY here (empty list was ignored) | Empty answer from Auth = everybody OFF. Auth down = nothing changes |
| Auth down looked like "no contacts" / "no responders" | Fallback now throws `AuthUnavailableException` for those two calls |
| Feign calls could hang 60 s | connect 2 s, read 4 s, circuit-breaker limit 6 s |
| Twilio code and placeholders | Removed. SMS goes through SimGate (or the log provider) |

## Where things are

| Package | New / changed | What it does |
|---|---|---|
| `config` | `SmsProperties`, `PushProperties`, `NotifyProperties`, `PropertiesConfig` | All numbers and switches come from `application.yml` |
| `util` | `AfterCommit` | SMS and push start only after the database commit |
| `sms` | `SmsSender`, `SimGateSmsSender`, `LoggingSmsSender`, `SmsResult` | A new provider = one new class |
| `push` | `PushMessage`, `PushSender`, `FcmPushSender`, `LoggingPushSender`, `PushConfig`, `PushService` | Fan-out to all phones of a user; dead tokens are reported to Auth |
| `service` | `NotificationService` (rewritten), `ContactAlertService`, `BackgroundRunner`; changed: `DispatchOrchestrator`, `DispatchSweepService`, `SpatialQueryService` | See below |
| `kafka` | `DutyEventConsumer` | Reads Auth's `duty-events` |
| `feign` | `AuthServiceClient` (+ push tokens, invalidate), `AuthServiceFallbackFactory` | |
| `entity` / `repository` | `Incident` +3 columns; `IncidentRepository` +5 queries; `ResponderLocationRepository` +1 | Columns are added by `ddl-auto: update` |
| `exception` | `AuthUnavailableException` | |

## How it works

1. **Crash arrives** → incident saved → after the commit the contact alert starts in a background pool, and the nearest responder gets the push. A slow SMS gateway cannot delay the ambulance.
2. **Contact alert** is owned by one worker through a lease column, saves each contact right after its SMS is accepted, and is retried by the sweep (every 5 s) until all contacts are told or the incident is 30 minutes old.
3. **No ambulance accepts for 2 minutes** → contacts get the "call 1122" SMS (once).
4. **Responder accepts** → driver gets push `ACCEPTED`, contacts get "on the way".
5. **Driver cancels** → responder gets `STAND_DOWN`; contacts who got the first SMS get "false alarm".
6. **Shift over / phone silent** (Auth `duty-events`) → responder leaves the pool at once and gets push `OFF_DUTY` (`reason` = `MAX_DURATION` or `HEARTBEAT_LOST`). This one is **data only** (no system notification, high priority): the app shows its own alert with a 5-second sound and a "Go on duty again" button. The text is in `data.title` / `data.body`.

## Many instances (scaling)

- The contact alert is claimed with a conditional `UPDATE` (lease), so two instances never send the same SMS.
- The sweep, the offer timers and the status changes were already conditional updates.
- SMS/push run in a bounded pool (`dispatch.notify.executor`). If the queue is full the task is refused and logged; the sweep retries contact alerts.

## Settings to add where the service runs

```yaml
dispatch-notification-service:
  environment:
    DDL_AUTO: update
    SMS_PROVIDER: simgate              # "log" only prints (development)
    SIMGATE_API_KEY: ${SIMGATE_API_KEY}
    SIMGATE_DEVICE_ID: ${SIMGATE_DEVICE_ID}
    GOOGLE_APPLICATION_CREDENTIALS: /app/firebase-sa.json
    FIREBASE_PROJECT_ID: iers-a6400
    TRACKING_BASE_URL: https://your-tracking-page/track
    EMERGENCY_NUMBER: "1122"
  volumes:
    - ./secrets/firebase-sa.json:/app/firebase-sa.json:ro
```

- `SMS_PROVIDER=simgate` with an empty key or device id: the service refuses to start (on purpose).
- A Firebase key path that cannot be read: the service refuses to start (on purpose). Empty path = pushes only logged.
- In **Auth** set `DUTY_EVENTS_ENABLED: "true"` so Dispatch gets `duty-events`. Without it the OFF-duty push is not sent (the 15-second sync still removes the responder).

## Run the tests

```
mvn clean test
```

I could not run Maven in my environment (no access to Maven Central), so **this code is not compiled yet**. I checked every file for syntax and read it carefully. If `mvn clean test` shows an error, send me the first error and I will fix it.

## Not changed on purpose

- Follow-up SMS (on the way / no ambulance / false alarm) are best effort. Only the first crash SMS is retried.
- Chat messages (`CHAT_MESSAGE`) are not pushed yet.
- Hospital pre-alert is still a log line (there is no hospital system to call).
- A responder who is `ON_MISSION` is not touched by the duty sweep or by `duty-events`.
- A token FCM rejects as `INVALID_ARGUMENT` is kept (it can also mean a bad message). Auth keeps only the newest 5 tokens per user, so old ones age out.
