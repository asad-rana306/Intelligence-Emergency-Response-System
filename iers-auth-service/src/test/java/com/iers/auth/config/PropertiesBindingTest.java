package com.iers.auth.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks that application.yml style settings turn into the right values, and that the
 * defaults are the ones we expect when nothing is configured. No database or Redis needed.
 */
class PropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class);

    @Test
    @DisplayName("defaults — nothing configured")
    void defaults() {
        runner.run(ctx -> {
            OtpProperties otp = ctx.getBean(OtpProperties.class);
            assertThat(otp.requiredOnRegister()).isTrue();
            assertThat(otp.codeLength()).isEqualTo(6);
            assertThat(otp.codeTtl()).isEqualTo(Duration.ofMinutes(5));
            assertThat(otp.maxVerifyAttempts()).isEqualTo(5);
            assertThat(otp.maxSendsPerWindow()).isEqualTo(3);
            assertThat(otp.sendWindow()).isEqualTo(Duration.ofHours(1));
            assertThat(otp.resendCooldown()).isEqualTo(Duration.ofSeconds(30));
            assertThat(otp.verifiedTtl()).isEqualTo(Duration.ofMinutes(10));

            DutyProperties duty = ctx.getBean(DutyProperties.class);
            assertThat(duty.maxOnDuty()).isEqualTo(Duration.ofHours(1));
            assertThat(duty.heartbeatTimeout()).isEqualTo(Duration.ofMinutes(2));
            assertThat(duty.sweepLockTtl()).isEqualTo(Duration.ofSeconds(25));
            assertThat(duty.events().enabled()).isFalse();
            assertThat(duty.events().topic()).isEqualTo("duty-events");

            SmsProperties sms = ctx.getBean(SmsProperties.class);
            assertThat(sms.provider()).isEqualTo("log");
            assertThat(sms.simgate().baseUrl()).isEqualTo("https://api.simgate.app");
            assertThat(sms.simgate().maxAttempts()).isEqualTo(2);

            assertThat(ctx.getBean(PushProperties.class).maxTokensPerUser()).isEqualTo(5);
        });
    }

    @Test
    @DisplayName("values from application.yml / environment are used")
    void overrides() {
        runner.withPropertyValues(
                "auth.otp.required-on-register=false",
                "auth.otp.code-length=4",
                "auth.otp.code-ttl=10m",
                "auth.duty.max-on-duty=30m",
                "auth.duty.heartbeat-timeout=90s",
                "auth.duty.events.enabled=true",
                "auth.duty.events.topic=my-topic",
                "auth.sms.provider=simgate",
                "auth.sms.simgate.api-key=k",
                "auth.sms.simgate.device-id=d",
                "auth.push.max-tokens-per-user=2"
        ).run(ctx -> {
            OtpProperties otp = ctx.getBean(OtpProperties.class);
            assertThat(otp.requiredOnRegister()).isFalse();
            assertThat(otp.codeLength()).isEqualTo(4);
            assertThat(otp.codeTtl()).isEqualTo(Duration.ofMinutes(10));

            DutyProperties duty = ctx.getBean(DutyProperties.class);
            assertThat(duty.maxOnDuty()).isEqualTo(Duration.ofMinutes(30));
            assertThat(duty.heartbeatTimeout()).isEqualTo(Duration.ofSeconds(90));
            assertThat(duty.events().enabled()).isTrue();
            assertThat(duty.events().topic()).isEqualTo("my-topic");

            SmsProperties sms = ctx.getBean(SmsProperties.class);
            assertThat(sms.provider()).isEqualTo("simgate");
            assertThat(sms.simgate().apiKey()).isEqualTo("k");
            assertThat(sms.simgate().deviceId()).isEqualTo("d");

            assertThat(ctx.getBean(PushProperties.class).maxTokensPerUser()).isEqualTo(2);
        });
    }
}
