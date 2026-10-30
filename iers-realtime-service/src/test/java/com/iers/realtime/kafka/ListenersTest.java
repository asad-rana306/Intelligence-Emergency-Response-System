package com.iers.realtime.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iers.realtime.dto.KafkaEvent;
import com.iers.realtime.service.FanOutService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ListenersTest {

    private FanOutService fanOut;
    private CrashEventListener crashListener;
    private IncidentEventListener incidentListener;

    @BeforeEach
    void setUp() {
        fanOut = mock(FanOutService.class);
        ObjectMapper mapper = new ObjectMapper();
        crashListener = new CrashEventListener(fanOut, mapper);
        incidentListener = new IncidentEventListener(fanOut, mapper);
    }

    @Test
    @DisplayName("A crash event is parsed and handed to the fan-out")
    void crash() {
        crashListener.consume("{\"eventType\":\"CRASH_PENDING\",\"crashEventId\":\"c1\",\"driverId\":\"d1\"}");

        ArgumentCaptor<KafkaEvent> captor = ArgumentCaptor.forClass(KafkaEvent.class);
        verify(fanOut).handle(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("CRASH_PENDING");
        assertThat(captor.getValue().driverId()).isEqualTo("d1");
    }

    @Test
    @DisplayName("An incident event is parsed and handed to the fan-out")
    void incident() {
        incidentListener.consume("{\"eventType\":\"INCIDENT_OFFERED\",\"incidentId\":\"i1\",\"responderId\":\"r1\","
                + "\"data\":{\"attempt\":1}}");

        ArgumentCaptor<KafkaEvent> captor = ArgumentCaptor.forClass(KafkaEvent.class);
        verify(fanOut).handle(captor.capture());
        assertThat(captor.getValue().incidentId()).isEqualTo("i1");
        assertThat(captor.getValue().data()).containsEntry("attempt", 1);
    }

    @Test
    @DisplayName("Bad JSON, non-objects and empty messages are skipped without an error")
    void unreadable() {
        for (String bad : new String[]{"not json {{", "[1]", "", "null"}) {
            assertThatCode(() -> crashListener.consume(bad)).doesNotThrowAnyException();
            assertThatCode(() -> incidentListener.consume(bad)).doesNotThrowAnyException();
        }
        verifyNoInteractions(fanOut);
    }

    @Test
    @DisplayName("An error inside the fan-out does not escape the listener")
    void fanOutFails() {
        doThrow(new IllegalStateException("boom")).when(fanOut).handle(any());
        String json = "{\"eventType\":\"CRASH_PENDING\"}";

        assertThatCode(() -> crashListener.consume(json)).doesNotThrowAnyException();
        assertThatCode(() -> incidentListener.consume(json)).doesNotThrowAnyException();
    }
}
