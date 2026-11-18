# Dispatch service — Step 4 changes

## 1. Copy these four files

Into `src/main/java/com/iers/dispatch/push/`:
- `FirebasePushConfig.java`
- `AuthPushClient.java`
- `PushService.java`
- `IncidentPushListener.java`

## 2. pom.xml — add Firebase Admin SDK

```xml
<dependency>
  <groupId>com.google.firebase</groupId>
  <artifactId>firebase-admin</artifactId>
  <version>9.3.0</version>
</dependency>
```

(You also need `spring-boot-starter-webflux` for WebClient, which step 1
already pulled in.)

## 3. application.yml — add

```yaml
fcm:
  enabled: true
  credentials-path: ${GOOGLE_APPLICATION_CREDENTIALS:}
  project-id: ${FIREBASE_PROJECT_ID:iers-a6400}

services:
  auth:
    url: http://auth-service:8081
```

## 4. docker-compose.yml — add to the dispatch service

```yaml
  dispatch-notification-service:
    # ...everything you already have...
    volumes:
      - ~/iers/secrets/firebase-service-account.json:/app/firebase-sa.json:ro
    environment:
      GOOGLE_APPLICATION_CREDENTIALS: /app/firebase-sa.json
      FIREBASE_PROJECT_ID: ${FIREBASE_PROJECT_ID}
```

## 5. .env

```
FIREBASE_PROJECT_ID=iers-a6400
```

The JSON itself is NOT in `.env` — it is the file you mount above.
