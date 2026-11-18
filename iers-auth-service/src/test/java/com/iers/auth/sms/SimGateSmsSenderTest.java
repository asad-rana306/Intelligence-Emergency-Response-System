package com.iers.auth.sms;

import com.iers.auth.config.SmsProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Talks to a tiny local HTTP server that plays the role of SimGate, so the real HTTP code is tested.
 */
class SimGateSmsSenderTest {

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> apiKeys = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    /** HTTP status to answer on call 1, 2, 3 ... (the last one repeats). */
    private volatile int[] answers = {200};

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int n = calls.getAndIncrement();
            paths.add(exchange.getRequestURI().getPath());
            apiKeys.add(exchange.getRequestHeaders().getFirst("x-api-key"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int status = answers[Math.min(n, answers.length - 1)];
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private SimGateSmsSender sender(int maxAttempts) {
        return new SimGateSmsSender(new SmsProperties("simgate", config("http://127.0.0.1:" + server.getAddress().getPort(),
                "secret-key", "device-1", maxAttempts)));
    }

    private static SmsProperties.SimGate config(String baseUrl, String key, String device, int maxAttempts) {
        return new SmsProperties.SimGate(baseUrl, key, device, Duration.ofSeconds(1), Duration.ofSeconds(3), maxAttempts);
    }

    @Test
    @DisplayName("200 — SENT; the request has the right path, key and body")
    void sent() {
        SmsResult result = sender(2).send("+923001234567", "Your code is 123456");

        assertThat(result.status()).isEqualTo(SmsResult.Status.SENT);
        assertThat(paths).containsExactly("/v1/sms/send");
        assertThat(apiKeys).containsExactly("secret-key");
        assertThat(bodies.get(0))
                .contains("\"deviceId\":\"device-1\"")
                .contains("\"phoneNumber\":\"+923001234567\"")
                .contains("\"message\":\"Your code is 123456\"");
    }

    @Test
    @DisplayName("202 — QUEUED (the gateway phone is offline and will send later), still accepted")
    void queued() {
        answers = new int[]{202};

        SmsResult result = sender(2).send("+923001234567", "hi");

        assertThat(result.status()).isEqualTo(SmsResult.Status.QUEUED);
        assertThat(result.accepted()).isTrue();
    }

    @Test
    @DisplayName("401 — FAILED at once, no retry")
    void clientErrorIsNotRetried() {
        answers = new int[]{401};

        SmsResult result = sender(3).send("+923001234567", "hi");

        assertThat(result.status()).isEqualTo(SmsResult.Status.FAILED);
        assertThat(result.detail()).contains("401");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("500 then 200 — the retry succeeds")
    void serverErrorIsRetried() {
        answers = new int[]{500, 200};

        SmsResult result = sender(2).send("+923001234567", "hi");

        assertThat(result.status()).isEqualTo(SmsResult.Status.SENT);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("500 every time — FAILED after the allowed number of tries")
    void serverErrorGivesUp() {
        answers = new int[]{500};

        SmsResult result = sender(2).send("+923001234567", "hi");

        assertThat(result.status()).isEqualTo(SmsResult.Status.FAILED);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("gateway not reachable — FAILED, not an exception")
    void unreachable() {
        SimGateSmsSender closed = new SimGateSmsSender(new SmsProperties("simgate",
                config("http://127.0.0.1:1", "k", "d", 1)));

        SmsResult result = closed.send("+923001234567", "hi");

        assertThat(result.status()).isEqualTo(SmsResult.Status.FAILED);
    }

    @Test
    @DisplayName("missing api key or device id — the service refuses to start")
    void failsFastWithoutCredentials() {
        assertThatThrownBy(() -> new SimGateSmsSender(new SmsProperties("simgate",
                config("http://127.0.0.1:1", "", "device-1", 1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SIMGATE_API_KEY");
        assertThatThrownBy(() -> new SimGateSmsSender(new SmsProperties("simgate",
                config("http://127.0.0.1:1", "key", null, 1))))
                .isInstanceOf(IllegalStateException.class);
    }
}
