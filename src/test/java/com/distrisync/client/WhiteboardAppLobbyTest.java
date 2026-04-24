package com.distrisync.client;

import javafx.application.Platform;
import javafx.scene.control.Button;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Headless-style tests for lobby manual refresh debouncing (no full {@link WhiteboardApp} stage).
 */
class WhiteboardAppLobbyTest {

    @BeforeAll
    static void initJavaFxToolkit() {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException ignored) {
            // Toolkit already started by another test class in the same JVM.
        }
    }

    @Test
    void manualRefreshDebouncesRapidProgrammaticFires() throws Exception {
        AtomicInteger fetchCalls = new AtomicInteger(0);
        Button refreshBtn = new Button();
        WhiteboardApp.wireLobbyRefreshButton(refreshBtn, fetchCalls::incrementAndGet);

        CountDownLatch firstBurst = new CountDownLatch(1);
        Platform.runLater(() -> {
            refreshBtn.fire();
            assertThat(refreshBtn.isDisabled()).as("after first refresh, button must be disabled").isTrue();
            assertThat(refreshBtn.getText()).isEqualTo("Refreshing...");
            refreshBtn.fire();
            assertThat(fetchCalls.get())
                    .as("second fire during cooldown must not run fetch again")
                    .isEqualTo(1);
            assertThat(refreshBtn.isDisabled()).isTrue();
            firstBurst.countDown();
        });
        assertThat(firstBurst.await(5, TimeUnit.SECONDS)).isTrue();

        await().atMost(2, TimeUnit.SECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(refreshBtn.isDisabled())
                        .as("after 1s debounce, button re-enables")
                        .isFalse());
        assertThat(refreshBtn.getText()).isEqualTo("Refresh ↻");

        CountDownLatch secondFire = new CountDownLatch(1);
        Platform.runLater(() -> {
            refreshBtn.fire();
            secondFire.countDown();
        });
        assertThat(secondFire.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(fetchCalls.get()).isEqualTo(2);
    }
}
