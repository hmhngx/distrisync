package com.distrisync.client;

import com.distrisync.model.ArrowNode;
import com.distrisync.model.Circle;
import com.distrisync.model.EllipseNode;
import com.distrisync.model.EraserPath;
import com.distrisync.model.Line;
import com.distrisync.model.RectangleNode;
import com.distrisync.model.Shape;
import com.distrisync.model.TextNode;
import com.distrisync.protocol.Message;
import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.MessageCodec.TextUpdatePayload;
import com.distrisync.protocol.MessageType;
import com.distrisync.protocol.PartialMessageException;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleLongProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * TCP network layer for the DistriSync collaborative whiteboard client.
 *
 * <h2>Thread model</h2>
 * <ul>
 *   <li><b>distrisync-read</b> — a blocking thread that calls
 *       {@link SocketChannel#read} in a tight loop, accumulates raw bytes,
 *       decodes complete {@link Message} frames, and dispatches them to all
 *       registered {@link CanvasUpdateListener}s.</li>
 *   <li><b>distrisync-write</b> — a dedicated thread that drains the
 *       {@link ConcurrentLinkedQueue} of encoded outgoing {@code MUTATION}
 *       frames.  It parks itself when the queue is empty and is woken by
 *       {@link LockSupport#unpark} from {@link #sendMutation}.</li>
 * </ul>
 *
 * <h2>Reconnection backoff</h2>
 * On EOF or any I/O error the read thread invokes the reconnect strategy:
 * up to {@value #MAX_RECONNECT_ATTEMPTS} attempts, each separated by
 * {@value #RECONNECT_DELAY_MS} ms.  After exhausting all attempts a
 * {@link RuntimeException} is thrown from the read thread and the client
 * transitions to a permanently-stopped state.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * NetworkClient client = new NetworkClient("localhost", 9090);
 * client.addListener(myCanvasListener);
 * client.addLobbyListener(myLobbyListener);
 * client.connect();                        // handshake → lobby; LOBBY_STATE pushed by server
 * client.sendJoinRoom("MyRoom", "Alice");  // async; SNAPSHOT follows when joined
 * client.sendMutation(someShape);         // enqueued; async dispatch
 * client.close();                          // graceful shutdown
 * }</pre>
 */
public final class NetworkClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NetworkClient.class);

    /** Delay between successive reconnect attempts. */
    private static final int RECONNECT_DELAY_MS = 2_000;

    /** Maximum number of reconnect attempts before the client gives up. */
    private static final int MAX_RECONNECT_ATTEMPTS = 5;

    /**
     * Max shapes per {@link MessageType#MUTATION_BATCH} frame so payloads stay
     * within the server's 64 KiB read accumulator.
     */
    private static final int MUTATION_BATCH_MAX_SHAPES = 20;

    /** Max UTF-8 payload bytes per {@code MUTATION_BATCH} frame (below 64 KiB). */
    private static final int MUTATION_BATCH_MAX_PAYLOAD_BYTES = 48_000;

    /**
     * Read accumulation buffer capacity per channel.
     *
     * Must be able to hold the largest single frame in one contiguous buffer.
     * Snapshots for rooms with many persisted shapes can exceed
     * {@link MessageCodec#LEGACY_CLIENT_READ_BUFFER_BYTES}, so use protocol max
     * payload + header to avoid permanent partial-frame stalls.
     */
    private static final int READ_BUFFER_CAPACITY =
            MessageCodec.MAX_PAYLOAD_BYTES + MessageCodec.HEADER_BYTES;

    /** Max idle park duration for the write thread (5 ms). */
    private static final long WRITE_PARK_NANOS = 5_000_000L;

    /** Extended park duration after a transient write failure (500 ms). */
    private static final long WRITE_ERROR_PARK_NANOS = 500_000_000L;

    // =========================================================================
    // State
    // =========================================================================

    private final String host;
    private final int    port;
    private final String clientId;

    /** Room-level display identity; set on {@link #sendJoinRoom}, cleared on {@link #sendLeaveRoom}. */
    private volatile String displayName = "";

    /**
     * Server-confirmed room display name from a self {@code JOIN_ROOM} notification;
     * used for reconnect {@code JOIN_ROOM} payloads and outbound canvas identity.
     */
    private volatile String lockedRoomName = null;

    /**
     * Room id used for {@code JOIN_ROOM} on reconnect. Empty while the client
     * is in the lobby (after handshake / {@code LEAVE_ROOM}).
     */
    private volatile String activeRoomId = "";

    /**
     * Active workspace board id while in a room; empty in the lobby. Updated when
     * switching boards and when a {@code SNAPSHOT} arrives (aligned with
     * {@link #lastSnapshotBoardId}).
     */
    private volatile String currentBoardId = "";

    /**
     * Active board ids for the current room, kept in sync with the server's
     * {@link MessageType#BOARD_LIST_UPDATE} payloads (authoritative). Also primed locally
     * on {@link #sendJoinRoom} / {@link #sendSwitchBoard} until the next server update.
     */
    private final List<String> knownBoards = new CopyOnWriteArrayList<>();

    /**
     * Board id the server will use for the next {@code SNAPSHOT} — set on
     * {@code JOIN_ROOM} / {@code SWITCH_BOARD} and applied when the snapshot is decoded.
     */
    private volatile String lastSnapshotBoardId = "";

    /**
     * True between an empty hydration {@code SNAPSHOT} {@code []} and {@code SNAPSHOT_END};
     * {@code MUTATION_BATCH} frames are buffered instead of applied as live peer mutations.
     */
    private boolean snapshotHydrating;

    /** Shapes accumulated during chunked snapshot hydration (read thread only). */
    private final List<Shape> snapshotHydrationBuffer = new ArrayList<>();

    /** Thread-safe listener registry; copy-on-write for lock-free iteration. */
    private final CopyOnWriteArrayList<CanvasUpdateListener> listeners =
            new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<LobbyUpdateListener> lobbyListeners =
            new CopyOnWriteArrayList<>();

    /**
     * Invoked on the read thread when {@link MessageType#ROOM_DELETED} is received
     * (server destroyed the room). UI should use {@link javafx.application.Platform#runLater}.
     */
    private final CopyOnWriteArrayList<Runnable> roomDeletedListeners = new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<VoiceStateListener> voiceStateListeners =
            new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<CursorSyncListener> cursorSyncListeners =
            new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<SessionRevokedListener> sessionRevokedListeners =
            new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<RoleUpdateListener> roleUpdateListeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<RoomMembershipListener> roomMembershipListeners =
            new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<BoardPresenceListener> boardPresenceListeners =
            new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<BoardDeletedListener> boardDeletedListeners =
            new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<MediaStateListener> mediaStateListeners =
            new CopyOnWriteArrayList<>();

    private final RoomState roomState = new RoomState();

    /**
     * When {@code false}, {@link #handleDisconnect} does not run the reconnect loop
     * (e.g. after {@link MessageType#SESSION_REVOKED}).
     */
    private final AtomicBoolean autoReconnectEnabled = new AtomicBoolean(true);

    /** Minimum interval between {@link #sendCursorSync} frames (~15 Hz). */
    private static final long CURSOR_SYNC_MIN_INTERVAL_MS = 66;

    private volatile long lastCursorSendMs;

    /** Opaque UDP relay token from the latest {@link MessageType#UDP_ADMISSION}; empty until admitted. */
    private volatile String udpToken = "";

    private volatile AudioEngine audioEngine = new AudioEngine();

    private final ParticipantManager participantManager = new ParticipantManager();

    /**
     * Outgoing MUTATION frames awaiting dispatch.  Produced by any thread via
     * {@link #sendMutation}; consumed exclusively by the write thread.
     */
    private final ConcurrentLinkedQueue<ByteBuffer> writeQueue =
            new ConcurrentLinkedQueue<>();

    /** Guards against double-connect and allows graceful shutdown signalling. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Interval between {@code PING} heartbeats on the control channel. */
    private static final int HEARTBEAT_PING_INTERVAL_MS = 5_000;

    private static final int PING_ROLLING_WINDOW = 10;

    private final SimpleBooleanProperty tcpConnected = new SimpleBooleanProperty(false);
    private final SimpleBooleanProperty udpActive = new SimpleBooleanProperty(false);
    /** Rolling average RTT in ms (last up to {@value #PING_ROLLING_WINDOW} PONGs), or {@code -1} before first PONG. */
    private final SimpleLongProperty ping = new SimpleLongProperty(-1L);
    private final long[] pingSamples = new long[PING_ROLLING_WINDOW];
    private int pingSampleCount;
    private int pingSampleWriteIndex;

    private volatile Thread pingThread;

    /**
     * Set after the first post-handshake server message ({@code LOBBY_STATE} or
     * {@code SNAPSHOT}). {@code JOIN_ROOM} must not hit the wire before then or
     * the server may still be processing {@code HANDSHAKE} and will drop the join.
     */
    private final AtomicBoolean protocolReady = new AtomicBoolean(false);

    /**
     * If {@link #sendJoinRoom} runs before {@link #protocolReady}, the room id is
     * held here and sent when {@link #noteProtocolReady} runs.
     */
    private volatile String deferredJoinRoomId = "";

    /**
     * When non-blank, deferred join uses {@link MessageCodec#encodeJoinRoom(String, String, String)}.
     * Blank means {@link MessageCodec#encodeJoinRoom(String, String)} (server default board).
     */
    private volatile String deferredJoinBoardId = "";

    /** Display name held for a deferred {@link #sendJoinRoom} until {@link #protocolReady}. */
    private volatile String deferredJoinDisplayName = "";

    /**
     * Reference to the read daemon thread so {@link #resumeAfterSessionRevoked}
     * can restart it after proactive kick teardown.
     */
    private volatile Thread readThread;

    /**
     * Reference to the write daemon thread so that producers can
     * {@link LockSupport#unpark} it when new work is enqueued.
     */
    private volatile Thread writeThread;

    /**
     * The live {@link SocketChannel}; replaced atomically on every successful
     * reconnect.  Declared {@code volatile} so the write thread always sees the
     * most recent value without acquiring a lock.
     */
    private volatile SocketChannel channel;

    /**
     * Serialises every outbound {@link SocketChannel#write} so handshake / reconnect
     * and the async write thread cannot interleave partial frames on the wire.
     */
    private final Object writeLock = new Object();

    // =========================================================================
    // Construction
    // =========================================================================

    /**
     * Creates an anonymous {@code NetworkClient} targeting the given server endpoint.
     * A random {@code clientId} is generated. Call {@link #connect()} for the lobby,
     * then {@link #sendJoinRoom(String, String)} with a display name.
     *
     * @param host server host name or IP; must not be blank
     * @param port server TCP port; must be in [1, 65535]
     */
    public NetworkClient(String host, int port) {
        this(host, port, java.util.UUID.randomUUID().toString());
    }

    /**
     * Creates a {@code NetworkClient} targeting the given server endpoint.
     * Only {@code clientId} is sent in the {@code HANDSHAKE}; display identity is
     * declared per room via {@link #sendJoinRoom(String, String)}.
     *
     * @param host     server host name or IP; must not be blank
     * @param port     server TCP port; must be in [1, 65535]
     * @param clientId stable session identifier; may be empty but not {@code null}
     */
    public NetworkClient(String host, int port, String clientId) {
        if (host == null || host.isBlank())
            throw new IllegalArgumentException("host must not be blank");
        if (port < 1 || port > 65_535)
            throw new IllegalArgumentException("Invalid port: " + port);
        this.host       = host;
        this.port       = port;
        this.clientId   = clientId   != null ? clientId   : "";
        audioEngine.setVoiceStateSync(this::sendVoiceState);
        audioEngine.setParticipantManager(participantManager);
        addVoiceStateListener(participantManager);
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Establishes the initial TCP connection using the reconnect backoff
     * strategy, sends the {@code HANDSHAKE} frame, and starts the read and
     * write daemon threads. The server places the client in the lobby and may
     * push {@code LOBBY_STATE}; call {@link #sendJoinRoom(String, String)} to enter a room
     * and receive {@code SNAPSHOT}.
     *
     * <p>This method is idempotent only for the first call; subsequent calls
     * throw {@link IllegalStateException}.
     *
     * @throws IOException          if all {@value #MAX_RECONNECT_ATTEMPTS}
     *                              connection attempts fail
     * @throws IllegalStateException if already connected
     */
    public void connect() throws IOException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("NetworkClient is already connected");
        }
        try {
            protocolReady.set(false);
            deferredJoinRoomId = "";
            deferredJoinBoardId = "";
            resetWorkspaceForLobby();
            channel = connectWithBackoff();
            sendHandshake();
            startWriteThread();
            startReadThread();
            audioEngine.startReceiveDaemon();
            publishTcpConnected(true);
            startHeartbeatPingThread();
        } catch (IOException e) {
            running.set(false);
            SocketChannel ch = channel;
            channel = null;
            if (ch != null) {
                try {
                    ch.close();
                } catch (IOException ignored) { /* best-effort */ }
            }
            throw e;
        }
    }

    /**
     * Enqueues a {@code MUTATION} frame for the given shape onto the async
     * write queue.  Safe to call from any thread (e.g., the UI dispatch
     * thread).  The actual write is performed by the write daemon thread.
     *
     * @param shape the shape mutation to broadcast; must not be {@code null}
     * @throws IllegalStateException if the client has not been connected
     */
    public void sendMutation(Shape shape) {
        if (shape == null) throw new IllegalArgumentException("shape must not be null");
        if (!running.get()) throw new IllegalStateException("NetworkClient is not running");

        enqueueFrame(MessageCodec.encode(
                new Message(MessageType.MUTATION, ClientShapeCodec.encodeMutation(shape))));
    }

    /**
     * Enqueues one or more {@code MUTATION_BATCH} frames for the given shapes.
     * Large freehand strokes are split into chunks that respect
     * {@link #MUTATION_BATCH_MAX_SHAPES} and {@link #MUTATION_BATCH_MAX_PAYLOAD_BYTES}.
     *
     * @param shapes committed shapes to broadcast; must not be {@code null} or empty
     * @throws IllegalStateException if the client has not been connected
     */
    public void sendMutationBatch(List<Shape> shapes) {
        if (shapes == null || shapes.isEmpty()) {
            return;
        }
        if (!running.get()) {
            throw new IllegalStateException("NetworkClient is not running");
        }

        int index = 0;
        while (index < shapes.size()) {
            int chunkEnd = index + 1;
            String payload = ClientShapeCodec.encodeMutationBatch(shapes.subList(index, chunkEnd));

            while (chunkEnd < shapes.size()
                    && chunkEnd - index < MUTATION_BATCH_MAX_SHAPES) {
                String candidate = ClientShapeCodec.encodeMutationBatch(
                        shapes.subList(index, chunkEnd + 1));
                if (candidate.getBytes(StandardCharsets.UTF_8).length
                        > MUTATION_BATCH_MAX_PAYLOAD_BYTES) {
                    break;
                }
                payload = candidate;
                chunkEnd++;
            }

            enqueueFrame(MessageCodec.encode(
                    new Message(MessageType.MUTATION_BATCH, payload)));
            index = chunkEnd;
        }
    }

    /**
     * Enqueues a {@code SHAPE_START} frame signalling that this client began
     * drawing a new shape.  Silently no-ops when not connected.
     */
    public void sendShapeStart(UUID shapeId, String tool, String color,
                               double strokeWidth, double x, double y) {
        if (!running.get()) return;
        record Payload(String shapeId, String tool, String color,
                       double strokeWidth, double x, double y, String authorName) {}
        enqueueFrame(MessageCodec.encodeObject(MessageType.SHAPE_START,
                new Payload(shapeId.toString(), tool, color, strokeWidth, x, y, effectiveAuthorName())));
    }

    /**
     * Enqueues a {@code SHAPE_UPDATE} frame with the latest cursor tip
     * coordinates for a shape that is currently being drawn.
     * Silently no-ops when not connected.
     */
    public void sendShapeUpdate(UUID shapeId, double x, double y) {
        if (!running.get()) return;
        record Payload(String shapeId, double x, double y) {}
        enqueueFrame(MessageCodec.encodeObject(MessageType.SHAPE_UPDATE,
                new Payload(shapeId.toString(), x, y)));
    }

    /**
     * Enqueues a {@code SHAPE_COMMIT} frame signalling that the shape is
     * finished.  Receivers should clear the transient preview; the final
     * shape(s) will arrive as subsequent {@code MUTATION} frames.
     * Silently no-ops when not connected.
     */
    public void sendShapeCommit(UUID shapeId) {
        if (!running.get()) return;
        record Payload(String shapeId) {}
        enqueueFrame(MessageCodec.encodeObject(MessageType.SHAPE_COMMIT,
                new Payload(shapeId.toString())));
    }

    /**
     * Enqueues a {@code TEXT_UPDATE} frame carrying the current (uncommitted)
     * content of a text node that is being actively edited.
     *
     * <p>The server relays the frame to all other connected clients immediately
     * without persisting it — receivers should update a transient "ghost" overlay
     * rather than committing the text to their authoritative canvas store.  The
     * final committed {@link com.distrisync.model.TextNode} arrives later as a
     * normal {@code MUTATION} frame.
     *
     * <p>Silently no-ops when not connected.
     *
     * @param objectId    stable UUID of the text node being edited; must not be {@code null}
     * @param x           X anchor coordinate of the text node on the canvas
     * @param y           Y anchor coordinate of the text node on the canvas
     * @param currentText the in-progress (uncommitted) text; must not be {@code null}
     */
    public void sendTextUpdate(UUID objectId, double x, double y, String currentText) {
        if (objectId    == null) throw new IllegalArgumentException("objectId must not be null");
        if (currentText == null) throw new IllegalArgumentException("currentText must not be null");
        if (!running.get()) return;
        enqueueFrame(MessageCodec.encodeTextUpdate(objectId, clientId, effectiveAuthorName(), x, y, currentText));
        log.debug("TEXT_UPDATE enqueued objectId={}", objectId);
    }

    /**
     * Sends a {@code CLEAR_USER_SHAPES} request to the server carrying this
     * client's own {@code clientId} as the payload.  The server will remove all
     * shapes owned by this client from its authoritative state and relay the
     * frame to every other connected peer so they can mirror the scoped clear.
     * The calling client is responsible for removing its own shapes from the
     * local canvas immediately, without waiting for an echo.
     *
     * <p>Silently no-ops when not connected.
     */
    public void sendClearUserShapes() {
        if (!running.get()) return;
        enqueueFrame(MessageCodec.encodeClearUserShapes(clientId));
        log.debug("CLEAR_USER_SHAPES enqueued clientId={}", clientId);
    }

    /**
     * Requests removal of a peer from the current room ({@link MessageType#MODERATION_ACTION} KICK).
     * No-op when not connected, not in a room, or {@code targetClientId} is blank.
     */
    public void sendModerationKick(String targetClientId, String reason) {
        if (!running.get()) {
            return;
        }
        String rid = activeRoomId;
        if (rid == null || rid.isBlank()) {
            log.debug("sendModerationKick ignored — not in a room");
            return;
        }
        String target = targetClientId != null ? targetClientId.strip() : "";
        if (target.isBlank()) {
            log.debug("sendModerationKick ignored — blank targetClientId");
            return;
        }
        String r = reason != null ? reason : "";
        enqueueFrame(MessageCodec.encodeModerationAction("KICK", target, r));
        log.debug("MODERATION_ACTION KICK enqueued targetClientId='{}'", target);
    }

    /**
     * Requests revocation of a peer's speak permission ({@link MessageType#MODERATION_ACTION}
     * {@code REVOKE_SPEAK}). No-op when not connected, not in a room, or {@code targetClientId}
     * is blank.
     */
    public void sendModerationRevokeSpeak(String targetClientId, String reason) {
        if (!running.get()) {
            return;
        }
        String rid = activeRoomId;
        if (rid == null || rid.isBlank()) {
            log.debug("sendModerationRevokeSpeak ignored — not in a room");
            return;
        }
        String target = targetClientId != null ? targetClientId.strip() : "";
        if (target.isBlank()) {
            log.debug("sendModerationRevokeSpeak ignored — blank targetClientId");
            return;
        }
        String r = reason != null ? reason : "";
        enqueueFrame(MessageCodec.encodeModerationAction("REVOKE_SPEAK", target, r));
        log.debug("MODERATION_ACTION REVOKE_SPEAK enqueued targetClientId='{}'", target);
    }

    /**
     * Requests restoration of a peer's speak permission ({@link MessageType#MODERATION_ACTION}
     * {@code GRANT_SPEAK}). No-op when not connected, not in a room, or {@code targetClientId}
     * is blank.
     */
    public void sendModerationGrantSpeak(String targetClientId, String reason) {
        if (!running.get()) {
            return;
        }
        String rid = activeRoomId;
        if (rid == null || rid.isBlank()) {
            log.debug("sendModerationGrantSpeak ignored — not in a room");
            return;
        }
        String target = targetClientId != null ? targetClientId.strip() : "";
        if (target.isBlank()) {
            log.debug("sendModerationGrantSpeak ignored — blank targetClientId");
            return;
        }
        String r = reason != null ? reason : "";
        enqueueFrame(MessageCodec.encodeModerationAction("GRANT_SPEAK", target, r));
        log.debug("MODERATION_ACTION GRANT_SPEAK enqueued targetClientId='{}'", target);
    }

    /**
     * Sends an {@code UNDO_REQUEST} to the server asking it to delete the shape
     * identified by {@code shapeId}.  If the shape exists in the authoritative
     * state the server removes it and broadcasts a {@code SHAPE_DELETE} frame to
     * all other clients.  The calling client should remove the shape from its
     * local store immediately, without waiting for an echo.
     *
     * <p>Silently no-ops when not connected.
     *
     * @param shapeId the {@link UUID} of the shape to delete; must not be {@code null}
     */
    public void sendUndoRequest(UUID shapeId) {
        if (shapeId == null) throw new IllegalArgumentException("shapeId must not be null");
        if (!running.get()) return;
        record Payload(String shapeId) {}
        enqueueFrame(MessageCodec.encodeObject(MessageType.UNDO_REQUEST,
                new Payload(shapeId.toString())));
        log.debug("UNDO_REQUEST enqueued shapeId={}", shapeId);
    }

    /** Returns the server-confirmed room display name when locked; otherwise the last requested name. */
    public String getAuthorName() {
        return effectiveAuthorName();
    }

    private String effectiveAuthorName() {
        String locked = lockedRoomName;
        if (locked != null && !locked.isBlank()) {
            return locked;
        }
        return displayName != null ? displayName : "";
    }

    private String resolveReconnectDisplayName() {
        String locked = lockedRoomName;
        if (locked != null && !locked.isBlank()) {
            return locked;
        }
        return displayName != null ? displayName : "";
    }

    /** Returns the client-ID this client advertised in its {@code HANDSHAKE}. */
    public String getClientId() {
        return clientId;
    }

    /**
     * Last room passed to {@link #sendJoinRoom(String, String)}; empty in the lobby
     * (including immediately after {@link #sendLeaveRoom()}).
     */
    public String getActiveRoomId() {
        return activeRoomId;
    }

    /** Active workspace board while in a room; empty in the lobby. */
    public String getCurrentBoardId() {
        String s = currentBoardId;
        return s != null ? s : "";
    }

    /**
     * Board ids for the current workspace, aligned with the latest
     * {@code BOARD_LIST_UPDATE} from the server while in a room.
     */
    public List<String> getKnownBoards() {
        return List.copyOf(knownBoards);
    }

    /** {@code true} while TCP I/O threads are running after a successful {@link #connect()}. */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * {@code true} after a successful TCP connect until {@link #close()} or permanent disconnect.
     */
    public SimpleBooleanProperty tcpConnectedProperty() {
        return tcpConnected;
    }

    /**
     * {@code true} after {@link MessageType#UDP_ADMISSION} is handled and the UDP datagram socket
     * is bound and connected (audio data plane ready for registration).
     */
    public SimpleBooleanProperty udpActiveProperty() {
        return udpActive;
    }

    /**
     * Rolling average TCP RTT in milliseconds over the last up to
     * {@value #PING_ROLLING_WINDOW} {@code PONG} samples, or {@code -1} before the first
     * successful measurement.
     */
    public SimpleLongProperty pingProperty() {
        return ping;
    }

    /** Push-to-talk audio engine (mic capture + UDP playback). */
    public AudioEngine getAudioEngine() {
        return audioEngine;
    }

    /**
     * Replaces the audio engine after {@link MessageType#SESSION_REVOKED} closed the prior instance.
     * Safe to call from the FX thread when returning to the lobby.
     */
    public void reinitializeAudioEngine() {
        AudioEngine old = audioEngine;
        old.close();
        AudioEngine fresh = new AudioEngine();
        fresh.setVoiceStateSync(this::sendVoiceState);
        fresh.setParticipantManager(participantManager);
        audioEngine = fresh;
        if (running.get()) {
            fresh.startReceiveDaemon();
        }
    }

    /** Room participant roster and peer audio state for UI binding. */
    public ParticipantManager getParticipantManager() {
        return participantManager;
    }

    /** Room-level flags (board lock, etc.) for UI binding. */
    public RoomState getRoomState() {
        return roomState;
    }

    /**
     * Last opaque UDP token from {@link MessageType#UDP_ADMISSION}; empty in the lobby
     * or before the server issues a token.
     */
    public String getUdpToken() {
        String t = udpToken;
        return t != null ? t : "";
    }

    /** Adds {@code frame} to the write queue and unparks the write thread. */
    private void enqueueFrame(ByteBuffer frame) {
        writeQueue.offer(frame);
        Thread wt = writeThread;
        if (wt != null) LockSupport.unpark(wt);
    }

    /**
     * Registers a listener to receive canvas update events.
     * Duplicate registrations are silently ignored.
     *
     * @param listener the listener to add; ignored if {@code null}
     */
    public void addListener(CanvasUpdateListener listener) {
        if (listener != null) listeners.addIfAbsent(listener);
    }

    /**
     * Removes a previously registered listener.
     *
     * @param listener the listener to remove; ignored if {@code null} or not registered
     */
    public void removeListener(CanvasUpdateListener listener) {
        if (listener != null) listeners.remove(listener);
    }

    /**
     * Registers a listener for {@code LOBBY_STATE} frames. Duplicate registrations
     * are ignored.
     */
    public void addLobbyListener(LobbyUpdateListener listener) {
        if (listener != null) lobbyListeners.addIfAbsent(listener);
    }

    public void removeLobbyListener(LobbyUpdateListener listener) {
        if (listener != null) lobbyListeners.remove(listener);
    }

    /**
     * Registers a callback for {@link MessageType#ROOM_DELETED}. Duplicate registrations are ignored.
     */
    public void addRoomDeletedListener(Runnable listener) {
        if (listener != null) {
            roomDeletedListeners.addIfAbsent(listener);
        }
    }

    public void removeRoomDeletedListener(Runnable listener) {
        if (listener != null) {
            roomDeletedListeners.remove(listener);
        }
    }

    public void addVoiceStateListener(VoiceStateListener listener) {
        if (listener != null) {
            voiceStateListeners.addIfAbsent(listener);
        }
    }

    public void removeVoiceStateListener(VoiceStateListener listener) {
        if (listener != null) {
            voiceStateListeners.remove(listener);
        }
    }

    public void addCursorSyncListener(CursorSyncListener listener) {
        if (listener != null) {
            cursorSyncListeners.addIfAbsent(listener);
        }
    }

    public void removeCursorSyncListener(CursorSyncListener listener) {
        if (listener != null) {
            cursorSyncListeners.remove(listener);
        }
    }

    public void addSessionRevokedListener(SessionRevokedListener listener) {
        if (listener != null) {
            sessionRevokedListeners.addIfAbsent(listener);
        }
    }

    public void removeSessionRevokedListener(SessionRevokedListener listener) {
        if (listener != null) {
            sessionRevokedListeners.remove(listener);
        }
    }

    public void addRoleUpdateListener(RoleUpdateListener listener) {
        if (listener != null) {
            roleUpdateListeners.addIfAbsent(listener);
        }
    }

    public void removeRoleUpdateListener(RoleUpdateListener listener) {
        if (listener != null) {
            roleUpdateListeners.remove(listener);
        }
    }

    public void addRoomMembershipListener(RoomMembershipListener listener) {
        if (listener != null) {
            roomMembershipListeners.addIfAbsent(listener);
        }
    }

    public void removeRoomMembershipListener(RoomMembershipListener listener) {
        if (listener != null) {
            roomMembershipListeners.remove(listener);
        }
    }

    public void addBoardPresenceListener(BoardPresenceListener listener) {
        if (listener != null) {
            boardPresenceListeners.addIfAbsent(listener);
        }
    }

    public void removeBoardPresenceListener(BoardPresenceListener listener) {
        if (listener != null) {
            boardPresenceListeners.remove(listener);
        }
    }

    /**
     * Registers a callback for {@link com.distrisync.protocol.MessageType#BOARD_DELETED}.
     * Duplicate registrations are ignored.
     */
    public void addBoardDeletedListener(BoardDeletedListener listener) {
        if (listener != null) {
            boardDeletedListeners.addIfAbsent(listener);
        }
    }

    public void removeBoardDeletedListener(BoardDeletedListener listener) {
        if (listener != null) {
            boardDeletedListeners.remove(listener);
        }
    }

    public void addMediaStateListener(MediaStateListener listener) {
        if (listener != null) {
            mediaStateListeners.addIfAbsent(listener);
        }
    }

    public void removeMediaStateListener(MediaStateListener listener) {
        if (listener != null) {
            mediaStateListeners.remove(listener);
        }
    }

    /**
     * Publishes this client's canvas cursor position to peers ({@link MessageType#CURSOR_SYNC}).
     * Rate-limited to ~15 Hz. Silently no-ops when not connected.
     *
     * @param x canvas X coordinate
     * @param y canvas Y coordinate
     */
    public void sendCursorSync(double x, double y) {
        if (!running.get()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCursorSendMs < CURSOR_SYNC_MIN_INTERVAL_MS) {
            return;
        }
        lastCursorSendMs = now;
        enqueueFrame(MessageCodec.encodeCursorSync(clientId, effectiveAuthorName(), x, y));
    }

    /**
     * Publishes this client's hardware microphone mute state to all other peers in the room
     * via the reliable TCP control plane ({@link MessageType#VOICE_STATE}).
     *
     * <p>Silently no-ops when not connected. Not used for voice-activity / speaking detection.
     *
     * @param isMuted {@code true} when the local mic is muted
     */
    public void sendVoiceState(boolean isMuted) {
        if (!running.get()) {
            return;
        }
        enqueueFrame(MessageCodec.encodeVoiceState(clientId, isMuted));
        log.debug("VOICE_STATE enqueued clientId={} muted={}", clientId, isMuted);
    }

    /**
     * Enqueues a {@code MEDIA_CONTROL} frame (PLAY, PAUSE, SEEK, LOAD, STOP).
     * Server enforces {@link com.distrisync.protocol.RoomPermissions#PERM_MANAGE_MEDIA}.
     */
    public void sendMediaControl(String action, double requestedTime, String targetId) {
        if (!running.get()) {
            return;
        }
        String tid = targetId != null ? targetId : "";
        enqueueFrame(MessageCodec.encodeMediaControl(
                new MessageCodec.MediaControlPayload(action, requestedTime, tid)));
        log.debug("MEDIA_CONTROL enqueued action={} time={}", action, requestedTime);
    }

    /**
     * Stops both I/O threads and closes the underlying channel.  Safe to call
     * from any thread.  After this returns the client instance must not be
     * reused.
     */
    @Override
    public void close() {
        running.set(false);

        Thread pt = pingThread;
        if (pt != null) {
            pt.interrupt();
        }

        audioEngine.close();

        SocketChannel ch = channel;
        if (ch != null) {
            try {
                ch.close();
            } catch (IOException e) {
                log.warn("Error closing channel during shutdown", e);
            }
        }

        // Unpark the write thread so it exits its park/loop promptly.
        Thread wt = writeThread;
        if (wt != null) LockSupport.unpark(wt);

        publishTcpConnected(false);
        publishUdpActive(false);

        log.info("NetworkClient closed");
    }

    private void resetWorkspaceForLobby() {
        knownBoards.clear();
        currentBoardId = "";
        lastSnapshotBoardId = "";
        participantManager.clear();
        runOnFxThreadIfPossible(roomState::reset);
        notifyWorkspaceListeners();
    }

    private void ensureBoardKnown(String boardId) {
        if (boardId == null || boardId.isBlank()) {
            return;
        }
        if (!knownBoards.contains(boardId)) {
            knownBoards.add(boardId);
        }
    }

    private void notifyWorkspaceListeners() {
        List<String> copy = List.copyOf(knownBoards);
        String cur = currentBoardId != null ? currentBoardId : "";
        for (CanvasUpdateListener listener : listeners) {
            listener.onWorkspaceStateChanged(cur, copy);
        }
    }

    // =========================================================================
    // Connection helpers
    // =========================================================================

    /**
     * Opens a blocking {@link SocketChannel} to the configured endpoint.
     * Retries on failure with a {@value #RECONNECT_DELAY_MS} ms pause between
     * attempts, up to {@value #MAX_RECONNECT_ATTEMPTS} times total.
     *
     * @return a connected, blocking-mode {@link SocketChannel}
     * @throws IOException if all attempts are exhausted
     */
    private SocketChannel connectWithBackoff() throws IOException {
        IOException lastError = null;

        for (int attempt = 1; attempt <= MAX_RECONNECT_ATTEMPTS; attempt++) {
            log.info("Connecting to {}:{} (attempt {}/{})",
                    host, port, attempt, MAX_RECONNECT_ATTEMPTS);
            try {
                SocketChannel sc = SocketChannel.open();
                // Disable Nagle's algorithm before the connect syscall so the OS
                // never buffers small frames (cursor ticks, live-text events).
                sc.setOption(StandardSocketOptions.TCP_NODELAY, true);
                // 64 KiB kernel socket buffers absorb large snapshots without
                // blocking the write loop mid-frame.
                sc.setOption(StandardSocketOptions.SO_SNDBUF, 64 * 1024);
                sc.setOption(StandardSocketOptions.SO_RCVBUF, 64 * 1024);
                sc.configureBlocking(true);
                sc.connect(new InetSocketAddress(host, port));
                log.info("TCP connection established to {}:{}", host, port);
                return sc;
            } catch (IOException e) {
                lastError = e;
                log.warn("Attempt {}/{} failed: {}", attempt, MAX_RECONNECT_ATTEMPTS, e.getMessage());

                if (attempt < MAX_RECONNECT_ATTEMPTS) {
                    try {
                        Thread.sleep(RECONNECT_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted during reconnect backoff", ie);
                    }
                }
            }
        }

        throw new IOException(
                "Failed to connect to " + host + ":" + port
                + " after " + MAX_RECONNECT_ATTEMPTS + " attempts",
                lastError);
    }

    /**
     * Sends a {@code HANDSHAKE} frame synchronously on the current channel.
     * The payload carries {@code clientId} only; display identity is sent with
     * {@code JOIN_ROOM}. This always completes before the I/O threads are started
     * (or restarted after a reconnect). Uses {@link #writeFully} so it never races
     * the write thread.
     */
    private void sendHandshake() throws IOException {
        ByteBuffer handshake = MessageCodec.encodeHandshake(clientId);
        writeFully(handshake);
        log.debug("HANDSHAKE sent to {}:{} clientId='{}'", host, port, clientId);
    }

    /**
     * Enters a canvas room (async). The server responds with {@code SNAPSHOT}.
     * Updates {@link #getActiveRoomId()} for reconnect semantics.
     *
     * @param roomId      non-blank room identifier
     * @param displayName room-level display identity; may be empty but not {@code null}
     */
    public void joinRoom(String roomId, String displayName) {
        sendJoinRoom(roomId, displayName);
    }

    /**
     * Enters a canvas room (async). The server responds with {@code SNAPSHOT}.
     * Updates {@link #getActiveRoomId()} for reconnect semantics.
     *
     * @param roomId      non-blank room identifier
     * @param displayName room-level display identity; may be empty but not {@code null}
     */
    public void sendJoinRoom(String roomId, String displayName) {
        if (!running.get()) return;
        String rid = roomId != null ? roomId.strip() : "";
        if (rid.isBlank()) {
            log.debug("sendJoinRoom ignored — blank roomId");
            return;
        }
        String name = displayName != null ? displayName : "";
        this.displayName = name;
        activeRoomId = rid;
        lastSnapshotBoardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;
        currentBoardId = lastSnapshotBoardId;
        ensureBoardKnown(lastSnapshotBoardId);
        notifyWorkspaceListeners();
        if (protocolReady.get()) {
            enqueueFrame(MessageCodec.encodeJoinRoom(rid, name));
            log.debug("JOIN_ROOM enqueued roomId='{}' displayName='{}'", rid, name);
        } else {
            deferredJoinRoomId = rid;
            deferredJoinBoardId = "";
            deferredJoinDisplayName = name;
            log.debug("JOIN_ROOM deferred until protocol ready roomId='{}' displayName='{}'", rid, name);
        }
    }

    /**
     * Enters a room on a specific workspace board (async). The server responds with
     * {@code SNAPSHOT} for that board.
     *
     * @param roomId          non-blank room identifier
     * @param displayName     room-level display identity; may be empty but not {@code null}
     * @param initialBoardId  non-blank board id (e.g. {@code "Math-Notes"})
     */
    public void sendJoinRoom(String roomId, String displayName, String initialBoardId) {
        if (!running.get()) return;
        String rid = roomId != null ? roomId.strip() : "";
        if (rid.isBlank()) {
            log.debug("sendJoinRoom ignored — blank roomId");
            return;
        }
        String board = initialBoardId != null ? initialBoardId.strip() : "";
        if (board.isBlank()) {
            log.debug("sendJoinRoom ignored — blank initialBoardId");
            return;
        }
        String name = displayName != null ? displayName : "";
        this.displayName = name;
        activeRoomId = rid;
        lastSnapshotBoardId = board;
        currentBoardId = board;
        ensureBoardKnown(board);
        notifyWorkspaceListeners();
        if (protocolReady.get()) {
            enqueueFrame(MessageCodec.encodeJoinRoom(rid, name, board));
            log.debug("JOIN_ROOM enqueued roomId='{}' displayName='{}' boardId='{}'", rid, name, board);
        } else {
            deferredJoinRoomId = rid;
            deferredJoinBoardId = board;
            deferredJoinDisplayName = name;
            log.debug("JOIN_ROOM deferred until protocol ready roomId='{}' displayName='{}' boardId='{}'",
                    rid, name, board);
        }
    }

    /**
     * Switches the active board within the current room (async). The server sends a fresh
     * {@code SNAPSHOT} for {@code boardId}. No-op when not connected or when {@code boardId} is blank.
     */
    public void sendSwitchBoard(String boardId) {
        if (!running.get()) return;
        String rid = activeRoomId;
        if (rid == null || rid.isBlank()) {
            log.debug("sendSwitchBoard ignored — not in a room");
            return;
        }
        String bid = boardId != null ? boardId.strip() : "";
        if (bid.isBlank()) {
            log.debug("sendSwitchBoard ignored — blank boardId");
            return;
        }
        currentBoardId = bid;
        lastSnapshotBoardId = bid;
        ensureBoardKnown(bid);
        participantManager.setCurrentBoardId(clientId, bid);
        notifyWorkspaceListeners();
        enqueueFrame(MessageCodec.encodeSwitchBoard(bid));
        log.debug("SWITCH_BOARD enqueued boardId='{}'", bid);
    }

    /**
     * Requests removal of a board from the room (requires {@link com.distrisync.protocol.RoomPermissions#PERM_MANAGE_ROOM}).
     * No-op when not connected, not in a room, protocol not ready, or {@code boardId} is blank.
     */
    public void sendDeleteBoard(String boardId) {
        if (!running.get()) {
            return;
        }
        String rid = activeRoomId;
        if (rid == null || rid.isBlank()) {
            log.debug("sendDeleteBoard ignored — not in a room");
            return;
        }
        String bid = boardId != null ? boardId.strip() : "";
        if (bid.isBlank()) {
            log.debug("sendDeleteBoard ignored — blank boardId");
            return;
        }
        if (!protocolReady.get()) {
            log.debug("sendDeleteBoard ignored — protocol not ready boardId='{}'", bid);
            return;
        }
        enqueueFrame(MessageCodec.encodeDeleteBoard(bid));
        log.debug("DELETE_BOARD enqueued boardId='{}'", bid);
    }

    /**
     * Sets the room board-creation lock on the server (requires {@link com.distrisync.protocol.RoomPermissions#PERM_MANAGE_ROOM}).
     */
    public void sendBoardLockState(boolean locked) {
        if (!running.get()) {
            return;
        }
        String rid = activeRoomId;
        if (rid == null || rid.isBlank()) {
            log.debug("sendBoardLockState ignored — not in a room");
            return;
        }
        enqueueFrame(MessageCodec.encodeBoardLockCommand(locked));
        log.debug("TOGGLE_BOARD_LOCK enqueued roomId='{}' locked={}", rid, locked);
    }

    /**
     * Blocking {@code JOIN_ROOM} for the reconnect path only (write thread not used).
     */
    private void sendJoinRoomBlocking(String roomId, String name) throws IOException {
        ByteBuffer frame = MessageCodec.encodeJoinRoom(roomId, name);
        writeFully(frame);
        log.info("JOIN_ROOM sent (blocking) to {}:{} roomId='{}' displayName='{}'", host, port, roomId, name);
    }

    /**
     * Requests to leave the current canvas room and return to the lobby.
     * No-op when not connected.
     */
    public void sendLeaveRoom() {
        if (!running.get()) return;
        activeRoomId = "";
        displayName = "";
        lockedRoomName = null;
        resetWorkspaceForLobby();
        publishUdpActive(false);
        audioEngine.stopCaptureDaemon();
        enqueueFrame(MessageCodec.encodeLeaveRoom());
        log.debug("LEAVE_ROOM enqueued");
        sendFetchLobby();
    }

    /**
     * Requests durable deletion of {@code roomId} on the server ({@link MessageType#DELETE_ROOM}).
     * No-op when not connected, protocol is not ready, or {@code roomId} is blank.
     */
    public void sendDeleteRoom(String roomId) {
        if (!running.get()) {
            return;
        }
        String rid = roomId != null ? roomId.strip() : "";
        if (rid.isBlank()) {
            log.debug("sendDeleteRoom ignored — blank roomId");
            return;
        }
        if (!protocolReady.get()) {
            log.debug("sendDeleteRoom ignored — protocol not ready roomId='{}'", rid);
            return;
        }
        enqueueFrame(MessageCodec.encodeDeleteRoom(rid));
        log.debug("DELETE_ROOM enqueued roomId='{}'", rid);
    }

    /**
     * Requests a pull-based {@code LOBBY_STATE} refresh from the server (client → server
     * {@link MessageType#FETCH_LOBBY}). No-op when not connected or before the protocol is ready.
     */
    public void sendFetchLobby() {
        if (!running.get()) {
            return;
        }
        if (!protocolReady.get()) {
            log.debug("sendFetchLobby ignored — protocol not ready");
            return;
        }
        enqueueFrame(MessageCodec.encodeFetchLobby());
        log.debug("FETCH_LOBBY enqueued");
    }

    /**
     * Writes the entire buffer to {@link #channel} under {@link #writeLock},
     * spinning on partial writes (blocking channel).
     */
    private void writeFully(ByteBuffer buf) throws IOException {
        ByteBuffer dup = buf.duplicate();
        synchronized (writeLock) {
            SocketChannel ch = channel;
            if (ch == null) {
                throw new IOException("SocketChannel is null");
            }
            while (dup.hasRemaining()) {
                ch.write(dup);
            }
        }
    }

    private void closeChannelQuietly() {
        SocketChannel ch = channel;
        if (ch != null) {
            try {
                ch.close();
            } catch (IOException ignored) {
            }
        }
        publishTcpConnected(false);
    }

    // =========================================================================
    // Reconnect
    // =========================================================================

    /**
     * Closes the stale channel, opens a fresh one via the backoff strategy, and
     * re-sends the {@code HANDSHAKE} so the server issues a new {@code SNAPSHOT}.
     *
     * <p>Synchronized to prevent the read and write threads from triggering
     * concurrent reconnect storms.
     *
     * @throws IOException if all reconnect attempts are exhausted
     */
    private synchronized void reconnect() throws IOException {
        publishUdpActive(false);
        protocolReady.set(false);
        deferredJoinRoomId = "";
        deferredJoinBoardId = "";
        deferredJoinDisplayName = "";
        SocketChannel stale = channel;
        if (stale != null) {
            try { stale.close(); } catch (IOException ignored) {}
        }
        channel = connectWithBackoff();
        sendHandshake();
        String rid = activeRoomId;
        if (rid != null && !rid.isBlank()) {
            sendJoinRoomBlocking(rid, resolveReconnectDisplayName());
            lastSnapshotBoardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;
            currentBoardId = lastSnapshotBoardId;
            ensureBoardKnown(lastSnapshotBoardId);
            notifyWorkspaceListeners();
            protocolReady.set(true);
            log.info("Reconnected to {}:{} — HANDSHAKE + JOIN_ROOM roomId='{}'", host, port, rid);
        } else {
            log.info("Reconnected to {}:{} — HANDSHAKE (lobby)", host, port);
        }
    }

    // =========================================================================
    // Read thread
    // =========================================================================

    private void startReadThread() {
        Thread t = new Thread(this::readLoop, "distrisync-read");
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((thread, ex) ->
                log.error("Read thread terminated with fatal exception — client offline", ex));
        readThread = t;
        t.start();
    }

    /**
     * Blocking read loop.  Runs on the {@code distrisync-read} daemon thread.
     *
     * <p>Each iteration appends bytes from the channel into an accumulation
     * buffer, flips it, drains all complete frames, then compacts the remaining
     * partial tail back to the write position.  On disconnect it triggers the
     * reconnect backoff; after exhausting all attempts it propagates a
     * {@link RuntimeException} through the thread's uncaught handler.
     */
    private void readLoop() {
        // Per-connection accumulation buffer; cleared only on reconnect.
        ByteBuffer accumulator = ByteBuffer.allocate(READ_BUFFER_CAPACITY);

        while (running.get()) {
            try {
                int bytesRead = channel.read(accumulator);

                if (bytesRead == -1) {
                    log.warn("Server closed the connection (EOF)");
                    handleDisconnect("EOF from server");
                    accumulator.clear();
                    continue;
                }

                if (bytesRead == 0) {
                    // Shouldn't happen on a blocking channel, but guard defensively.
                    continue;
                }

                // Flip: switch from write-mode to read-mode for decoding.
                accumulator.flip();
                drainFrames(accumulator);
                // Compact: move any partial tail back to position 0 for the next read.
                accumulator.compact();

            } catch (IOException e) {
                if (!running.get()) break; // Normal shutdown — channel was closed by close().
                log.warn("I/O error on read channel: {}", e.getMessage());
                try {
                    handleDisconnect(e.getMessage());
                    accumulator.clear();
                } catch (RuntimeException fatal) {
                    publishTcpConnected(false);
                    publishUdpActive(false);
                    running.set(false);
                    throw fatal;
                }
                if (running.get() && (channel == null || !channel.isOpen())) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        log.info("Read loop exited");
    }

    /**
     * Decodes all complete frames from a flipped {@code buffer}, dispatching
     * each to {@link #dispatchMessage}.  Stops when a
     * {@link PartialMessageException} signals that the remaining bytes do not
     * form a complete frame; the codec has already rewound the buffer position
     * to the start of the partial tail so the caller can safely {@code compact}.
     */
    private void drainFrames(ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
            try {
                Message msg = MessageCodec.decode(buffer);
                dispatchMessage(msg);
            } catch (PartialMessageException e) {
                // Buffer holds a partial frame; position was reset by the codec.
                // The outer readLoop will compact and read more bytes.
                break;
            } catch (RuntimeException e) {
                log.error("Protocol decode/dispatch failed — forcing reconnect: {}", e.getMessage(), e);
                try {
                    handleDisconnect("protocol error");
                } catch (RuntimeException fatal) {
                    publishTcpConnected(false);
                    publishUdpActive(false);
                    running.set(false);
                    throw fatal;
                }
                break;
            }
        }
    }

    /**
     * First server message after handshake proves the session is live; flush any
     * deferred {@code JOIN_ROOM} queued from the UI before this point.
     */
    private void deliverSnapshot(List<Shape> shapes) {
        noteProtocolReady();
        if (!lastSnapshotBoardId.isBlank()) {
            currentBoardId = lastSnapshotBoardId;
            ensureBoardKnown(currentBoardId);
        }
        log.info("SNAPSHOT received: {} shape(s)", shapes.size());
        for (CanvasUpdateListener listener : listeners) {
            listener.onSnapshotReceived(shapes);
        }
    }

    private void noteProtocolReady() {
        if (!protocolReady.compareAndSet(false, true)) {
            return;
        }
        String rid = deferredJoinRoomId;
        deferredJoinRoomId = "";
        String board = deferredJoinBoardId;
        deferredJoinBoardId = "";
        String name = deferredJoinDisplayName;
        deferredJoinDisplayName = "";
        if (rid != null && !rid.isBlank()) {
            ByteBuffer frame = (board != null && !board.isBlank())
                    ? MessageCodec.encodeJoinRoom(rid, name, board)
                    : MessageCodec.encodeJoinRoom(rid, name);
            enqueueFrame(frame);
            log.debug("Flushed deferred JOIN_ROOM roomId='{}' displayName='{}' boardId='{}'", rid, name, board);
        }
    }

    private void dispatchMessage(Message msg) {
        switch (msg.type()) {
            case SNAPSHOT -> {
                List<Shape> shapes;
                try {
                    shapes = ClientShapeCodec.decodeSnapshot(msg.payload());
                } catch (Exception e) {
                    log.error("SNAPSHOT decode failed — ignoring frame: {}", e.getMessage(), e);
                    break;
                }
                if (shapes.isEmpty()) {
                    snapshotHydrating = true;
                    snapshotHydrationBuffer.clear();
                    if (!lastSnapshotBoardId.isBlank()) {
                        currentBoardId = lastSnapshotBoardId;
                        ensureBoardKnown(currentBoardId);
                    }
                    log.debug("SNAPSHOT hydration started");
                    break;
                }
                snapshotHydrating = false;
                snapshotHydrationBuffer.clear();
                deliverSnapshot(shapes);
            }
            case SNAPSHOT_END -> {
                snapshotHydrating = false;
                List<Shape> shapes = List.copyOf(snapshotHydrationBuffer);
                snapshotHydrationBuffer.clear();
                deliverSnapshot(shapes);
            }
            case MUTATION -> {
                Shape shape;
                try {
                    shape = ClientShapeCodec.decodeMutation(msg.payload());
                } catch (Exception e) {
                    log.warn("MUTATION decode failed — ignoring: {}", e.getMessage());
                    break;
                }
                log.debug("MUTATION received: objectId={}", shape.objectId());
                for (CanvasUpdateListener listener : listeners) {
                    listener.onMutationReceived(shape);
                }
            }
            case MUTATION_BATCH -> {
                List<Shape> batch;
                try {
                    batch = ClientShapeCodec.decodeMutationBatch(msg.payload());
                } catch (Exception e) {
                    log.warn("MUTATION_BATCH decode failed — ignoring: {}", e.getMessage());
                    break;
                }
                if (snapshotHydrating) {
                    snapshotHydrationBuffer.addAll(batch);
                    log.debug("SNAPSHOT batch buffered: {} shape(s), total {}",
                            batch.size(), snapshotHydrationBuffer.size());
                    break;
                }
                log.debug("MUTATION_BATCH received: {} shape(s)", batch.size());
                for (Shape shape : batch) {
                    for (CanvasUpdateListener listener : listeners) {
                        listener.onMutationReceived(shape);
                    }
                }
            }
            case SHAPE_START -> {
                try {
                    JsonObject p      = MessageCodec.gson().fromJson(msg.payload(), JsonObject.class);
                    UUID   shapeId    = UUID.fromString(p.get("shapeId").getAsString());
                    String tool       = p.get("tool").getAsString();
                    String color      = p.get("color").getAsString();
                    double strokeW    = p.get("strokeWidth").getAsDouble();
                    double x          = p.get("x").getAsDouble();
                    double y          = p.get("y").getAsDouble();
                    String author     = p.has("authorName") && !p.get("authorName").isJsonNull()
                                        ? p.get("authorName").getAsString() : "";
                    String shapeClientId = p.has("clientId") && !p.get("clientId").isJsonNull()
                                        ? p.get("clientId").getAsString() : "";
                    for (CanvasUpdateListener listener : listeners) {
                        listener.onShapeStart(shapeId, tool, color, strokeW, x, y, author, shapeClientId);
                    }
                } catch (Exception e) {
                    log.debug("Malformed SHAPE_START payload ignored: {}", e.getMessage());
                }
            }
            case SHAPE_UPDATE -> {
                try {
                    JsonObject p   = MessageCodec.gson().fromJson(msg.payload(), JsonObject.class);
                    UUID   shapeId = UUID.fromString(p.get("shapeId").getAsString());
                    double x       = p.get("x").getAsDouble();
                    double y       = p.get("y").getAsDouble();
                    for (CanvasUpdateListener listener : listeners) {
                        listener.onShapeUpdate(shapeId, x, y);
                    }
                } catch (Exception e) {
                    log.debug("Malformed SHAPE_UPDATE payload ignored: {}", e.getMessage());
                }
            }
            case SHAPE_COMMIT -> {
                try {
                    JsonObject p   = MessageCodec.gson().fromJson(msg.payload(), JsonObject.class);
                    UUID   shapeId = UUID.fromString(p.get("shapeId").getAsString());
                    for (CanvasUpdateListener listener : listeners) {
                        listener.onShapeCommit(shapeId);
                    }
                } catch (Exception e) {
                    log.debug("Malformed SHAPE_COMMIT payload ignored: {}", e.getMessage());
                }
            }
            case CLEAR_USER_SHAPES -> {
                String targetClientId;
                try {
                    targetClientId = MessageCodec.decodeClearUserShapes(msg);
                } catch (Exception e) {
                    log.debug("Malformed CLEAR_USER_SHAPES payload ignored: {}", e.getMessage());
                    break;
                }
                log.debug("CLEAR_USER_SHAPES received targetClientId={}", targetClientId);
                for (CanvasUpdateListener listener : listeners) {
                    listener.onUserShapesCleared(targetClientId);
                }
            }
            case SHAPE_DELETE -> {
                try {
                    JsonObject p   = MessageCodec.gson().fromJson(msg.payload(), JsonObject.class);
                    UUID   shapeId = UUID.fromString(p.get("shapeId").getAsString());
                    log.debug("SHAPE_DELETE received shapeId={}", shapeId);
                    for (CanvasUpdateListener listener : listeners) {
                        listener.onShapeDeleted(shapeId);
                    }
                } catch (Exception e) {
                    log.debug("Malformed SHAPE_DELETE payload ignored: {}", e.getMessage());
                }
            }
            case TEXT_UPDATE -> {
                try {
                    TextUpdatePayload p = MessageCodec.decodeTextUpdate(msg);
                    UUID objectId = UUID.fromString(p.objectId());
                    log.debug("TEXT_UPDATE received objectId={} clientId={}", objectId, p.clientId());
                    for (CanvasUpdateListener listener : listeners) {
                        listener.onTextUpdate(objectId, p.clientId(), p.authorName(),
                                              p.x(), p.y(), p.currentText());
                    }
                } catch (Exception e) {
                    log.debug("Malformed TEXT_UPDATE payload ignored: {}", e.getMessage());
                }
            }
            case LOBBY_STATE -> {
                try {
                    var entries = MessageCodec.decodeLobbyState(msg);
                    noteProtocolReady();
                    log.debug("LOBBY_STATE received  rooms={}", entries.size());
                    List<RoomInfo> rooms = RoomInfo.copyOf(entries);
                    for (LobbyUpdateListener listener : lobbyListeners) {
                        listener.onLobbyUpdated(rooms);
                    }
                } catch (Exception e) {
                    log.debug("Malformed LOBBY_STATE ignored: {}", e.getMessage());
                }
            }
            case JOIN_ROOM -> {
                try {
                    MessageCodec.RoomMemberJoinedPayload p = MessageCodec.decodeRoomMemberJoined(msg);
                    participantManager.putParticipant(p.clientId(), p.authorName());
                    if (p.clientId().equals(clientId)) {
                        String confirmed = p.authorName();
                        if (confirmed != null && !confirmed.isBlank()) {
                            lockedRoomName = confirmed;
                            displayName = confirmed;
                            log.debug("JOIN_ROOM self-confirm authorName='{}'", confirmed);
                        }
                        break;
                    }
                    log.debug("JOIN_ROOM peer-join clientId='{}' authorName='{}'",
                            p.clientId(), p.authorName());
                    for (RoomMembershipListener listener : roomMembershipListeners) {
                        listener.onPeerJoined(p.clientId(), p.authorName());
                    }
                } catch (IllegalArgumentException e) {
                    log.trace("JOIN_ROOM not a peer-join notification: {}", e.getMessage());
                }
            }
            case LEAVE_ROOM -> {
                try {
                    String peerId = MessageCodec.decodeRoomMemberLeft(msg);
                    if (peerId.equals(clientId)) {
                        break;
                    }
                    log.debug("LEAVE_ROOM peer-depart clientId='{}'", peerId);
                    for (RoomMembershipListener listener : roomMembershipListeners) {
                        listener.onPeerLeft(peerId);
                    }
                } catch (IllegalArgumentException e) {
                    log.trace("LEAVE_ROOM not a peer-depart notification: {}", e.getMessage());
                }
            }
            case ROOM_DELETED -> {
                log.info("ROOM_DELETED received — clearing local room state");
                activeRoomId = "";
                resetWorkspaceForLobby();
                publishUdpActive(false);
                audioEngine.stopCaptureDaemon();
                for (Runnable listener : roomDeletedListeners) {
                    try {
                        listener.run();
                    } catch (Exception e) {
                        log.warn("roomDeletedListener failed: {}", e.getMessage());
                    }
                }
                runOnFxThreadIfPossible(this::sendFetchLobby);
            }
            case BOARD_LIST_UPDATE -> {
                if (activeRoomId == null || activeRoomId.isBlank()) {
                    log.trace("BOARD_LIST_UPDATE ignored — not in a room");
                    break;
                }
                try {
                    List<String> ids = MessageCodec.decodeBoardListUpdate(msg);
                    knownBoards.clear();
                    for (String id : ids) {
                        if (id == null) {
                            continue;
                        }
                        String bid = id.strip();
                        if (!bid.isEmpty()) {
                            knownBoards.add(bid);
                        }
                    }
                    log.debug("BOARD_LIST_UPDATE applied  boardCount={}", knownBoards.size());
                    notifyWorkspaceListeners();
                } catch (Exception e) {
                    log.debug("Malformed BOARD_LIST_UPDATE ignored: {}", e.getMessage());
                }
            }
            case BOARD_DELETED -> {
                if (activeRoomId == null || activeRoomId.isBlank()) {
                    log.trace("BOARD_DELETED ignored — not in a room");
                    break;
                }
                try {
                    String deletedId = MessageCodec.decodeBoardDeleted(msg);
                    knownBoards.remove(deletedId);
                    String def = MessageCodec.DEFAULT_INITIAL_BOARD_ID;
                    if (deletedId.equals(currentBoardId) || deletedId.equals(lastSnapshotBoardId)) {
                        currentBoardId = def;
                        lastSnapshotBoardId = def;
                        participantManager.setCurrentBoardId(clientId, def);
                        ensureBoardKnown(def);
                    }
                    notifyWorkspaceListeners();
                    log.debug("BOARD_DELETED applied  boardId='{}'", deletedId);
                    for (BoardDeletedListener listener : boardDeletedListeners) {
                        try {
                            listener.onBoardDeleted(deletedId);
                        } catch (Exception e) {
                            log.warn("boardDeletedListener failed: {}", e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    log.warn("Malformed BOARD_DELETED ignored: {}", e.getMessage());
                }
            }
            case UDP_ADMISSION -> {
                try {
                    String token = MessageCodec.decodeUdpAdmission(msg);
                    udpToken = token;
                    audioEngine.onUdpAdmission(host, port, token);
                    publishUdpActive(true);
                    log.debug("UDP_ADMISSION stored; NAT punch sent to {}:{}", host, port);
                } catch (Exception e) {
                    log.warn("UDP_ADMISSION handling failed: {}", e.getMessage(), e);
                    publishUdpActive(false);
                }
            }
            case PONG -> {
                try {
                    long origin = MessageCodec.decodePingPongOrigin(msg);
                    applyPingRtt(origin);
                } catch (Exception e) {
                    log.debug("Malformed PONG ignored: {}", e.getMessage());
                }
            }
            case VOICE_STATE -> {
                try {
                    MessageCodec.VoiceStatePayload p = MessageCodec.decodeVoiceState(msg);
                    log.debug("VOICE_STATE received clientId={} muted={}", p.clientId(), p.isMuted());
                    for (VoiceStateListener listener : voiceStateListeners) {
                        listener.onVoiceState(p.clientId(), p.isMuted());
                    }
                } catch (Exception e) {
                    log.debug("Malformed VOICE_STATE ignored: {}", e.getMessage());
                }
            }
            case CURSOR_SYNC -> {
                try {
                    MessageCodec.CursorSyncPayload p = MessageCodec.decodeCursorSync(msg);
                    for (CursorSyncListener listener : cursorSyncListeners) {
                        listener.onCursorSync(p.clientId(), p.authorName(), p.x(), p.y());
                    }
                } catch (Exception e) {
                    log.debug("Malformed CURSOR_SYNC ignored: {}", e.getMessage());
                }
            }
            case FETCH_LOBBY ->
                    log.trace("Ignoring unexpected inbound FETCH_LOBBY (client-originated type)");
            case SESSION_REVOKED -> {
                String reason;
                try {
                    reason = MessageCodec.decodeSessionRevoked(msg);
                } catch (Exception e) {
                    log.warn("Malformed SESSION_REVOKED ignored: {}", e.getMessage());
                    reason = "";
                }
                log.info("SESSION_REVOKED received reason='{}'", reason);
                suppressAutoReconnect();
                running.set(false);
                closeChannelQuietly();
                activeRoomId = "";
                resetWorkspaceForLobby();
                publishUdpActive(false);
                audioEngine.stopCaptureDaemon();
                // UI teardown (overlay, cursors) is handled by SessionRevokedListener implementations.
                for (SessionRevokedListener listener : sessionRevokedListeners) {
                    try {
                        listener.onSessionRevoked(reason);
                    } catch (Exception e) {
                        log.warn("sessionRevokedListener failed: {}", e.getMessage());
                    }
                }
            }
            case ROLE_UPDATE -> {
                try {
                    MessageCodec.RoleUpdatePayload p = MessageCodec.decodeRoleUpdate(msg);
                    log.debug("ROLE_UPDATE received clientId='{}' perms={}",
                            p.newHostClientId(), p.newPermissions());
                    participantManager.updatePermissions(p.newHostClientId(), p.newPermissions());
                    if (p.newHostClientId().equals(clientId)) {
                        participantManager.setLocalPermissions(p.newPermissions());
                    }
                    for (RoleUpdateListener listener : roleUpdateListeners) {
                        try {
                            listener.onRoleUpdate(p);
                        } catch (Exception e) {
                            log.warn("roleUpdateListener failed: {}", e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    log.warn("Malformed ROLE_UPDATE ignored: {}", e.getMessage());
                }
            }
            case BOARD_SWITCH -> {
                try {
                    MessageCodec.BoardSwitchPayload p = MessageCodec.decodeBoardSwitch(msg);
                    if (p.clientId().equals(clientId)) {
                        break;
                    }
                    log.debug("BOARD_SWITCH peer='{}' board='{}'", p.clientId(), p.newBoardId());
                    participantManager.setCurrentBoardId(p.clientId(), p.newBoardId());
                    for (BoardPresenceListener listener : boardPresenceListeners) {
                        try {
                            listener.onPeerBoardSwitch(p.clientId(), p.newBoardId());
                        } catch (Exception e) {
                            log.warn("boardPresenceListener failed: {}", e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    log.warn("Malformed BOARD_SWITCH ignored: {}", e.getMessage());
                }
            }
            case TOGGLE_BOARD_LOCK -> {
                try {
                    MessageCodec.BoardLockPayload p = MessageCodec.decodeBoardLockState(msg);
                    log.debug("TOGGLE_BOARD_LOCK state locked={}", p.locked());
                    runOnFxThreadIfPossible(() -> roomState.setBoardCreationLocked(p.locked()));
                } catch (Exception e) {
                    log.warn("Malformed TOGGLE_BOARD_LOCK ignored: {}", e.getMessage());
                }
            }
            case MEDIA_STATE_UPDATE -> {
                try {
                    MessageCodec.MediaStatePayload p = MessageCodec.decodeMediaState(msg);
                    log.debug("MEDIA_STATE_UPDATE state={} videoId='{}'", p.state(), p.videoId());
                    for (MediaStateListener listener : mediaStateListeners) {
                        try {
                            listener.onMediaStateUpdate(p);
                        } catch (Exception e) {
                            log.warn("mediaStateListener failed: {}", e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    log.warn("Malformed MEDIA_STATE_UPDATE ignored: {}", e.getMessage());
                }
            }
            default -> log.warn("Ignoring unexpected inbound message type={} — check client/server versions",
                    msg.type());
        }
    }

    /**
     * Feeds an inbound {@code ROOM_DELETED} through {@link #dispatchMessage} with {@code running}
     * and {@code protocolReady} forced on, for unit tests that assert outbound behaviour without TCP.
     */
    void ingestRoomDeletedForStateTest() {
        running.set(true);
        protocolReady.set(true);
        dispatchMessage(new Message(MessageType.ROOM_DELETED, ""));
        // Leaves {@code running} / {@code protocolReady} armed so async eviction UI can call
        // {@link #sendFetchLobby()}; {@link #close()} when the harness is done.
    }

    /**
     * Feeds {@code SESSION_REVOKED} through {@link #dispatchMessage} for unit tests.
     */
    void ingestSessionRevokedForStateTest(String reason) {
        running.set(true);
        protocolReady.set(true);
        activeRoomId = "test-room";
        try {
            ByteBuffer frame = MessageCodec.encodeSessionRevoked(reason != null ? reason : "");
            Message msg = MessageCodec.decode(frame);
            dispatchMessage(msg);
        } catch (Exception e) {
            throw new IllegalStateException("SESSION_REVOKED test frame failed", e);
        }
    }

    /**
     * Feeds {@code ROLE_UPDATE} through {@link #dispatchMessage} for unit tests.
     */
    void ingestRoleUpdateForStateTest(String affectedClientId, int permissions, String roomHostClientId) {
        running.set(true);
        protocolReady.set(true);
        try {
            ByteBuffer frame = MessageCodec.encodeRoleUpdate(affectedClientId, permissions, roomHostClientId);
            Message msg = MessageCodec.decode(frame);
            dispatchMessage(msg);
        } catch (Exception e) {
            throw new IllegalStateException("ROLE_UPDATE test frame failed", e);
        }
    }

    /**
     * Feeds server→client {@code JOIN_ROOM} peer-join through {@link #dispatchMessage} for unit tests.
     */
    void ingestRoomMemberJoinedForStateTest(String joinedClientId, String authorName) {
        running.set(true);
        protocolReady.set(true);
        try {
            ByteBuffer frame = MessageCodec.encodeRoomMemberJoined(joinedClientId, authorName);
            Message msg = MessageCodec.decode(frame);
            dispatchMessage(msg);
        } catch (Exception e) {
            throw new IllegalStateException("JOIN_ROOM peer-join test frame failed", e);
        }
    }

    String getLockedRoomNameForTest() {
        return lockedRoomName;
    }

    void setLockedRoomNameForTest(String name) {
        lockedRoomName = name;
    }

    void setDisplayNameForTest(String name) {
        displayName = name != null ? name : "";
    }

    void setActiveRoomIdForTest(String roomId) {
        activeRoomId = roomId != null ? roomId : "";
    }

    String resolveReconnectDisplayNameForTest() {
        return resolveReconnectDisplayName();
    }

    /**
     * Feeds {@code MEDIA_STATE_UPDATE} through {@link #dispatchMessage} for unit tests.
     */
    void ingestMediaStateForStateTest(MessageCodec.MediaStatePayload payload) {
        running.set(true);
        protocolReady.set(true);
        try {
            ByteBuffer frame = MessageCodec.encodeMediaState(payload);
            Message msg = MessageCodec.decode(frame);
            dispatchMessage(msg);
        } catch (Exception e) {
            throw new IllegalStateException("MEDIA_STATE_UPDATE test frame failed", e);
        }
    }

    boolean isAutoReconnectEnabledForTest() {
        return autoReconnectEnabled.get();
    }

    /**
     * Returns whether the async write queue currently holds at least one frame whose wire type
     * matches {@code type} (package-private for {@link NetworkClientStateTest}).
     */
    boolean outboundQueueContainsFrameOfTypeForTest(MessageType type) {
        if (type == null) {
            return false;
        }
        byte code = type.wireCode();
        for (ByteBuffer buf : writeQueue) {
            if (buf == null || buf.remaining() < 1) {
                continue;
            }
            if (buf.duplicate().get() == code) {
                return true;
            }
        }
        return false;
    }

    /**
     * Applies RTT from a decoded PONG origin timestamp (package-private for
     * {@link NetworkClientTelemetryTest}).
     */
    void ingestPongForTelemetryTest(Message msg) {
        if (msg == null || msg.type() != MessageType.PONG) {
            throw new IllegalArgumentException("expected PONG message");
        }
        long origin = MessageCodec.decodePingPongOrigin(msg);
        applyPingRtt(origin);
    }

    private void applyPingRtt(long originTimestamp) {
        long rtt = Math.max(0L, System.currentTimeMillis() - originTimestamp);
        runOnFxThreadIfPossible(() -> recordPingSample(rtt));
    }

    private void recordPingSample(long rtt) {
        pingSamples[pingSampleWriteIndex] = rtt;
        pingSampleWriteIndex = (pingSampleWriteIndex + 1) % PING_ROLLING_WINDOW;
        if (pingSampleCount < PING_ROLLING_WINDOW) {
            pingSampleCount++;
        }
        long sum = 0L;
        for (int i = 0; i < pingSampleCount; i++) {
            sum += pingSamples[i];
        }
        ping.set(sum / pingSampleCount);
    }

    /**
     * JavaFX {@link SimpleBooleanProperty} updates used by the UI must run on the
     * FX Application Thread; {@link #connect()}, I/O threads, and {@link #close()}
     * may run elsewhere.
     */
    private void publishTcpConnected(boolean connected) {
        runOnFxThreadIfPossible(() -> tcpConnected.set(connected));
    }

    private void publishUdpActive(boolean active) {
        runOnFxThreadIfPossible(() -> udpActive.set(active));
    }

    private static void runOnFxThreadIfPossible(Runnable action) {
        try {
            if (Platform.isFxApplicationThread()) {
                action.run();
            } else {
                Platform.runLater(action);
            }
        } catch (IllegalStateException e) {
            action.run();
        }
    }

    private void startHeartbeatPingThread() {
        Thread t = new Thread(this::heartbeatPingLoop, "distrisync-ping");
        t.setDaemon(true);
        pingThread = t;
        t.start();
    }

    private void heartbeatPingLoop() {
        while (running.get()) {
            try {
                Thread.sleep(HEARTBEAT_PING_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (!running.get()) {
                break;
            }
            try {
                enqueueFrame(MessageCodec.encodePing(System.currentTimeMillis()));
            } catch (Exception e) {
                log.trace("PING enqueue skipped: {}", e.getMessage());
            }
        }
    }

    private void suppressAutoReconnect() {
        autoReconnectEnabled.set(false);
    }

    /**
     * Restores TCP after a moderation kick once the user returns to the lobby.
     * Re-enables auto-reconnect and performs handshake + lobby fetch without re-joining a room.
     */
    public void resumeAfterSessionRevoked() {
        running.set(true);
        autoReconnectEnabled.set(true);
        Thread t = new Thread(() -> {
            try {
                synchronized (this) {
                    publishUdpActive(false);
                    protocolReady.set(false);
                    deferredJoinRoomId = "";
                    deferredJoinBoardId = "";
                    closeChannelQuietly();
                    channel = connectWithBackoff();
                    sendHandshake();
                    protocolReady.set(true);
                    if (readThread == null || !readThread.isAlive()) {
                        startReadThread();
                    }
                    if (writeThread == null || !writeThread.isAlive()) {
                        startWriteThread();
                    }
                    sendFetchLobby();
                    publishTcpConnected(true);
                    log.info("Resumed TCP to {}:{} after SESSION_REVOKED (lobby)", host, port);
                }
            } catch (IOException e) {
                log.warn("resumeAfterSessionRevoked failed: {}", e.getMessage());
                publishTcpConnected(false);
            }
        }, "distrisync-resume");
        t.setDaemon(true);
        t.start();
    }

    private void handleDisconnect(String reason) {
        if (!autoReconnectEnabled.get()) {
            log.info("Disconnected ({}) — auto-reconnect suppressed (session revoked)", reason);
            publishTcpConnected(false);
            publishUdpActive(false);
            return;
        }
        log.warn("Disconnected ({}); starting reconnect sequence…", reason);
        try {
            reconnect();
        } catch (IOException fatal) {
            log.error("All {} reconnect attempts exhausted — client is permanently offline",
                    MAX_RECONNECT_ATTEMPTS, fatal);
            publishTcpConnected(false);
            publishUdpActive(false);
            running.set(false);
            throw new RuntimeException(
                    "Permanently disconnected from " + host + ":" + port
                    + " after " + MAX_RECONNECT_ATTEMPTS + " attempts", fatal);
        }
    }

    // =========================================================================
    // Write thread
    // =========================================================================

    private void startWriteThread() {
        Thread t = new Thread(this::writeLoop, "distrisync-write");
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((thread, ex) ->
                log.error("Write thread terminated with fatal exception", ex));
        writeThread = t;
        t.start();
    }

    /**
     * Async write loop.  Runs on the {@code distrisync-write} daemon thread.
     *
     * <p>Polls {@link #writeQueue} for outgoing frames.  When the queue is
     * empty the thread parks itself for up to {@value #WRITE_PARK_NANOS} ns and
     * is woken early by any call to {@link #sendMutation}.  On a transient
     * channel write failure the undelivered frame is re-queued and the thread
     * backs off, allowing the read thread time to complete a reconnect.
     */
    private void writeLoop() {
        while (running.get()) {
            ByteBuffer frame = writeQueue.poll();

            if (frame == null) {
                // Queue is empty — park briefly; sendMutation() will unpark us.
                LockSupport.parkNanos(WRITE_PARK_NANOS);
                continue;
            }

            try {
                byte wireType = frame.get(0);
                writeFully(frame);
                if (wireType == MessageType.JOIN_ROOM.wireCode()) {
                    log.info("JOIN_ROOM written on TCP ({} bytes) — awaiting SNAPSHOT from server",
                            frame.limit());
                }
            } catch (IOException e) {
                if (!running.get()) break;
                log.warn("Write failed (channel may have dropped): {}; re-queuing frame", e.getMessage());
                // Re-offer the unsent frame so it is retried after reconnect.
                writeQueue.offer(frame);
                // Back off to let the read thread complete its reconnect.
                LockSupport.parkNanos(WRITE_ERROR_PARK_NANOS);
            }
        }

        log.info("Write loop exited");
    }

    // =========================================================================
    // Client-side shape codec
    // =========================================================================

    /**
     * Mirrors the server-side {@code ShapeCodec} for the client package.
     *
     * <p>The server encodes every shape in a JSON envelope that carries a
     * {@code "_type"} discriminator alongside all shape fields.  This codec
     * reads and writes the same format so the wire protocol remains symmetric.
     *
     * <p>A dedicated {@link Gson} instance with a {@link UUID} type adapter is
     * used because Gson's default reflection-based UUID handling produces
     * {@code {"mostSigBits":…,"leastSigBits":…}}, which is incompatible with
     * {@link UUID#fromString}.
     */
    private static final class ClientShapeCodec {

        private static final String TYPE_FIELD = "_type";

        private static final Gson GSON = new GsonBuilder()
                .registerTypeHierarchyAdapter(UUID.class, new UuidAdapter())
                .serializeNulls()
                .disableHtmlEscaping()
                .create();

        private ClientShapeCodec() {}

        /** Serialises a shape to a JSON envelope string (for outgoing MUTATION frames). */
        static String encodeMutation(Shape shape) {
            JsonObject envelope = GSON.toJsonTree(shape).getAsJsonObject();
            envelope.addProperty(TYPE_FIELD, shape.getClass().getSimpleName());
            return GSON.toJson(envelope);
        }

        /** Serialises shapes to a JSON array (for outgoing MUTATION_BATCH frames). */
        static String encodeMutationBatch(List<Shape> shapes) {
            JsonArray array = new JsonArray(shapes.size());
            for (Shape shape : shapes) {
                JsonObject envelope = GSON.toJsonTree(shape).getAsJsonObject();
                envelope.addProperty(TYPE_FIELD, shape.getClass().getSimpleName());
                array.add(envelope);
            }
            return GSON.toJson(array);
        }

        /** Deserialises all shapes from a SNAPSHOT payload. */
        static List<Shape> decodeSnapshot(String payload) {
            JsonArray array = JsonParser.parseString(payload).getAsJsonArray();
            List<Shape> shapes = new ArrayList<>(array.size());
            for (JsonElement element : array) {
                shapes.add(fromEnvelope(element.getAsJsonObject()));
            }
            return shapes;
        }

        /** Deserialises a single shape from a MUTATION payload. */
        static Shape decodeMutation(String payload) {
            return fromEnvelope(JsonParser.parseString(payload).getAsJsonObject());
        }

        /** Deserialises all shapes from a MUTATION_BATCH payload. */
        static List<Shape> decodeMutationBatch(String payload) {
            return decodeSnapshot(payload);
        }

        private static Shape fromEnvelope(JsonObject envelope) {
            JsonElement typeElement = envelope.get(TYPE_FIELD);
            if (typeElement == null) {
                throw new IllegalArgumentException(
                        "Shape envelope is missing the '" + TYPE_FIELD + "' discriminator field");
            }
            return switch (typeElement.getAsString()) {
                case "Line"          -> GSON.fromJson(envelope, Line.class);
                case "Circle"        -> GSON.fromJson(envelope, Circle.class);
                case "TextNode"      -> GSON.fromJson(envelope, TextNode.class);
                case "EraserPath"    -> GSON.fromJson(envelope, EraserPath.class);
                case "RectangleNode" -> GSON.fromJson(envelope, RectangleNode.class);
                case "EllipseNode"   -> GSON.fromJson(envelope, EllipseNode.class);
                case "ArrowNode"     -> GSON.fromJson(envelope, ArrowNode.class);
                default -> throw new IllegalArgumentException(
                        "Unknown shape type discriminator: '" + typeElement.getAsString() + "'");
            };
        }

        private static final class UuidAdapter
                implements JsonSerializer<UUID>, JsonDeserializer<UUID> {

            @Override
            public JsonElement serialize(UUID src, Type type, JsonSerializationContext ctx) {
                return new JsonPrimitive(src.toString());
            }

            @Override
            public UUID deserialize(JsonElement json, Type type, JsonDeserializationContext ctx)
                    throws JsonParseException {
                try {
                    return UUID.fromString(json.getAsString());
                } catch (IllegalArgumentException e) {
                    throw new JsonParseException("Invalid UUID string: " + json.getAsString(), e);
                }
            }
        }
    }
}
