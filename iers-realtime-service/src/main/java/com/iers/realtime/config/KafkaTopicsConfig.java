package com.iers.realtime.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Makes sure both topics exist before the listeners start. The group starts at "now"
 * (auto-offset-reset: latest), so a topic created a moment too late would lose the first events.
 * Partitions and replicas are left to the broker defaults, so an existing topic is not changed.
 */
@Configuration
public class KafkaTopicsConfig {

    @Bean
    public NewTopic crashEventsTopic(@Value("${realtime.kafka.crash-topic:crash-events}") String name) {
        return TopicBuilder.name(name).build();
    }

    @Bean
    public NewTopic incidentEventsTopic(@Value("${realtime.kafka.incident-topic:incident-events}") String name) {
        return TopicBuilder.name(name).build();
    }
}
