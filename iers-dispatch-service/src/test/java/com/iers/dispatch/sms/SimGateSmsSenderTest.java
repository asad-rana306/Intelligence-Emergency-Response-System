package com.iers.dispatch.sms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iers.dispatch.config.SmsProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Talks to a tiny fake SimGate on localhost, so the real HTTP code runs. */
class SimGateSmsSenderTest {

    private static final String PHONE = "+923001234567";

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> apiKeys = new CopyOnWriteArrayList<>();
    /** Status code to answer for call 1, 2, 3 ... (the last one repeats). */
    private volatile int[] answers = {200};

    @BeforeEach
    void startFakeSimGate() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/sms/send", exchange -> {
            int n = calls.incrementAndGet();
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            apiKeys.add(exchange.getRequestHeaders().getFirst("x-api-key"));
            int[] a = answers;
            exchange.sendResponseHeaders(a[Math.min(n, a.length) - 1], -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopFakeSimGate() {
        server.stop(0);
    }

    private SimGateSmsSender sender(String baseUrl, int maxAttempts) {
        return new SimGateSmsSender(new SmsProperties("simgate", new SmsProperties.SimGate(
                baseUrl, "key-1", "dev-1", Duration.ofSeconds(2), Duration.ofSeconds(2), maxAttempts)));
    }

    private SimGateSmsSender sender(int maxAttempts) {
        return sender("http://127.0.0.1:" + server.getAddress().getPort(), maxAttempts);
    }

    @Test
    @DisplayName("200 = SENT, with the api key header and the right JSON body")
    void sent() throws Exception {
        answers = new int[]{200};

        SmsResult result = sender(2).send(PHONE, "hello");

        assertThat(result.status()).isEqualTo(SmsResult.Status.SENT);
        assertThat(result.accepted()).isTrue();
        assertThat(apiKeys).containsExactly("key-1");
        JsonNode body = new ObjectMapper().readTree(bodies.get(0));
        assertThat(body.get("deviceId").asText()).isEqualTo("dev-1");
        assertThat(body.get("phoneNumber").asText()).isEqualTo(PHONE);
        assertThat(body.get("message").asText()).isEqualTo("hello");
    }

    @Test
    @DisplayName("202 = QUEUED (the gateway phone is offline) and still counts as accepted")
    void queued() {
        answers = new int[]{202};

        SmsResult result = sender(2).send(PHONE, "hello");

        assertThat(result.status()).isEqualTo(SmsResult.Status.QUEUED);
        assertThat(result.accepted()).isTrue();
    }

    @Test
    @DisplayName("a 4xx answer (bad key or number) fails at once, without a retry")
    void clientErrorIsNotRetried() {
        answers = new int[]{401};

        SmsResult result = sender(3).send(PHONE, "hello");

        assertThat(result.status()).isEqualTo(SmsResult.Status.FAILED);
        assertThat(result.detail()).contains("401");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a 5xx answer is retried, and the retry can succeed")
    void serverErrorIsRetried() {
        answers = new int[]{500, 200};

        SmsResult result = sender(2).send(PHONE, "hello");

        assertThat(result.status()).isEqualTo(SmsResult.Status.SENT);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("5xx every time: FAILED after max-attempts tries")
    void serverErrorGivesUp() {
        answers = new int[]{503};

        SmsResult result = sender(2).send(PHONE, "hello");

        assertThat(result.status()).isEqualTo(SmsResult.Status.FAILED);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("nobody listening: FAILED, never an exception")
    void networkError() throws IOException {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }

        SmsResult result = sender("http://127.0.0.1:" + closedPort, 1).send(PHONE, "hello");

        assertThat(result.status()).isEqualTo(SmsResult.Status.FAILED);
    }

    @Test
    @DisplayName("simgate without key or device id refuses to start")
    void missingConfigurationFailsFast() {
        SmsProperties noKey = new SmsProperties("simgate", new SmsProperties.SimGate(
                "http://x", "", "dev", Duration.ofSeconds(1), Duration.ofSeconds(1), 2));
        SmsProperties noDevice = new SmsProperties("simgate", new SmsProperties.SimGate(
                "http://x", "key", null, Duration.ofSeconds(1), Duration.ofSeconds(1), 2));

        assertThatThrownBy(() -> new SimGateSmsSender(noKey)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SimGateSmsSender(noDevice)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the log sender only prints and reports SENT")
    void loggingSender() {
        assertThat(new LoggingSmsSender().send(PHONE, "hello").status()).isEqualTo(SmsResult.Status.SENT);
    }
}
