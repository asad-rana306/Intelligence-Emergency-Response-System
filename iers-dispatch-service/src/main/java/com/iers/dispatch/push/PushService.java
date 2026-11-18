package com.iers.dispatch.push;

import com.google.firebase.messaging.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sends a push notification to one user (all their devices).
 *
 * "data-only" message — the app decides how to show it. For offers we add
 * a full-screen Android notification so the responder's phone wakes up and
 * rings even when the app is closed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushService {

    private final AuthPushClient authClient;

    @Autowired(required = false)   // null when Firebase is not configured
    private FirebaseMessaging fcm;

    /** Generic info push ("you are now OFF duty", "driver arrived"). */
    public void sendInfo(UUID userId, String title, String body, Map<String, String> data) {
        fanOut(userId, title, body, data, false);
    }

    /** High-priority "ACCEPT / REJECT" push to a responder for an incoming offer. */
    public void sendOffer(UUID responderUserId, String incidentId,
                          double lat, double lng, String driverName) {
        Map<String, String> d = new HashMap<>();
        d.put("type", "OFFER");
        d.put("incidentId", incidentId);
        d.put("lat", String.valueOf(lat));
        d.put("lng", String.valueOf(lng));
        d.put("driverName", driverName == null ? "" : driverName);
        fanOut(responderUserId,
                "EMERGENCY — accept now",
                "Crash reported. Tap to open.",
                d, true);
    }

    private void fanOut(UUID userId, String title, String body,
                        Map<String, String> data, boolean highPriority) {
        if (fcm == null) {
            log.warn("FCM disabled; push to {} skipped", userId);
            return;
        }
        List<String> tokens = authClient.tokensFor(userId);
        if (tokens.isEmpty()) {
            log.info("No push tokens for {}; nothing to send", userId);
            return;
        }
        for (String tok : tokens) {
            try {
                Message msg = Message.builder()
                        .setToken(tok)
                        .putAllData(data == null ? Map.of() : data)
                        .setNotification(Notification.builder()
                                .setTitle(title).setBody(body).build())
                        .setAndroidConfig(AndroidConfig.builder()
                                .setPriority(highPriority
                                        ? AndroidConfig.Priority.HIGH
                                        : AndroidConfig.Priority.NORMAL)
                                .setNotification(AndroidNotification.builder()
                                        .setChannelId(highPriority ? "iers_offers" : "iers_info")
                                        .setSound("default")
                                        .build())
                                .build())
                        .build();
                String id = fcm.send(msg);
                log.info("FCM sent id={} to ***{}", id, tail(tok));
            } catch (FirebaseMessagingException e) {
                if (e.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED
                        || e.getMessagingErrorCode() == MessagingErrorCode.INVALID_ARGUMENT) {
                    log.info("Dead token, should be removed: ***{}", tail(tok));
                    // A later cleanup job could delete these from Auth.
                } else {
                    log.warn("FCM send failed: {}", e.getMessage());
                }
            }
        }
    }

    private static String tail(String t) { return t.length() < 6 ? "***" : t.substring(t.length() - 6); }
}
