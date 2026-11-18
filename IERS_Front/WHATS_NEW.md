# IERS app — update for the new backend (Auth, Gateway, Dispatch)

Replace the whole project, then:

```
flutter pub get
flutter analyze
flutter test
flutter run
```

**5 packages are new** (`firebase_core`, `firebase_messaging`, `flutter_foreground_task`,
`flutter_local_notifications`, `flutter_contacts`). They were already imported in the code but missing
from `pubspec.yaml`, so the app did not build. I could not reach pub.dev from my environment, so I could
not check the versions or compile. If `pub get` or the build shows an error, send me the first one.

## What changed

| Need | Now |
|---|---|
| **Build** | Missing packages added. `lib/firebase_options.dart` was missing: created from `google-services.json` (client `com.example.untitled`). Android only; Chrome/iOS run without push. `ApiClient.delete` can take query values (`DELETE /api/push/tokens?token=`). |
| **Responder shift (1 hour)** | `DutyService` now follows the server: `GET /api/responders/me` and the heartbeat answer (status, seconds left). The old `/duty-info` endpoint never existed. A stored `ON_DUTY` no longer starts a new shift by itself when the app opens: the server decides. |
| **Auto OFF duty** | The server puts the responder OFF after 1 hour, or after 2 minutes without heartbeat. The app then: plays a **5-second sound**, shows a notification with a **"Go on duty again"** button, and a dialog on the screen. "Renew" shows in the last 10 minutes. |
| **Permanent notification** | While on duty a notification stays at the top: "On duty — 42 min left". It is a foreground service, so the **heartbeat and location keep going with the app closed**. After the auto OFF it shows a "Go on duty" button. |
| **Responder alert, app closed** | The notification channels `iers_offers` and `iers_info` (the names Dispatch uses) are now created on the phone, with sound. The device token is sent to Auth after login (and removed on logout). |
| **Crash sensors, app closed** | While the driver is "Protected", a foreground service keeps watching. If the app is swiped away, the sensors continue inside the service. A crash is sent to the server and a siren notification with **"I'M OK — cancel"** opens (it also wakes the screen). |
| **Airbag** | Every crash carries `sensorData.airbagEstimate` = `NONE` / `POSSIBLE` / `LIKELY` (software guess from the hit and the speed drop, never "confirmed"), plus `speedBefore` and `speedAfter`. The speed before the hit is now the highest speed of the last 12 s (before, the speed after was used). |
| **OTP** | Uses `resendAfterSeconds` and `expiresInSeconds` from the server, the 429 `Retry-After`, and says "Wrong code. 2 tries left" / "too old" / "too many tries". Digits only. |
| **Emergency contacts** | Phonebook or manual, with the "Is this number correct?" step (already there). Added the `READ_CONTACTS` permission (the picker could not work without it) and `3001234567` is now read as `+923001234567`. |
| **Refresh token** | The server changes the refresh token on every refresh and the app now has two isolates (screen + service). Before refreshing, the app first looks at the stored login, so they do not break each other. |

## Android files changed

- `AndroidManifest.xml`: `READ_CONTACTS`, `USE_FULL_SCREEN_INTENT`, default push channel `iers_info`, contact-picker query.
- `android/app/build.gradle.kts`: core library desugaring (needed by `flutter_local_notifications`).
- `android/app/src/main/res/raw/`: `iers_off_duty.wav` (5 s), `iers_offer.wav`, `iers_siren.wav` (channel sounds).

## Needs a real phone to test

Foreground service, notification buttons, push when the app is closed, and crash sensors do not run in Chrome
or an emulator without sensors. On the phone, allow **notifications**, **location "all the time"**, and
**no battery optimisation** (the app asks). Some brands (Xiaomi, Oppo, Huawei…) also need "Autostart" on.

## Backend settings that must be on

- Auth: `DUTY_EVENTS_ENABLED=true` (so Dispatch can push "you are OFF duty").
- Dispatch: `GOOGLE_APPLICATION_CREDENTIALS` and `FIREBASE_PROJECT_ID=iers-a6400`.
- Dispatch zip was updated too: the OFF_DUTY push is now data only (the app shows it, so there is no double notification).

## Not changed

- iOS (no Firebase iOS file, no background service).
- Chat messages are not pushed yet.
- The Telemetry service must read `sensorData.airbagEstimate` (next service).
