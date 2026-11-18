package com.iers.dispatch.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;
import java.io.InputStream;

/**
 * Initializes the Firebase Admin SDK, if a service-account key file is present.
 *   - Path is taken from GOOGLE_APPLICATION_CREDENTIALS (standard Google env var).
 *   - Project id from FIREBASE_PROJECT_ID.
 *
 * If either is missing, push is silently disabled so local dev still works.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "fcm.enabled", havingValue = "true", matchIfMissing = true)
public class FirebasePushConfig {

    @Value("${fcm.credentials-path:${GOOGLE_APPLICATION_CREDENTIALS:}}")
    private String credentialsPath;

    @Value("${fcm.project-id:${FIREBASE_PROJECT_ID:}}")
    private String projectId;

    @PostConstruct
    public void init() {
        if (credentialsPath == null || credentialsPath.isBlank()
                || projectId == null || projectId.isBlank()) {
            log.warn("Firebase not configured (credentials or project id missing); push is disabled");
            return;
        }
        try {
            if (FirebaseApp.getApps().isEmpty()) {
                try (InputStream in = new FileInputStream(credentialsPath)) {
                    FirebaseOptions options = FirebaseOptions.builder()
                            .setCredentials(GoogleCredentials.fromStream(in))
                            .setProjectId(projectId)
                            .build();
                    FirebaseApp.initializeApp(options);
                    log.info("Firebase Admin ready: project={}", projectId);
                }
            }
        } catch (Exception e) {
            log.error("Firebase init failed: {}", e.getMessage(), e);
        }
    }

    @Bean
    public FirebaseMessaging firebaseMessaging() {
        if (FirebaseApp.getApps().isEmpty()) return null;
        return FirebaseMessaging.getInstance();
    }
}
