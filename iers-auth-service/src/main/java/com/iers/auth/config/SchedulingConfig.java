package com.iers.auth.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on @Scheduled jobs. Set {@code auth.duty.sweep.enabled=false} on an instance that
 * should not run the duty sweep (the others will still run it).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "auth.duty.sweep", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
