# Auth service — Step 3 changes

## 1. Copy these two files

Into `src/main/java/com/iers/auth/duty/`:
- `DutyScheduler.java`
- `DutyController.java`

The scheduler already has `@EnableScheduling` on the class, so nothing else to add.

## 2. One edit in `ResponderService.updateDutyStatus(...)`

When a responder goes ON_DUTY we need to record the start time in Redis so the
1-hour cap can be enforced. Replace the body of `updateDutyStatus` with:

```java
@Transactional
public ResponderProfileResponse updateDutyStatus(UUID userId, UpdateDutyStatusRequest request) {
    ResponderProfile profile = responderProfileRepository.findByUserId(userId)
            .orElseThrow(() -> new ResourceNotFoundException(
                    "Responder profile not found for user: " + userId));

    profile.setDutyStatus(request.getStatus());
    profile = responderProfileRepository.save(profile);

    // NEW: track when the responder started their on-duty shift
    String key = com.iers.auth.duty.DutyScheduler.REDIS_STARTED_AT_KEY + userId;
    String hb  = com.iers.auth.duty.DutyScheduler.REDIS_HB_KEY + userId;
    if (request.getStatus() == DutyStatus.ON_DUTY) {
        redisTemplate.opsForValue().set(key,
                String.valueOf(java.time.Instant.now().getEpochSecond()),
                java.time.Duration.ofHours(2));           // room to spare
        redisTemplate.opsForValue().set(hb,
                String.valueOf(java.time.Instant.now().getEpochSecond()),
                java.time.Duration.ofMinutes(10));
    } else {
        redisTemplate.delete(key);
        redisTemplate.delete(hb);
    }

    log.info("Responder {} duty status updated to {}", userId, request.getStatus());
    return ResponderProfileResponse.from(profile);
}
```

Then inject at the top of the class (if not already there):
```java
private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
```

Lombok `@RequiredArgsConstructor` will wire it automatically.

## 3. New-user safety: force all existing responders to OFF_DUTY once

Run this SQL one time against `iers_auth` (file `sql/reset_duty.sql` in this zip):

```sql
UPDATE responder_profiles SET duty_status = 'OFF_DUTY';
```

The entity default is already OFF_DUTY, so new users will start off-duty by
themselves — but test-environment rows from before this change must be reset
or they will keep receiving offers.

## 4. Nothing to change in `application.yml`

The scheduler uses the Redis you already have.

## 5. Gateway

Nothing. `/api/responders/heartbeat` and `/api/responders/duty-info` already
match the existing route `Path=/api/responders/**` with JWT auth.
