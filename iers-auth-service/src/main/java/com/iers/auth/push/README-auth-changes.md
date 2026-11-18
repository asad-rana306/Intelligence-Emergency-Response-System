# Auth service — Step 4 changes

Auth only stores and serves tokens. The actual FCM send is done by Dispatch.

## Copy these three files

Into `src/main/java/com/iers/auth/push/`:
- `PushTokenEntity.java`
- `PushTokenRepository.java`
- `PushTokenController.java`

JPA will auto-create the `push_tokens` table (your `ddl-auto: update` already does this).

## Gateway

Nothing. `/api/push/tokens` is matched by the existing `/api/**` behavior and
`/internal/**` is already blocked from outside callers.

## No new env vars for Auth.
