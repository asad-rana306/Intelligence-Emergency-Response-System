package com.iers.dispatch.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iers.dispatch.entity.Incident;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IncidentEventPublisherTest {

    @Mock private KafkaTemplate<String, String> kafkaTemplate;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private IncidentEventPublisher publisher;

    private final UUID incidentId = UUID.randomUUID();
    private final UUID crashId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();
    private final UUID responderId = UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private Incident incident() {
        return Incident.builder().id(incidentId).crashEventId(crashId).driverId(driverId).build();
    }

    private CompletableFuture<SendResult<String, String>> okFuture() {
        RecordMetadata metadata = new RecordMetadata(new TopicPartition("incident-events", 0), 0L, 0, 0L, 0, 0);
        return CompletableFuture.completedFuture(new SendResult<>(null, metadata));
    }

    @Test
    @DisplayName("publish — sends the contract shape to incident-events, keyed by incidentId")
    void publish_sendsContractShape() throws Exception {
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(okFuture());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("incidentId", incidentId.toString());
        data.put("dispatchExpiresAt", "2026-10-29T18:30:42Z");
        data.put("allergies", null);

        publisher.publish("INCIDENT_OFFERED", incident(), responderId, data);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("incident-events"), eq(incidentId.toString()), payload.capture());

        JsonNode json = objectMapper.readTree(payload.getValue());
        assertThat(json.get("eventType").asText()).isEqualTo("INCIDENT_OFFERED");
        assertThat(json.get("incidentId").asText()).isEqualTo(incidentId.toString());
        assertThat(json.get("crashEventId").asText()).isEqualTo(crashId.toString());
        assertThat(json.get("driverId").asText()).isEqualTo(driverId.toString());
        assertThat(json.get("responderId").asText()).isEqualTo(responderId.toString());
        assertThat(json.get("timestamp").asText()).endsWith("Z");
        assertThat(json.get("data").get("dispatchExpiresAt").asText()).isEqualTo("2026-10-29T18:30:42Z");
        assertThat(json.get("data").has("allergies")).isTrue();
    }

    @Test
    @DisplayName("publish — an event with no responder and no data still goes out")
    void publish_noResponderNoData() throws Exception {
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(okFuture());

        Incident bare = Incident.builder().id(incidentId).build();
        publisher.publish("NO_RESPONDER_AVAILABLE", bare, null, null);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("incident-events"), eq(incidentId.toString()), payload.capture());
        JsonNode json = objectMapper.readTree(payload.getValue());
        assertThat(json.get("responderId").isNull()).isTrue();
        assertThat(json.get("driverId").isNull()).isTrue();
        assertThat(json.get("crashEventId").isNull()).isTrue();
    }

    @Test
    @DisplayName("publish — inside a transaction the event waits for the commit")
    void publish_waitsForCommit() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(okFuture());
        TransactionSynchronizationManager.initSynchronization();

        publisher.publish("INCIDENT_CREATED", incident(), null, Map.of("incidentId", incidentId.toString()));

        verifyNoInteractions(kafkaTemplate); // not yet: the transaction is still open

        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertThat(syncs).hasSize(1);
        syncs.get(0).afterCommit();

        verify(kafkaTemplate).send(eq("incident-events"), eq(incidentId.toString()), anyString());
    }

    @Test
    @DisplayName("publish — a Kafka failure is logged, never thrown")
    void publish_kafkaFutureFails() {
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(failed);

        assertThatCode(() -> publisher.publish("INCIDENT_STATUS", incident(), responderId, Map.of()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("publish — send() throwing is also swallowed")
    void publish_sendThrows() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("closed"));

        assertThatCode(() -> publisher.publish("INCIDENT_STATUS", incident(), responderId, Map.of()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("publish — data that cannot be turned into JSON is dropped, not thrown")
    void publish_unserializable() {
        assertThatCode(() -> publisher.publish("INCIDENT_STATUS", incident(), responderId,
                Map.of("bad", new Object())))
                .doesNotThrowAnyException();

        verifyNoInteractions(kafkaTemplate);
    }
}
