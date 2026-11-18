package com.iers.dispatch.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks that application.yml style settings turn into the right values, and that the
 * defaults are the ones we expect when nothing is configured. No database or Kafka needed.
 */
class PropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class);

    @Test
    @DisplayName("defaults — nothing configured")
    void defaults() {
        runner.run(ctx -> {
            SmsProperties sms = ctx.getBean(SmsProperties.class);
            assertThat(sms.provider()).isEqualTo("log");
            assertThat(sms.simgate().baseUrl()).isEqualTo("https://api.simgate.app");
            assertThat(sms.simgate().maxAttempts()).isEqualTo(2);
            assertThat(sms.simgate().connectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(sms.simgate().readTimeout()).isEqualTo(Duration.ofSeconds(10));

            PushProperties push = ctx.getBean(PushProperties.class);
            assertThat(push.enabled()).isTrue();
            assertThat(push.hasCredentials()).isFalse();
            assertThat(push.offerTtl()).isEqualTo(Duration.ofSeconds(30));

            NotifyProperties notify = ctx.getBean(NotifyProperties.class);
            assertThat(notify.trackingBaseUrl()).isEqualTo("http://localhost:3000/track");
            assertThat(notify.emergencyNumber()).isEqualTo("1122");
            assertThat(notify.contactRetryInterval()).isEqualTo(Duration.ofSeconds(20));
            assertThat(notify.contactLease()).isEqualTo(Duration.ofMinutes(2));
            assertThat(notify.giveUpAfter()).isEqualTo(Duration.ofMinutes(30));
            assertThat(notify.batchSize()).isEqualTo(20);
            assertThat(notify.executor().threads()).isEqualTo(4);
            assertThat(notify.executor().queueCapacity()).isEqualTo(500);
        });
    }

    @Test
    @DisplayName("values from application.yml / environment are used")
    void overrides() {
        runner.withPropertyValues(
                "dispatch.sms.provider=simgate",
                "dispatch.sms.simgate.api-key=k",
                "dispatch.sms.simgate.device-id=d",
                "dispatch.sms.simgate.max-attempts=5",
                "dispatch.fcm.enabled=false",
                "dispatch.fcm.credentials-path=/app/firebase-sa.json",
                "dispatch.fcm.project-id=iers-a6400",
                "dispatch.fcm.offer-ttl=45s",
                "dispatch.notify.emergency-number=15",
                "dispatch.notify.contact-lease=90s",
                "dispatch.notify.give-up-after=10m",
                "dispatch.notify.batch-size=5",
                "dispatch.notify.executor.threads=2"
        ).run(ctx -> {
            SmsProperties sms = ctx.getBean(SmsProperties.class);
            assertThat(sms.provider()).isEqualTo("simgate");
            assertThat(sms.simgate().apiKey()).isEqualTo("k");
            assertThat(sms.simgate().deviceId()).isEqualTo("d");
            assertThat(sms.simgate().maxAttempts()).isEqualTo(5);

            PushProperties push = ctx.getBean(PushProperties.class);
            assertThat(push.enabled()).isFalse();
            assertThat(push.hasCredentials()).isTrue();
            assertThat(push.projectId()).isEqualTo("iers-a6400");
            assertThat(push.offerTtl()).isEqualTo(Duration.ofSeconds(45));

            NotifyProperties notify = ctx.getBean(NotifyProperties.class);
            assertThat(notify.emergencyNumber()).isEqualTo("15");
            assertThat(notify.contactLease()).isEqualTo(Duration.ofSeconds(90));
            assertThat(notify.giveUpAfter()).isEqualTo(Duration.ofMinutes(10));
            assertThat(notify.batchSize()).isEqualTo(5);
            assertThat(notify.executor().threads()).isEqualTo(2);
            assertThat(notify.executor().queueCapacity()).isEqualTo(500); // not overridden
        });
    }
}
