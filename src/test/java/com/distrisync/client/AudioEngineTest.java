package com.distrisync.client;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Wire-format and sizing invariants for {@link AudioEngine} (no audio hardware,
 * sockets, or threads).
 */
@DisplayName("AudioEngine")
@ExtendWith(MockitoExtension.class)
class AudioEngineTest {

    @BeforeAll
    static void initJavaFx() {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            /* toolkit already running */
        }
    }

    @Test
    @DisplayName("10 ms frame at 8 kHz, 16-bit mono is exactly 160 bytes")
    void testAudioFrameCalculations() {
        AudioFormat format = AudioEngine.AUDIO_FORMAT;

        assertThat(format.getSampleRate()).isEqualTo(8000.0f);
        assertThat(format.getSampleSizeInBits()).isEqualTo(16);
        assertThat(format.getChannels()).isEqualTo(1);

        int sampleRate = Math.round(format.getSampleRate());
        int bytesPerSample = format.getSampleSizeInBits() / 8;
        int channels = format.getChannels();
        assertThat(format.getFrameSize()).isEqualTo(bytesPerSample * channels);

        final int frameDurationMs = 10;
        // bytes = sampleRate * (ms/1000) * bytesPerSample * channels  (exact integers here)
        long bytesPer10ms =
                (long) sampleRate * frameDurationMs / 1000L * bytesPerSample * channels;

        assertThat(bytesPer10ms).isEqualTo(160L);
        assertThat(AudioEngine.PAYLOAD_SIZE).isEqualTo((int) bytesPer10ms);
    }

    /**
     * Guards micro-framing and UDP capture/receive buffer sizing: {@link AudioEngine} allocates
     * {@code new byte[UDP_PACKET_BYTES]} as 36-byte identity + {@link AudioEngine#PAYLOAD_SIZE} PCM.
     */
    @Test
    @DisplayName("Micro-frame latency math: 10 ms PCM = 160 B; wire datagram allocation = 196 B")
    void testMicroFrameLatencyMath() {
        AudioFormat format = AudioEngine.AUDIO_FORMAT;

        assertThat(format.getSampleRate()).isEqualTo(8000.0f);
        assertThat(format.getSampleSizeInBits()).isEqualTo(16);
        assertThat(format.getChannels()).isEqualTo(1);

        final double microFrameSeconds = 0.01d;
        long samplesInMicroFrame = Math.round(format.getSampleRate() * microFrameSeconds);
        long pcmBytesFor10ms = samplesInMicroFrame
                * (format.getSampleSizeInBits() / 8L)
                * format.getChannels();

        assertThat(pcmBytesFor10ms)
                .as("PCM bytes for exactly %.2f s at sample rate %s Hz, %d-bit, %d channel(s)",
                        microFrameSeconds, format.getSampleRate(), format.getSampleSizeInBits(), format.getChannels())
                .isEqualTo(160L);
        assertThat(AudioEngine.PAYLOAD_SIZE)
                .as("PAYLOAD_SIZE must equal the 10 ms micro-frame on the wire")
                .isEqualTo((int) pcmBytesFor10ms);

        // Must match AudioEngine UDP_IDENTITY_BYTES — UUID UTF-8 admission token on the wire.
        final int uuidTokenWireBytes = 36;
        int totalUdpPacketBytes = uuidTokenWireBytes + AudioEngine.PAYLOAD_SIZE;
        assertThat(totalUdpPacketBytes)
                .as("UDP datagram buffer: %d-byte identity + %d-byte PCM payload",
                        uuidTokenWireBytes, AudioEngine.PAYLOAD_SIZE)
                .isEqualTo(196);
    }

    @Test
    @DisplayName("RMS gate drops silence and passes loud PCM frames")
    void testAudioEngineDropsSilentPackets() {
        byte[] silence = new byte[AudioEngine.PAYLOAD_SIZE];
        assertThat(AudioEngine.computeRmsPcm16Be(silence, 0, silence.length)).isEqualTo(0.0);
        assertThat(AudioEngine.shouldTransmit(silence, 0, silence.length)).isFalse();

        byte[] loud = new byte[AudioEngine.PAYLOAD_SIZE];
        for (int i = 0; i < loud.length; i += 2) {
            loud[i] = (byte) 0x5F;
            loud[i + 1] = (byte) 0xFF;
        }
        assertThat(AudioEngine.computeRmsPcm16Be(loud, 0, loud.length))
                .isGreaterThan(AudioEngine.SILENCE_THRESHOLD);
        assertThat(AudioEngine.shouldTransmit(loud, 0, loud.length)).isTrue();
    }

    @Test
    @DisplayName("toggleMic flips mute without closing hardware; close() releases the line")
    void testAudioEngineToggleMuteHardwareLifecycle() throws Exception {
        TargetDataLine mockLine = mock(TargetDataLine.class);
        when(mockLine.isOpen()).thenReturn(true);
        when(mockLine.available()).thenReturn(0);

        try (DatagramSocket sink = new DatagramSocket(0)) {
            AudioEngine engine = new AudioEngine();
            engine.setMicLineFactoryForTests((format, bufferSize) -> mockLine);

            String token = "01234567-89ab-cdef-0123-456789abcdef";
            engine.onUdpAdmission("127.0.0.1", sink.getLocalPort(), token);

            await().atMost(2, SECONDS).untilAsserted(() -> verify(mockLine).start());

            assertThat(engine.isMicMuted()).isTrue();
            await().atMost(1, SECONDS).untilAsserted(() ->
                    assertThat(engine.isMicMutedProperty().get()).isTrue());

            engine.toggleMic();
            assertThat(engine.isMicMuted()).isFalse();
            await().atMost(1, SECONDS).untilAsserted(() ->
                    assertThat(engine.isMicMutedProperty().get()).isFalse());
            verify(mockLine, never()).close();

            engine.toggleMic();
            assertThat(engine.isMicMuted()).isTrue();
            await().atMost(1, SECONDS).untilAsserted(() ->
                    assertThat(engine.isMicMutedProperty().get()).isTrue());
            verify(mockLine, atLeastOnce()).flush();
            verify(mockLine, never()).close();

            engine.close();
            verify(mockLine, atLeastOnce()).close();
        }
    }

    @Test
    @DisplayName("remote speaking decay flushes shared playback buffer")
    void testRemoteSpeakingDecayFlushesPlaybackBuffer() {
        AtomicLong clock = new AtomicLong(1_000L);
        ParticipantManager participantManager = new ParticipantManager();
        participantManager.putParticipant("sender-1", "Peer One");

        SourceDataLine mockPlayback = mock(SourceDataLine.class);
        when(mockPlayback.isOpen()).thenReturn(true);

        AudioEngine engine = new AudioEngine();
        engine.setMillisClockForTests(clock::get);
        engine.setParticipantManager(participantManager);
        engine.setDeafened(false);
        engine.setPlaybackLineForTests(mockPlayback);

        engine.ingestRemoteDatagramForTest(remoteAudioDatagram("sender-1"), 0);

        await().atMost(2, SECONDS).untilAsserted(() ->
                assertThat(participantManager.get("sender-1").isSpeakingProperty().get()).isTrue());

        clock.addAndGet(300);
        engine.runRemoteSpeakingDecayForTest();

        await().atMost(2, SECONDS).untilAsserted(() ->
                assertThat(participantManager.get("sender-1").isSpeakingProperty().get()).isFalse());
        verify(mockPlayback).flush();
    }

    @Test
    @DisplayName("UDP receive sets peer isSpeaking and 250ms decay clears it")
    void testAudioEngineTriggersSpeakingState() {
        AtomicLong clock = new AtomicLong(1_000L);
        ParticipantManager participantManager = new ParticipantManager();
        participantManager.putParticipant("sender-1", "Peer One");

        AudioEngine engine = new AudioEngine();
        engine.setMillisClockForTests(clock::get);
        engine.setParticipantManager(participantManager);
        engine.setDeafened(true);

        byte[] packet = remoteAudioDatagram("sender-1");
        engine.ingestRemoteDatagramForTest(packet, 0);

        await().atMost(2, SECONDS).untilAsserted(() -> {
            Participant peer = participantManager.get("sender-1");
            assertThat(peer).isNotNull();
            assertThat(peer.isSpeakingProperty().get()).isTrue();
        });

        clock.addAndGet(300);
        engine.runRemoteSpeakingDecayForTest();

        await().atMost(2, SECONDS).untilAsserted(() ->
                assertThat(participantManager.get("sender-1").isSpeakingProperty().get()).isFalse());
    }

    private static byte[] remoteAudioDatagram(String clientId) {
        byte[] packet = new byte[196];
        byte[] identity = clientId.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(identity, 0, packet, 0, Math.min(identity.length, 36));
        Arrays.fill(packet, 36, packet.length, (byte) 0x01);
        return packet;
    }
}
