package com.iers.dispatch.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the settings classes. Kept out of the main class so web tests stay light. */
@Configuration
@EnableConfigurationProperties({
        SmsProperties.class,
        PushProperties.class,
        NotifyProperties.class
})
public class PropertiesConfig {
}
