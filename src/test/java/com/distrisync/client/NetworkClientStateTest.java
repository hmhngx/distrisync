package com.distrisync.client;

import com.distrisync.protocol.MessageType;
import com.distrisync.protocol.RoomPermissions;
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
    void roleUpdate_forLocalClient_setsLocalPermissions() {
        try (NetworkClient client = new NetworkClient("127.0.0.1", 1, "client-id")) {
            client.ingestRoleUpdateForStateTest("client-id", RoomPermissions.OWNER, "client-id");
            await().atMost(3, TimeUnit.SECONDS)
                    .pollInterval(20, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> assertThat(client.getParticipantManager().getLocalPermissions())
                            .isEqualTo(RoomPermissions.OWNER));
        }
    }

    @Test
    void testRoomDeleted_triggersLobbyFetch() {
        try (NetworkClient client = new NetworkClient("127.0.0.1", 1, "client-id")) {
            client.ingestRoomDeletedForStateTest();

            await().atMost(3, TimeUnit.SECONDS)
                    .pollInterval(20, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> assertThat(client.outboundQueueContainsFrameOfTypeForTest(MessageType.FETCH_LOBBY))
                            .as("ROOM_DELETED schedules FETCH_LOBBY after FX eviction handling")
                            .isTrue());
        }
    }

    @Test
    void sessionRevoked_suppressesAutoReconnectAndClearsRoom() {
        try (NetworkClient client = new NetworkClient("127.0.0.1", 1, "client-id")) {
            assertThat(client.isAutoReconnectEnabledForTest()).isTrue();
            client.ingestSessionRevokedForStateTest("policy violation");
            assertThat(client.isAutoReconnectEnabledForTest()).isFalse();
            assertThat(client.getActiveRoomId()).isBlank();
            assertThat(client.isRunning()).isFalse();
            client.resumeAfterSessionRevoked();
            await().atMost(5, TimeUnit.SECONDS)
                    .pollInterval(50, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        assertThat(client.isAutoReconnectEnabledForTest()).isTrue();
                        assertThat(client.isRunning()).isTrue();
                    });
        }
    }

    @Test
    void selfJoinRoom_confirmLocksAuthorNameAndHydratesParticipant() {
        try (NetworkClient client = new NetworkClient("127.0.0.1", 1, "client-id")) {
            client.setDisplayNameForTest("Admin");
            client.ingestRoomMemberJoinedForStateTest("client-id", "Admin #F4A2");

            await().atMost(3, TimeUnit.SECONDS)
                    .pollInterval(20, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        assertThat(client.getAuthorName()).isEqualTo("Admin #F4A2");
                        assertThat(client.getLockedRoomNameForTest()).isEqualTo("Admin #F4A2");
                        assertThat(client.getParticipantManager().get("client-id").getName())
                                .isEqualTo("Admin #F4A2");
                    });
        }
    }

    @Test
    void selfJoinRoom_emptyAuthorName_doesNotClearLockedName() {
        try (NetworkClient client = new NetworkClient("127.0.0.1", 1, "client-id")) {
            client.setLockedRoomNameForTest("Admin #F4A2");
            client.setDisplayNameForTest("Admin #F4A2");
            client.ingestRoomMemberJoinedForStateTest("client-id", "");

            assertThat(client.getLockedRoomNameForTest()).isEqualTo("Admin #F4A2");
            assertThat(client.getAuthorName()).isEqualTo("Admin #F4A2");
        }
    }

    @Test
    void sendLeaveRoom_clearsLockedRoomName() {
        try (NetworkClient client = new NetworkClient("127.0.0.1", 1, "client-id")) {
            client.setLockedRoomNameForTest("Admin #F4A2");
            client.setDisplayNameForTest("Admin #F4A2");
            client.setActiveRoomIdForTest("room-1");
            client.ingestRoomMemberJoinedForStateTest("client-id", "Admin #F4A2");
            client.sendLeaveRoom();
            assertThat(client.getLockedRoomNameForTest()).isNull();
            assertThat(client.getAuthorName()).isBlank();
        }
    }

    @Test
    void reconnectPrefersLockedRoomNameOverRequestedDisplayName() {
        try (NetworkClient client = new NetworkClient("127.0.0.1", 1, "client-id")) {
            client.setLockedRoomNameForTest("Admin #F4A2");
            client.setDisplayNameForTest("Admin");
            assertThat(client.resolveReconnectDisplayNameForTest()).isEqualTo("Admin #F4A2");
        }
    }

    @Test
    void sessionRevoked_reinitializeAudioEngine_restoresLiveEngine() {
        try (NetworkClient client = new NetworkClient("127.0.0.1", 1, "client-id")) {
            client.ingestSessionRevokedForStateTest("kicked");
            client.getAudioEngine().close();
            AudioEngine closed = client.getAudioEngine();
            assertThat(closed.isClosed()).isTrue();

            client.reinitializeAudioEngine();
            AudioEngine after = client.getAudioEngine();
            assertThat(after).isNotSameAs(closed);
            assertThat(after.isClosed()).isFalse();
        }
    }
}
