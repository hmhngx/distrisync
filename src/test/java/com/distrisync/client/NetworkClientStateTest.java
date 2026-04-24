package com.distrisync.client;

import com.distrisync.protocol.MessageType;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Inbound state transitions that must produce specific outbound frames (e.g. lobby refresh).
 */
class NetworkClientStateTest {

    @BeforeAll
    static void initJavaFxToolkit() {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException ignored) {
            // Toolkit already started by another test class in the same JVM.
        }
    }

    @Test
    void testRoomDeleted_triggersLobbyFetch() {
        try (NetworkClient client = new NetworkClient("127.0.0.1", 1, "author", "client-id")) {
            client.ingestRoomDeletedForStateTest();

            await().atMost(3, TimeUnit.SECONDS)
                    .pollInterval(20, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> assertThat(client.outboundQueueContainsFrameOfTypeForTest(MessageType.FETCH_LOBBY))
                            .as("ROOM_DELETED schedules FETCH_LOBBY after FX eviction handling")
                            .isTrue());
        }
    }
}
