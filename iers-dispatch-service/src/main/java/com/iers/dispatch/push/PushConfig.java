package com.iers.dispatch.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.iers.dispatch.config.PushProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Chooses the PushSender:
 *  - dispatch.fcm.enabled=false, or no credentials path / project id : only logs (development)
 *  - otherwise : Firebase Cloud Messaging. An unreadable key file stops the start-up on purpose.
 */
@Slf4j
@Configuration
public class PushConfig {

    @Bean
    public PushSender pushSender(PushProperties props) {
        if (!props.enabled()) {
            log.warn("Push is switched off (dispatch.fcm.enabled=false): pushes are only logged");
            return new LoggingPushSender();
        }
        if (!props.hasCredentials()) {
            log.warn("Firebase is not configured (GOOGLE_APPLICATION_CREDENTIALS / FIREBASE_PROJECT_ID empty): "
                    + "pushes are only logged");
            return new LoggingPushSender();
        }
        try (InputStream in = new FileInputStream(props.credentialsPath())) {
            if (FirebaseApp.getApps().isEmpty()) {
                FirebaseApp.initializeApp(FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(in))
                        .setProjectId(props.projectId())
                        .build());
            }
        } catch (IOException e) {
            throw new IllegalStateException("dispatch.fcm.credentials-path '" + props.credentialsPath()
                    + "' cannot be read: " + e.getMessage(), e);
        }
        log.info("Firebase push ready: project={}", props.projectId());
        return new FcmPushSender(FirebaseMessaging.getInstance());
    }
}
