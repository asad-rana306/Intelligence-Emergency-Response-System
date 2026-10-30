package com.iers.iot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling   // Phase 2: runs the 5-second sweep that finishes crashes whose timer was lost
public class IotTelemetryApplication {
    public static void main(String[] args) {
        SpringApplication.run(IotTelemetryApplication.class, args);
    }
}