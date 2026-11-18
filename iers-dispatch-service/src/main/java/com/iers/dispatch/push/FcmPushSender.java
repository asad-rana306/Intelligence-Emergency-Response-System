package com.iers.dispatch.push;

import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends the push through Firebase Cloud Messaging.
 *
 * Every message has a visible notification AND data. A visible notification is shown by Android
 * itself, so it works even when the app is closed. Urgent messages use high priority and the
 * "iers_offers" channel, which the Flutter app creates with sound.
 */
@Slf4j
public class FcmPushSender implements PushSender {

    static final String CHANNEL_URGENT = "iers_offers";
    static final String CHANNEL_INFO = "iers_info";

    private final FirebaseMessaging fcm;

    public FcmPushSender(FirebaseMessaging fcm) {
        this.fcm = fcm;
    }

    @Override
    public PushOutcome send(String token, PushMessage message) {
        try {
            AndroidConfig.Builder android = AndroidConfig.builder()
                    .setPriority(message.urgent() ? AndroidConfig.Priority.HIGH : AndroidConfig.Priority.NORMAL)
                    .setNotification(AndroidNotification.builder()
                            .setChannelId(message.urgent() ? CHANNEL_URGENT : CHANNEL_INFO)
                            .setSound("default")
                            .build());
            if (message.ttl() != null) {
                android.setTtl(message.ttl().toMillis());
            }

            Message fcmMessage = Message.builder()
                    .setToken(token)
                    .putAllData(message.data())
                    .setNotification(Notification.builder()
                            .setTitle(message.title())
                            .setBody(message.body())
                            .build())
                    .setAndroidConfig(android.build())
                    .build();

            String id = fcm.send(fcmMessage);
            log.info("FCM sent id={} type={} to ***{}", id, message.data().get("type"), tail(token));
            return PushOutcome.SENT;
        } catch (FirebaseMessagingException e) {
            MessagingErrorCode code = e.getMessagingErrorCode();
            if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.SENDER_ID_MISMATCH) {
                log.info("FCM token is dead ({}): ***{}", code, tail(token));
                return PushOutcome.DEAD_TOKEN;
            }
            log.warn("FCM send failed ({}) to ***{}: {}", code, tail(token), e.getMessage());
            return PushOutcome.FAILED;
        } catch (RuntimeException e) {
            log.warn("FCM send error to ***{}: {}", tail(token), e.getMessage());
            return PushOutcome.FAILED;
        }
    }

    private static String tail(String token) {
        return token == null || token.length() < 6 ? "***" : token.substring(token.length() - 6);
    }
}
