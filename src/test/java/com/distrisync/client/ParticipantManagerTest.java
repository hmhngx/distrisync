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
}
