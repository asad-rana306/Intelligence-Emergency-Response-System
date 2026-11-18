package com.iers.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the settings classes. Kept out of the main class so web tests stay light. */
@Configuration
@EnableConfigurationProperties({
        OtpProperties.class,
        SmsProperties.class,
        DutyProperties.class,
        PushProperties.class
})
public class PropertiesConfig {
}
