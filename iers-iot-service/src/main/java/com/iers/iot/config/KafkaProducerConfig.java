package com.iers.iot.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaProducerConfig {

    @Value("${iot.kafka.topic:crash-events}")
    private String crashEventsTopic;

    @Bean
    public NewTopic crashEventsTopic() {
        return TopicBuilder.name(crashEventsTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
