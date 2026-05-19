package com.distrisync.client;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Continuous microphone capture with RMS voice-activity gating and UDP playback
 * for the DistriSync audio data plane. Uses {@link javax.sound.sampled} at {@link #AUDIO_FORMAT}.
 */
public final class AudioEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AudioEngine.class);

    /**
     * Global wire / capture / playback format: 8 kHz, 16-bit signed PCM, mono, big-endian.
     */
    public static final AudioFormat AUDIO_FORMAT =
            new AudioFormat(8000.0f, 16, 1, true, true);

    private static final int UDP_IDENTITY_BYTES = 36;
    /** 10 ms of PCM at {@link #AUDIO_FORMAT}: 8000 Hz × 2 bytes × 0.01 s = 160 bytes. */
    public static final int PAYLOAD_SIZE = 160;
    /**
     * RMS at or below this level (16-bit sample units) is treated as silence and not transmitted.
     */
    public static final double SILENCE_THRESHOLD = 350.0;
    /**
     * javax.sound sampled internal buffer: 10 frames × {@link #PAYLOAD_SIZE} = 1 600 bytes
     * to keep the OS/driver queue shallow (≈100 ms at this frame size).
     */
    private static final int LINE_BUFFER_BYTES = 10 * PAYLOAD_SIZE;
    /** Wire datagram: 36-byte identity + {@link #PAYLOAD_SIZE} audio = 196 bytes. */
    private static final int UDP_PACKET_BYTES = UDP_IDENTITY_BYTES + PAYLOAD_SIZE;
    /** Completes a big-endian 16-bit sample when {@link TargetDataLine#read} returns an odd byte count. */
    private static final byte[] PCM_LOW_SCRATCH = new byte[1];
    private static final int MUTED_CAPTURE_PARK_MS = 10;
    /** Keeps {@link #isSpeaking} true briefly after the last above-threshold frame. */
    private static final long SPEAKING_HOLD_MS = 200L;
    /** Remote peer speaking flag decays after this gap without UDP audio (no stop-talking packet). */
    static final long REMOTE_SPEAKING_DECAY_MS = 250L;
    /** Single daemon polls {@link #remoteLastSpokenAt} at this interval (not per audio frame). */
    static final long REMOTE_SPEAKING_POLL_MS = 50L;

    private final AtomicBoolean micMutedAtomic = new AtomicBoolean(true);
    private final BooleanProperty isMicMuted = new SimpleBooleanProperty(true);
    private final BooleanProperty isDeafened = new SimpleBooleanProperty(false);
    private final BooleanProperty isSpeaking = new SimpleBooleanProperty(false);
    private final ScheduledExecutorService speakingScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "distrisync-speaking-debounce");
                t.setDaemon(true);
                return t;
            });
    private final Object speakingDebounceLock = new Object();
    private ScheduledFuture<?> speakingHoldFuture;

    private final ReentrantLock udpLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /** Capture daemon lifecycle; cleared only by {@link #stopCaptureDaemon()} or {@link #close()}. */
    private final AtomicBoolean captureRunning = new AtomicBoolean(false);
    private final AtomicBoolean receiveLoopStarted = new AtomicBoolean(false);

    private volatile DatagramSocket datagramSocket;
    /** UTF-8 token or identity prefix, always length {@value #UDP_IDENTITY_BYTES}. */
    private volatile byte[] udpIdentityBytes = new byte[UDP_IDENTITY_BYTES];

    private SourceDataLine playbackLine;
    private final Object playbackLock = new Object();

    private volatile TargetDataLine captureLine;
    private volatile Thread captureThread;
    private volatile Thread receiveThread;

    private volatile UserSpeakingListener userSpeakingListener;
    private volatile ParticipantManager participantManager;
    /** Invoked on the calling thread after a local hardware mute toggle (see {@link #toggleMic()}). */
    private volatile Consumer<Boolean> voiceStateSync;

    private final ConcurrentHashMap<String, Long> remoteLastSpokenAt = new ConcurrentHashMap<>();
    private final Set<String> remoteSpeakingMarked = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean remoteSpeakingDecayStarted = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> remoteSpeakingDecayFuture;
    private volatile LongSupplier millisClock = System::currentTimeMillis;

    public void setVoiceStateSync(Consumer<Boolean> sync) {
        this.voiceStateSync = sync;
    }

    /** Last 36-byte relay header (selector of {@link #rxSpeakerIdCached}). */
    private final byte[] rxIdentityMatch = new byte[UDP_IDENTITY_BYTES];
    private boolean rxSpeakerIdCacheValid;
    private String rxSpeakerIdCached = "";

    /**
     * Opens the capture {@link TargetDataLine}; overridden in unit tests to inject a mock line.
     */
    @FunctionalInterface
    interface MicLineFactory {
        TargetDataLine open(AudioFormat format, int bufferSize) throws LineUnavailableException;
    }

    private volatile MicLineFactory micLineFactory = (format, bufferSize) -> {
        TargetDataLine line = AudioSystem.getTargetDataLine(format);
        line.open(format, bufferSize);
        return line;
    };

    public AudioEngine() {
        isDeafened.addListener((obs, was, now) -> {
            if (Boolean.TRUE.equals(now)) {
                flushPlayback();
            }
        });
    }

    public BooleanProperty isMicMutedProperty() {
        return isMicMuted;
    }

    public boolean isMicMuted() {
        return micMutedAtomic.get();
    }

    /** {@code true} after {@link #close()}; the instance must not be reused. */
    public boolean isClosed() {
        return closed.get();
    }

    public void setMicMuted(boolean muted) {
        micMutedAtomic.set(muted);
        syncMicMutedProperty(muted);
    }

    /**
     * Flips the local mute state without closing the capture {@link TargetDataLine}.
     */
    public void toggleMic() {
        setMicMuted(!micMutedAtomic.get());
        Consumer<Boolean> sync = voiceStateSync;
        if (sync != null) {
            sync.accept(micMutedAtomic.get());
        }
    }

    public BooleanProperty isDeafenedProperty() {
        return isDeafened;
    }

    public boolean isDeafened() {
        return isDeafened.get();
    }

    public void setDeafened(boolean deafened) {
        isDeafened.set(deafened);
    }

    public BooleanProperty isSpeakingProperty() {
        return isSpeaking;
    }

    public boolean isSpeaking() {
        return isSpeaking.get();
    }

    public void setUserSpeakingListener(UserSpeakingListener listener) {
        this.userSpeakingListener = listener;
    }

    public void setParticipantManager(ParticipantManager manager) {
        this.participantManager = manager;
    }

    /**
     * RMS of big-endian 16-bit PCM samples in {@code pcm[offset .. offset+length)}.
     */
    public static double computeRmsPcm16Be(byte[] pcm, int offset, int length) {
        if (pcm == null || length < 2) {
            return 0.0;
        }
        int sampleCount = length / 2;
        if (sampleCount == 0) {
            return 0.0;
        }
        long sumSquares = 0L;
        int end = offset + length - 1;
        for (int i = offset; i < end; i += 2) {
            int sample = (short) ((pcm[i] << 8) | (pcm[i + 1] & 0xFF));
            sumSquares += (long) sample * sample;
        }
        return Math.sqrt((double) sumSquares / sampleCount);
    }

    /** Whether a 10 ms PCM frame should be sent (passes voice-activity / noise gate). */
    public static boolean shouldTransmit(byte[] pcm, int offset, int length) {
        return computeRmsPcm16Be(pcm, offset, length) > SILENCE_THRESHOLD;
    }

    /**
     * Stores the admission token, opens a {@link DatagramSocket}, connects it to
     * the server, sends a 36-byte registration datagram (token only), and starts
     * the receive and capture daemons.
     */
    public void onUdpAdmission(String serverHost, int serverUdpPort, String udpToken) throws IOException {
        if (closed.get()) {
            return;
        }
        if (serverHost == null || serverHost.isBlank()) {
            throw new IllegalArgumentException("serverHost must not be blank");
        }
        if (serverUdpPort < 1 || serverUdpPort > 65_535) {
            throw new IllegalArgumentException("Invalid UDP port: " + serverUdpPort);
        }
        if (udpToken == null || udpToken.isBlank()) {
            throw new IllegalArgumentException("udpToken must not be blank");
        }

        byte[] identity = utf8Fixed36(udpToken);
        InetAddress address = InetAddress.getByName(serverHost.strip());

        udpLock.lock();
        try {
            udpIdentityBytes = identity;

            DatagramSocket old = datagramSocket;
            if (old != null && !old.isClosed()) {
                try {
                    old.close();
                } catch (Exception ignored) {
                    /* best-effort */
                }
            }

            DatagramSocket ds = new DatagramSocket();
            ds.connect(address, serverUdpPort);
            datagramSocket = ds;

            DatagramPacket punch = new DatagramPacket(identity, UDP_IDENTITY_BYTES);
            ds.send(punch);
            log.debug("UDP registration punch sent ({} bytes) to {}:{}",
                    UDP_IDENTITY_BYTES, serverHost, serverUdpPort);
        } finally {
            udpLock.unlock();
        }

        ensureReceiveDaemon();
        try {
            startCaptureDaemon();
        } catch (IllegalStateException e) {
            log.warn("Capture daemon not started on UDP admission: {}", e.getMessage());
        }
    }

    /**
     * Starts the permanent UDP receive thread once (no-op if already running).
     * Safe to call from {@link NetworkClient#connect()} before {@code UDP_ADMISSION}.
     */
    public void startReceiveDaemon() {
        ensureReceiveDaemon();
    }

    private void ensureReceiveDaemon() {
        if (!receiveLoopStarted.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(this::receiveLoop, "distrisync-audio-recv");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.setUncaughtExceptionHandler((thread, ex) ->
                log.error("Audio receive thread terminated", ex));
        receiveThread = t;
        t.start();
    }

    /**
     * Opens the microphone once and runs the continuous capture loop until
     * {@link #stopCaptureDaemon()} or {@link #close()}.
     */
    public void startCaptureDaemon() {
        if (closed.get()) {
            throw new IllegalStateException("AudioEngine is closed");
        }
        if (!captureRunning.compareAndSet(false, true)) {
            return;
        }

        DatagramSocket sock;
        udpLock.lock();
        try {
            sock = datagramSocket;
        } finally {
            udpLock.unlock();
        }
        if (sock == null || sock.isClosed() || !sock.isConnected()) {
            captureRunning.set(false);
            throw new IllegalStateException("UDP not admitted — wait for UDP_ADMISSION");
        }

        try {
            TargetDataLine line = micLineFactory.open(AUDIO_FORMAT, LINE_BUFFER_BYTES);
            line.start();
            captureLine = line;
        } catch (LineUnavailableException e) {
            captureRunning.set(false);
            log.warn("Microphone unavailable: {}", e.getMessage());
            throw new IllegalStateException("Microphone unavailable", e);
        }

        Thread cap = new Thread(this::captureLoop, "distrisync-audio-capture");
        cap.setDaemon(true);
        cap.setPriority(Thread.MAX_PRIORITY);
        captureThread = cap;
        cap.start();
    }

    /**
     * Stops the capture loop and releases the {@link TargetDataLine}.
     * Call when leaving a room or on {@link #close()}.
     */
    public void stopCaptureDaemon() {
        captureRunning.set(false);
        TargetDataLine line = captureLine;
        captureLine = null;
        if (line != null) {
            try {
                line.stop();
            } catch (Exception ignored) {
                /* best-effort */
            }
            try {
                line.close();
            } catch (Exception ignored) {
                /* best-effort */
            }
        }
        Thread t = captureThread;
        captureThread = null;
        if (t != null) {
            try {
                t.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        markLocalVoiceActive(false);
    }

    /** True when the capture daemon is running and the mic is not muted. */
    public boolean isRecording() {
        return captureRunning.get() && !micMutedAtomic.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        stopCaptureDaemon();
        cancelSpeakingHold();
        cancelRemoteSpeakingDecay();
        remoteLastSpokenAt.clear();
        remoteSpeakingMarked.clear();
        Platform.runLater(() -> isSpeaking.set(false));
        speakingScheduler.shutdownNow();

        udpLock.lock();
        try {
            DatagramSocket ds = datagramSocket;
            datagramSocket = null;
            if (ds != null && !ds.isClosed()) {
                try {
                    ds.close();
                } catch (Exception ignored) {
                    /* best-effort */
                }
            }
        } finally {
            udpLock.unlock();
        }

        flushPlayback();

        Thread rt = receiveThread;
        if (rt != null) {
            try {
                rt.join(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void captureLoop() {
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);

        byte[] buffer = new byte[UDP_PACKET_BYTES];
        System.arraycopy(udpIdentityBytes, 0, buffer, 0, UDP_IDENTITY_BYTES);
        DatagramPacket packet = new DatagramPacket(buffer, UDP_PACKET_BYTES);

        while (captureRunning.get() && !closed.get()) {
            if (micMutedAtomic.get()) {
                markLocalVoiceActive(false);
                TargetDataLine line = captureLine;
                if (line != null) {
                    Arrays.fill(buffer, (byte) 0);
                    System.arraycopy(udpIdentityBytes, 0, buffer, 0, UDP_IDENTITY_BYTES);
                    flushCaptureLineBuffer(line);
                }
                parkMuted();
                continue;
            }

            TargetDataLine line = captureLine;
            if (line == null) {
                break;
            }

            if (!readPcmFrame(line, buffer, UDP_IDENTITY_BYTES)) {
                break;
            }

            boolean loud = shouldTransmit(buffer, UDP_IDENTITY_BYTES, PAYLOAD_SIZE);
            markLocalVoiceActive(loud);
            if (!loud) {
                continue;
            }

            udpLock.lock();
            try {
                DatagramSocket sock = datagramSocket;
                if (sock == null || sock.isClosed() || !sock.isConnected()) {
                    break;
                }
                packet.setData(buffer, 0, UDP_PACKET_BYTES);
                try {
                    sock.send(packet);
                } catch (IOException e) {
                    if (!closed.get()) {
                        log.debug("UDP audio send failed: {}", e.getMessage());
                    }
                }
            } finally {
                udpLock.unlock();
            }
        }
        captureRunning.set(false);
        markLocalVoiceActive(false);
    }

    /**
     * Drains and flushes the capture line while muted so the driver buffer does not grow without bound.
     */
    private static void flushCaptureLineBuffer(TargetDataLine line) {
        try {
            int avail = line.available();
            if (avail > 0) {
                byte[] drain = new byte[Math.min(avail, LINE_BUFFER_BYTES)];
                line.read(drain, 0, drain.length);
            }
            if (line.isOpen()) {
                line.flush();
            }
        } catch (Exception ignored) {
            /* best-effort */
        }
    }

    /**
     * Updates {@link #isSpeaking} from the capture thread with a short hold to avoid flicker.
     */
    private void markLocalVoiceActive(boolean loud) {
        if (closed.get() || micMutedAtomic.get()) {
            cancelSpeakingHold();
            Platform.runLater(() -> isSpeaking.set(false));
            return;
        }
        if (loud) {
            Platform.runLater(() -> isSpeaking.set(true));
            rescheduleSpeakingHold();
        }
    }

    private void rescheduleSpeakingHold() {
        synchronized (speakingDebounceLock) {
            if (speakingHoldFuture != null) {
                speakingHoldFuture.cancel(false);
            }
            speakingHoldFuture = speakingScheduler.schedule(
                    () -> Platform.runLater(() -> isSpeaking.set(false)),
                    SPEAKING_HOLD_MS,
                    TimeUnit.MILLISECONDS);
        }
    }

    private void cancelSpeakingHold() {
        synchronized (speakingDebounceLock) {
            if (speakingHoldFuture != null) {
                speakingHoldFuture.cancel(false);
                speakingHoldFuture = null;
            }
        }
    }

    /**
     * Fills {@code buffer[pcmOffset .. pcmOffset+PAYLOAD_SIZE)} with one 10 ms frame.
     *
     * @return {@code false} if the line ended or capture was stopped
     */
    private boolean readPcmFrame(TargetDataLine line, byte[] buffer, int pcmOffset) {
        int off = pcmOffset;
        int remaining = PAYLOAD_SIZE;
        int pendingHigh = -1;
        while (remaining > 0 && captureRunning.get() && !closed.get() && !micMutedAtomic.get()) {
            int needBytes = pendingHigh >= 0 ? 1 : remaining;
            while (line.available() < needBytes && captureRunning.get() && !closed.get() && !micMutedAtomic.get()) {
                Thread.yield();
            }
            if (!captureRunning.get() || closed.get() || micMutedAtomic.get()) {
                return false;
            }
            int n;
            try {
                if (pendingHigh >= 0) {
                    n = line.read(PCM_LOW_SCRATCH, 0, 1);
                    if (n < 0) {
                        return false;
                    }
                    if (n == 0) {
                        continue;
                    }
                    buffer[off] = (byte) pendingHigh;
                    buffer[off + 1] = PCM_LOW_SCRATCH[0];
                    pendingHigh = -1;
                    off += 2;
                    remaining -= 2;
                    continue;
                }
                n = line.read(buffer, off, remaining);
            } catch (Exception e) {
                log.debug("Mic read ended: {}", e.getMessage());
                return false;
            }
            if (n < 0) {
                return false;
            }
            if (n == 0) {
                continue;
            }
            if ((n & 1) != 0) {
                pendingHigh = buffer[off + n - 1] & 0xFF;
                n--;
            }
            if (n == 0) {
                continue;
            }
            off += n;
            remaining -= n;
        }
        return remaining == 0;
    }

    private void receiveLoop() {
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);

        byte[] buf = new byte[UDP_PACKET_BYTES];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);

        while (!closed.get()) {
            DatagramSocket sock;
            udpLock.lock();
            try {
                sock = datagramSocket;
            } finally {
                udpLock.unlock();
            }

            if (sock == null || sock.isClosed()) {
                parkBriefly();
                continue;
            }

            try {
                sock.receive(pkt);
                handleInboundRemoteAudio(pkt.getData(), pkt.getOffset(), pkt.getLength());
            } catch (SocketException e) {
                if (closed.get()) {
                    break;
                }
                parkBriefly();
            } catch (IOException e) {
                if (!closed.get()) {
                    log.debug("UDP audio receive error: {}", e.getMessage());
                }
                parkBriefly();
            }
        }
        log.info("Audio receive loop exited");
    }

    private void handleInboundRemoteAudio(byte[] pktBuf, int pktOff, int len) {
        if (len < UDP_IDENTITY_BYTES + PAYLOAD_SIZE) {
            return;
        }

        String speakerId = decodeUtf8Identity36Cached(pktBuf, pktOff);
        noteRemoteSpeech(speakerId);

        if (isDeafened.get()) {
            return;
        }

        try {
            ensurePlaybackOpen();
        } catch (LineUnavailableException e) {
            log.warn("Playback unavailable: {}", e.getMessage());
            return;
        }

        SourceDataLine line;
        synchronized (playbackLock) {
            line = playbackLine;
        }
        if (line != null) {
            line.write(pktBuf, pktOff + UDP_IDENTITY_BYTES, PAYLOAD_SIZE);
        }

        UserSpeakingListener listener = userSpeakingListener;
        if (listener != null) {
            listener.onUserSpeaking(speakerId);
        }
    }

    private void noteRemoteSpeech(String speakerId) {
        if (speakerId == null || speakerId.isBlank()) {
            return;
        }
        ensureRemoteSpeakingDecay();
        long now = millisClock.getAsLong();
        remoteLastSpokenAt.put(speakerId, now);
        if (remoteSpeakingMarked.add(speakerId)) {
            ParticipantManager manager = participantManager;
            if (manager != null) {
                manager.setSpeaking(speakerId, true);
            }
        }
    }

    private void ensureRemoteSpeakingDecay() {
        if (!remoteSpeakingDecayStarted.compareAndSet(false, true)) {
            return;
        }
        remoteSpeakingDecayFuture = speakingScheduler.scheduleAtFixedRate(
                this::expireRemoteSpeakers,
                REMOTE_SPEAKING_POLL_MS,
                REMOTE_SPEAKING_POLL_MS,
                TimeUnit.MILLISECONDS);
    }

    private void expireRemoteSpeakers() {
        long now = millisClock.getAsLong();
        boolean flushedPlayback = false;
        for (Map.Entry<String, Long> entry : remoteLastSpokenAt.entrySet()) {
            String speakerId = entry.getKey();
            Long lastAt = entry.getValue();
            if (lastAt == null || now - lastAt <= REMOTE_SPEAKING_DECAY_MS) {
                continue;
            }
            remoteLastSpokenAt.remove(speakerId, lastAt);
            if (remoteSpeakingMarked.remove(speakerId)) {
                ParticipantManager manager = participantManager;
                if (manager != null) {
                    manager.setSpeaking(speakerId, false);
                }
                if (!flushedPlayback) {
                    flushPlaybackBuffer();
                    flushedPlayback = true;
                }
            }
        }
    }

    private void cancelRemoteSpeakingDecay() {
        ScheduledFuture<?> future = remoteSpeakingDecayFuture;
        remoteSpeakingDecayFuture = null;
        if (future != null) {
            future.cancel(false);
        }
        remoteSpeakingDecayStarted.set(false);
    }

    private void ensurePlaybackOpen() throws LineUnavailableException {
        synchronized (playbackLock) {
            if (isDeafened.get()) {
                return;
            }
            if (playbackLine != null && playbackLine.isOpen()) {
                return;
            }
            SourceDataLine line = AudioSystem.getSourceDataLine(AUDIO_FORMAT);
            line.open(AUDIO_FORMAT, LINE_BUFFER_BYTES);
            line.start();
            playbackLine = line;
        }
    }

    /** Drops queued PCM on the shared playback line without closing it. */
    private void flushPlaybackBuffer() {
        synchronized (playbackLock) {
            SourceDataLine line = playbackLine;
            if (line != null && line.isOpen()) {
                try {
                    line.flush();
                } catch (Exception ignored) {
                    /* best-effort */
                }
            }
        }
    }

    private void flushPlayback() {
        synchronized (playbackLock) {
            SourceDataLine line = playbackLine;
            playbackLine = null;
            if (line == null) {
                return;
            }
            try {
                if (line.isOpen()) {
                    line.flush();
                }
            } catch (Exception ignored) {
                /* best-effort */
            }
            try {
                line.stop();
            } catch (Exception ignored) {
                /* best-effort */
            }
            try {
                line.close();
            } catch (Exception ignored) {
                /* best-effort */
            }
        }
    }

    private void syncMicMutedProperty(boolean muted) {
        if (Platform.isFxApplicationThread()) {
            isMicMuted.set(muted);
        } else {
            Platform.runLater(() -> isMicMuted.set(muted));
        }
    }

    private static void parkMuted() {
        try {
            Thread.sleep(MUTED_CAPTURE_PARK_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void parkBriefly() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] utf8Fixed36(String s) {
        byte[] raw = (s != null ? s : "").getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[UDP_IDENTITY_BYTES];
        int n = Math.min(UDP_IDENTITY_BYTES, raw.length);
        System.arraycopy(raw, 0, out, 0, n);
        return out;
    }

    private String decodeUtf8Identity36Cached(byte[] buf, int identityOffset) {
        if (rxSpeakerIdCacheValid
                && Arrays.equals(buf, identityOffset, identityOffset + UDP_IDENTITY_BYTES,
                rxIdentityMatch, 0, UDP_IDENTITY_BYTES)) {
            return rxSpeakerIdCached;
        }
        System.arraycopy(buf, identityOffset, rxIdentityMatch, 0, UDP_IDENTITY_BYTES);
        rxSpeakerIdCached = decodeUtf8Identity36(buf, identityOffset);
        rxSpeakerIdCacheValid = true;
        return rxSpeakerIdCached;
    }

    private static String decodeUtf8Identity36(byte[] buf, int identityOffset) {
        int end = identityOffset;
        int limit = identityOffset + UDP_IDENTITY_BYTES;
        while (end < limit && buf[end] != 0) {
            end++;
        }
        return new String(buf, identityOffset, end - identityOffset, StandardCharsets.UTF_8);
    }

    /** Same-package unit tests inject a mock {@link TargetDataLine}. */
    void setMicLineFactoryForTests(MicLineFactory factory) {
        this.micLineFactory = factory;
    }

    /** Same-package unit tests inject a mock {@link SourceDataLine} for playback flush assertions. */
    void setPlaybackLineForTests(SourceDataLine line) {
        synchronized (playbackLock) {
            playbackLine = line;
        }
    }

    void setMillisClockForTests(LongSupplier clock) {
        this.millisClock = clock != null ? clock : System::currentTimeMillis;
    }

    /** Feeds one inbound wire datagram through the UDP receive path (package-private for tests). */
    void ingestRemoteDatagramForTest(byte[] packet, int offset) {
        if (packet == null) {
            throw new IllegalArgumentException("packet must not be null");
        }
        handleInboundRemoteAudio(packet, offset, packet.length - offset);
    }

    /** Runs one decay sweep synchronously (package-private for tests with a fake clock). */
    void runRemoteSpeakingDecayForTest() {
        expireRemoteSpeakers();
    }

}
