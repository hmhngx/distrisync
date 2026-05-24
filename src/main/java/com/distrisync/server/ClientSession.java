package com.distrisync.server;

import com.distrisync.protocol.RoomPermissions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * Mutable per-connection state attached to a {@link java.nio.channels.SelectionKey}.
 *
 * <h2>Read buffer</h2>
 * A single 64 KiB {@link ByteBuffer} used for accumulating incoming TCP bytes.
 * The NIO event loop appends newly received bytes, then flips the buffer to
 * decode complete frames in a loop, and finally compacts it to retain any
 * partial tail for the next read.
 *
 * <h2>Write queue</h2>
 * A bounded {@link ArrayDeque} of outbound {@link ByteBuffer}s (max
 * {@link #WRITE_QUEUE_CAPACITY} frames). Each enqueued buffer is a self-contained
 * copy with its own position so that partial writes are correctly resumed on the
 * next {@code OP_WRITE} wake-up. When the queue is empty the corresponding
 * {@code OP_WRITE} interest bit is cleared from the key.
 */
final class ClientSession {

    private static final Logger log = LoggerFactory.getLogger(ClientSession.class);

    /** Maximum outbound frames queued per TCP session before load shedding. */
    static final int WRITE_QUEUE_CAPACITY = 1024;

    /** Max burst of UDP audio relay packets per session. */
    static final int UDP_MAX_TOKENS = 50;

    /** Milliseconds per refilled UDP relay token (50 tokens/sec sustained). */
    static final int UDP_REFILL_RATE_MS = 20;

    /** Stable server-assigned identity used in logs. */
    final UUID sessionId = UUID.randomUUID();

    /**
     * Human-readable display name supplied by the client in its {@code HANDSHAKE}
     * frame.  Defaults to an empty string for sessions that have not yet completed
     * their handshake.  Written only once (on HANDSHAKE receipt); reads are safe
     * from the single-threaded NIO event loop.
     */
    volatile String authorName = "";

    /**
     * Session-scoped client identifier supplied in the {@code HANDSHAKE} frame.
     * Correlates shapes to the originating client session across the board state.
     */
    volatile String clientId = "";

    /**
     * Canvas room id after a successful {@code JOIN_ROOM}; empty while the
     * client is in the discovery lobby.
     */
    volatile String roomId = "";

    /**
     * Active workspace board within {@link #roomId}, set on {@code JOIN_ROOM}
     * (default {@value com.distrisync.protocol.MessageCodec#DEFAULT_INITIAL_BOARD_ID})
     * or {@code SWITCH_BOARD}; empty in the lobby.
     */
    volatile String currentBoardId = "";

    /**
     * Set after the first {@code HANDSHAKE} is accepted; duplicate handshakes are ignored.
     */
    volatile boolean handshakeComplete = false;

    /**
     * Set when the server has decided to terminate this TCP session; stops further inbound
     * frame processing in {@link NioServer#handleRead}.
     */
    volatile boolean severed = false;

    /**
     * Bitmask of {@link RoomPermissions} granted on {@code JOIN_ROOM}; {@link RoomPermissions#SPECTATOR}
     * while in the lobby.
     */
    volatile int permissions = RoomPermissions.SPECTATOR;

    /**
     * Wall-clock millis when the first {@code HANDSHAKE} was accepted; {@code 0} before handshake.
     */
    volatile long connectedAtMillis = 0L;

    /**
     * Last-reported hardware mute from {@code VOICE_STATE}; default {@code true} until the client
     * sends an update. Used when hydrating late joiners.
     */
    volatile boolean micMuted = true;

    /**
     * Opaque token for the UDP audio data plane; issued on successful {@code JOIN_ROOM}.
     */
    volatile String udpToken = "";

    /**
     * Client UDP endpoint after a 36-byte token registration datagram; {@code null} until registered.
     */
    volatile InetSocketAddress udpEndpoint = null;

    private int udpTokens = UDP_MAX_TOKENS;
    private long lastUdpTokenRefill = System.currentTimeMillis();

    /**
     * Accumulation buffer for inbound bytes.
     * 64 KiB covers any realistic single MUTATION frame; the server never
     * receives SNAPSHOT messages (only sends them).
     */
    final ByteBuffer readBuffer = ByteBuffer.allocate(64 * 1024);

    /**
     * FIFO queue of outbound frames waiting to be drained to the socket.
     * Buffers are stored in write-ready state (position = next byte to send,
     * limit = end of data).
     */
    final Deque<ByteBuffer> writeQueue = new ArrayDeque<>(WRITE_QUEUE_CAPACITY);

    /**
     * Enqueues a frame for delivery to this client.
     *
     * <p>The caller must pass a buffer that is ready to read (position at start,
     * limit at end of data). A fresh copy is made so the caller's buffer is
     * not consumed and can safely be passed to multiple sessions.
     *
     * @param frame the frame to enqueue; its bytes are copied into a new buffer
     * @param cls   shedding policy when the queue is at capacity
     * @return {@link EnqueueResult#ENQUEUED}, {@link EnqueueResult#DROPPED}, or
     *         {@link EnqueueResult#OVERFLOW_DISCONNECT}
     */
    /**
     * Attempts to consume one UDP audio relay token after refilling based on elapsed time.
     * Called from the selector thread before room fan-out in {@link NioServer}.
     *
     * @return {@code true} if a token was available and consumed; {@code false} to drop the packet
     */
    boolean consumeUdpToken() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastUdpTokenRefill;
        if (elapsed > 0) {
            int refill = (int) (elapsed / UDP_REFILL_RATE_MS);
            if (refill > 0) {
                udpTokens = Math.min(UDP_MAX_TOKENS, udpTokens + refill);
                lastUdpTokenRefill += (long) refill * UDP_REFILL_RATE_MS;
            }
        }
        if (udpTokens > 0) {
            udpTokens--;
            return true;
        }
        return false;
    }

    EnqueueResult enqueue(ByteBuffer frame, OutboundClass cls) {
        if (writeQueue.size() >= WRITE_QUEUE_CAPACITY) {
            if (cls == OutboundClass.EPHEMERAL) {
                ServerMetrics.FRAMES_DROPPED_TOTAL.incrementAndGet();
                log.trace("Dropping ephemeral outbound frame session={} queueDepth={}",
                        sessionId, writeQueue.size());
                return EnqueueResult.DROPPED;
            }
            return EnqueueResult.OVERFLOW_DISCONNECT;
        }
        // duplicate() creates an independent view with its own position/limit so
        // that advancing the copy's position does not consume the caller's buffer.
        // This lets broadcastExcept() safely pass the same frame to N sessions.
        ByteBuffer copy = ByteBuffer.allocate(frame.remaining());
        copy.put(frame.duplicate());
        copy.flip();
        writeQueue.addLast(copy);
        return EnqueueResult.ENQUEUED;
    }

    @Override
    public String toString() {
        return "ClientSession[" + sessionId + "]";
    }
}
