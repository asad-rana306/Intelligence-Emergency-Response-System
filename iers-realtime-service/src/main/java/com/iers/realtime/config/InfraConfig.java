package com.iers.realtime.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iers.realtime.store.LiveStateStore;
import com.iers.realtime.store.RedisLiveStateStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class InfraConfig {

    /** One clock for the whole service (tests use a fixed one). */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** Runs the STOMP heartbeats and the short wait before a SNAPSHOT. */
    @Bean(name = "brokerHeartbeatScheduler")
    public ThreadPoolTaskScheduler brokerHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("live-sched-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean
    public LiveStateStore liveStateStore(StringRedisTemplate redis,
                                         ObjectMapper mapper,
                                         @Value("${realtime.snapshot-ttl-minutes:120}") long ttlMinutes) {
        return new RedisLiveStateStore(redis, mapper, Duration.ofMinutes(ttlMinutes));
    }
}
