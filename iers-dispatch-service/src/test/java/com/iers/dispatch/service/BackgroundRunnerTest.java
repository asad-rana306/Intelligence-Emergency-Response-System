package com.iers.dispatch.service;

import com.iers.dispatch.config.NotifyProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class BackgroundRunnerTest {

    private BackgroundRunner runner;

    private BackgroundRunner runner(int threads, int queue) {
        runner = new BackgroundRunner(new NotifyProperties("http://t", "1122", Duration.ofSeconds(20),
                Duration.ofMinutes(2), Duration.ofMinutes(30), 20, new NotifyProperties.Executor(threads, queue)));
        return runner;
    }

    @AfterEach
    void stop() {
        if (runner != null) {
            runner.stop();
        }
    }

    @Test
    @DisplayName("a task runs on another thread")
    void runsInBackground() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        String[] threadName = new String[1];

        boolean accepted = runner(1, 5).run("test", () -> {
            threadName[0] = Thread.currentThread().getName();
            done.countDown();
        });

        assertThat(accepted).isTrue();
        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(threadName[0]).startsWith("notify-");
    }

    @Test
    @DisplayName("a task that throws does not kill the pool: the next task still runs")
    void failureIsContained() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        BackgroundRunner r = runner(1, 5);

        r.run("bad", () -> {
            throw new IllegalStateException("boom");
        });
        r.run("good", done::countDown);

        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("queue full: the task is refused (false), not piled up")
    void refusesWhenFull() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        BackgroundRunner r = runner(1, 1);

        assertThat(r.run("busy", () -> await(release))).isTrue();     // takes the only thread
        assertThat(r.run("queued", () -> { })).isTrue();              // takes the only queue place
        assertThat(r.run("refused", () -> { })).isFalse();            // nowhere to go

        release.countDown();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
