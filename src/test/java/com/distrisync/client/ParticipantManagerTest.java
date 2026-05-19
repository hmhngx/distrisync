package com.distrisync.client;

import com.distrisync.protocol.Message;
import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.MessageType;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ParticipantManagerTest {

    @BeforeAll
    static void initJavaFxToolkit() {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException ignored) {
            // Toolkit already started by another test class in the same JVM.
        }
    }

    @Test
    void testParticipantManagerUpdatesMuteState() {
        ParticipantManager participantManager = new ParticipantManager();
        participantManager.putParticipant("User123", "User123");

        ByteBuffer frame = MessageCodec.encodeVoiceState("User123", true);
        Message voiceStateMessage = MessageCodec.decode(frame);
        MessageCodec.VoiceStatePayload payload = MessageCodec.decodeVoiceState(voiceStateMessage);

        participantManager.onVoiceState(payload.clientId(), payload.isMuted());

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            Participant participant = participantManager.get("User123");
            assertThat(participant).isNotNull();
            assertThat(participant.isMutedProperty().get()).isTrue();
        });
    }

    @Test
    void setCurrentBoardId_updatesParticipantBoard() {
        ParticipantManager participantManager = new ParticipantManager();
        participantManager.putParticipant("peer-1", "Peer One");
        participantManager.setCurrentBoardId("peer-1", "Board-2");

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            Participant participant = participantManager.get("peer-1");
            assertThat(participant).isNotNull();
            assertThat(participant.getCurrentBoardId()).isEqualTo("Board-2");
        });
    }

    @Test
    void putParticipant_overwritesUuidSeedWhenRealNameArrives() {
        String clientId = "550e8400-e29b-41d4-a716-446655440000";
        ParticipantManager participantManager = new ParticipantManager();
        participantManager.setCurrentBoardId(clientId, "Board-1");
        participantManager.putParticipant(clientId, "Alice");

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            Participant participant = participantManager.get(clientId);
            assertThat(participant).isNotNull();
            assertThat(participant.getName()).isEqualTo("Alice");
        });
    }

    @Test
    void putParticipant_overwritesClientIdPlaceholderWhenRealNameArrives() {
        String clientId = "client-a";
        ParticipantManager participantManager = new ParticipantManager();
        participantManager.setCurrentBoardId(clientId, "Board-1");
        participantManager.putParticipant(clientId, "Alice");

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            Participant participant = participantManager.get(clientId);
            assertThat(participant).isNotNull();
            assertThat(participant.getName()).isEqualTo("Alice");
        });
    }

    @Test
    void putParticipant_overwritesStaleNameWhenRealNameArrives() {
        String clientId = "client-a";
        ParticipantManager participantManager = new ParticipantManager();
        participantManager.putParticipant(clientId, "Stale Name");
        participantManager.putParticipant(clientId, "Alice");

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            Participant participant = participantManager.get(clientId);
            assertThat(participant).isNotNull();
            assertThat(participant.getName()).isEqualTo("Alice");
        });
    }

    @Test
    void remove_evictsParticipantFromRoster() {
        ParticipantManager participantManager = new ParticipantManager();
        participantManager.putParticipant("peer-1", "Peer One");

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(participantManager.getParticipants()).hasSize(1));

        participantManager.remove("peer-1");

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(participantManager.get("peer-1")).isNull();
            assertThat(participantManager.getParticipants()).isEmpty();
        });
    }
}
