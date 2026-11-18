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
 * Most messages have a visible notification AND data. A visible notification is shown by Android
 * itself, so it works even when the app is closed. Urgent messages use high priority and the
 * "iers_offers" channel, which the Flutter app creates with sound.
 * A "data only" message has no visible notification: the app shows its own (OFF_DUTY). It is always
 * sent with high priority, otherwise a sleeping phone may get it late.
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
            boolean high = message.urgent() || message.dataOnly();
            AndroidConfig.Builder android = AndroidConfig.builder()
                    .setPriority(high ? AndroidConfig.Priority.HIGH : AndroidConfig.Priority.NORMAL);
            if (!message.dataOnly()) {
                android.setNotification(AndroidNotification.builder()
                        .setChannelId(message.urgent() ? CHANNEL_URGENT : CHANNEL_INFO)
                        .setSound("default")
                        .build());
            }
            if (message.ttl() != null) {
                android.setTtl(message.ttl().toMillis());
            }

            Message.Builder builder = Message.builder()
                    .setToken(token)
                    .putAllData(message.data())
                    .setAndroidConfig(android.build());
            if (!message.dataOnly()) {
                builder.setNotification(Notification.builder()
                        .setTitle(message.title())
                        .setBody(message.body())
                        .build());
            }
            Message fcmMessage = builder.build();

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
