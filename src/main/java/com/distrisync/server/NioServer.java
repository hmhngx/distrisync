package com.distrisync.server;

import com.distrisync.model.Shape;
import com.distrisync.server.backplane.BackplaneEnvelope;
import com.distrisync.server.backplane.BackplaneEventDedup;
import com.distrisync.server.backplane.RedisBackplanePublisher;
import com.distrisync.server.backplane.RedisBackplaneSubscriber;
import com.distrisync.protocol.Message;
import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.MessageType;
import com.distrisync.protocol.PartialMessageException;
import com.distrisync.protocol.RoomPermissions;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central-authority server for the DistriSync collaborative whiteboard.
 *
 * <h2>Architecture</h2>
 * A single-threaded NIO event loop driven by a {@link Selector}.  All client
 * connections share the same selector; room-level isolation is enforced
 * entirely in software via the {@link RoomManager} routing layer.
 *
 * <h2>Multi-tenant connection lifecycle</h2>
 * <ol>
 *   <li><b>Accept</b> – a new {@link SocketChannel} is registered for
 *       {@code OP_READ} with a fresh {@link ClientSession}.  The client sends
 *       {@code HANDSHAKE} (lobby + {@code LOBBY_STATE}), then {@code JOIN_ROOM}
 *       for a canvas {@code SNAPSHOT}.</li>
 *   <li><b>HANDSHAKE</b> – the first frame must be a {@code HANDSHAKE} with
 *       {@code authorName} and {@code clientId}.  The client is placed in the
 *       global discovery lobby; the server pushes {@code LOBBY_STATE} to all
 *       lobby clients.</li>
 *   <li><b>JOIN_ROOM</b> – client leaves the lobby and enters a canvas room;
 *       the server sends that room's {@code SNAPSHOT}.</li>
 *   <li><b>LEAVE_ROOM</b> – client returns to the lobby and receives a fresh
 *       {@code LOBBY_STATE}.</li>
 *   <li><b>Read / mutation</b> – subsequent frames are dispatched to the
 *       room ({@code session.roomId}) and board ({@code session.currentBoardId}).
 *       Durable canvas ops use {@link RoomContext#getBoard}; relayed frames are
 *       broadcast only to peers in the same room <em>and</em> board.</li>
 *   <li><b>Write</b> – {@code OP_WRITE} is armed only when a previous write
 *       was partial (TCP send-buffer full).</li>
 *   <li><b>Disconnect</b> – the key is removed from the lobby or its canvas
 *       room before the channel is closed.</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 * {@code NioServer} itself is single-threaded.  {@link CanvasStateManager}
 * and {@link RoomManager} use {@link java.util.concurrent.ConcurrentHashMap}
 * and may safely be called from other threads without coordination.
 */
public final class NioServer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(NioServer.class);

    /** Selector attachment marking the shared UDP {@link DatagramChannel} key (not a {@link ClientSession}). */
    private static final Object UDP_CHANNEL_ATTACHMENT = new Object();

    /** Fixed prefix size for UDP token / {@link ClientSession#clientId} relay header. */
    private static final int UDP_IDENTITY_BYTES = 36;

    private final int port;
    private final RoomManager roomManager;

    /**
     * Optional WAL engine for {@link com.distrisync.protocol.MessageType#DELETE_ROOM} durable teardown;
     * {@code null} in tests that only exercise in-memory routing.
     */
    private final WalManager walManager;

    /**
     * Optional Redis Pub/Sub backplane for cross-node mutation fanout; {@code null} when disabled.
     * Cluster wiring is resolved at bootstrap via {@link ServerEnvironment} ({@code REDIS_HOST},
     * {@code REDIS_PORT}, {@code DISTRISYNC_REDIS_URI}, {@code NODE_ID}) in {@link WhiteboardServer}.
     */
    private final RedisBackplanePublisher backplanePublisher;

    /**
     * Optional Redis Pub/Sub consumer; {@code null} when disabled. Enqueues into {@link #remoteMailbox}.
     * See {@link #backplanePublisher} for environment configuration.
     */
    private final RedisBackplaneSubscriber backplaneSubscriber;

    /** Server instance id for discarding self-originated backplane events (from {@code NODE_ID} or generated). */
    private final String localNodeId;

    /** Idempotency guard for backplane {@code eventId} values (publish + ingest). */
    private final BackplaneEventDedup backplaneEventDedup = new BackplaneEventDedup();

    /**
     * Cross-thread remote mutation delivery: the Redis listener thread offers envelopes;
     * {@link #processRemoteMailbox()} drains on the selector thread.
     */
    final ConcurrentLinkedQueue<BackplaneEnvelope> remoteMailbox = new ConcurrentLinkedQueue<>();

    /**
     * Maps {@link ClientSession#udpToken} to the owning session for UDP registration and audio relay.
     * {@link ConcurrentHashMap} provides lock-free reads on the selector thread during UDP fan-out.
     */
    private final ConcurrentHashMap<String, ClientSession> udpTokenToSession = new ConcurrentHashMap<>();

    /**
     * Direct buffer for UDP receive / in-place relay header rewrite — avoids JVM heap copies on the
     * I/O path (selector thread only).
     */
    private final ByteBuffer udpBuffer = ByteBuffer.allocateDirect(1024);

    /** Scratch for the 36-byte UDP token prefix; avoids per-datagram {@code byte[]} allocation. */
    private final byte[] udpTokenScratch = new byte[UDP_IDENTITY_BYTES];

    /** Last token bytes matching {@link #udpCachedToken}; avoids {@code new String} on every relay frame. */
    private final byte[] udpLastTokenScratch = new byte[UDP_IDENTITY_BYTES];
    private boolean udpTokenStringCacheValid;
    private String udpCachedToken = "";

    /** Filled on the selector thread for {@link #writeClientIdPrefix36} — avoids {@link CharBuffer#wrap}. */
    private final CharBuffer udpClientIdCharScratch = CharBuffer.allocate(256);

    /**
     * Reused on the selector thread to write the 36-byte client-id prefix without {@code String#getBytes}.
     */
    private final CharsetEncoder udpClientIdEncoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);

    /** Bytes handed to the TCP board fan-out and UDP audio relay (per-peer datagram octets). */
    private final AtomicLong bytesRouted = new AtomicLong();

    /** Connected TCP client channels (excluding the accept socket and UDP channel). */
    private final AtomicInteger activeTcpSockets = new AtomicInteger();

    /**
     * Completes with the actual TCP port the server bound to.  Useful when
     * {@code port == 0} is passed to let the OS assign an ephemeral port.
     */
    private final CompletableFuture<Integer> boundPortFuture = new CompletableFuture<>();

    /**
     * Set to {@code true} by {@link #stop()} to break the selector loop
     * without relying on thread interruption.
     */
    private volatile boolean stopped = false;

    private HttpServer metricsServer;

    /**
     * The live {@link Selector}; stored so {@link #stop()} can call
     * {@link Selector#wakeup()} even while the event loop is blocked in
     * {@link Selector#select()}.
     */
    private volatile Selector selector;

    /**
     * Cross-thread lobby broadcasts: {@link RoomManager} may call the fanout from
     * the storage lifecycle thread; frames are drained on the selector thread.
     */
    private final ConcurrentLinkedQueue<ByteBuffer> pendingLobbyFrames = new ConcurrentLinkedQueue<>();

    /**
     * @param port        TCP port to bind; must be in the range [0, 65535]
     * @param roomManager the multi-tenant routing registry
     */
    public NioServer(int port, RoomManager roomManager) {
        this(port, roomManager, null, null, null, null);
    }

    /**
     * @param walManager  WAL used when servicing {@link MessageType#DELETE_ROOM}; may be {@code null}
     */
    public NioServer(int port, RoomManager roomManager, WalManager walManager) {
        this(port, roomManager, walManager, null, null, null);
    }

    /**
     * @param backplanePublisher Redis mutation fanout; {@code null} disables backplane publish
     */
    public NioServer(
            int port,
            RoomManager roomManager,
            WalManager walManager,
            RedisBackplanePublisher backplanePublisher) {
        this(port, roomManager, walManager, backplanePublisher, null, null);
    }

    /**
     * @param backplaneSubscriber Redis mutation ingest; {@code null} disables backplane subscribe
     * @param localNodeId         node identity for self-echo discard; generated when {@code null}
     */
    public NioServer(
            int port,
            RoomManager roomManager,
            WalManager walManager,
            RedisBackplanePublisher backplanePublisher,
            RedisBackplaneSubscriber backplaneSubscriber,
            String localNodeId) {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid port: " + port);
        if (roomManager == null)       throw new IllegalArgumentException("roomManager must not be null");
        this.port                = port;
        this.roomManager         = roomManager;
        this.walManager          = walManager;
        this.backplanePublisher  = backplanePublisher;
        this.backplaneSubscriber = backplaneSubscriber;
        this.localNodeId         = resolveLocalNodeId(backplanePublisher, localNodeId);
    }

    private static String resolveLocalNodeId(
            RedisBackplanePublisher backplanePublisher,
            String localNodeId) {
        if (localNodeId != null && !localNodeId.isBlank()) {
            return localNodeId;
        }
        if (backplanePublisher != null) {
            return backplanePublisher.originNodeId();
        }
        return UUID.randomUUID().toString();
    }

    // =========================================================================
    // Main event loop
    // =========================================================================

    /**
     * Starts the NIO event loop.  Blocks the calling thread until interrupted
     * or a fatal {@link IOException} is encountered.
     */
    @Override
    public void run() {
        log.info("NioServer starting on port {}", port);

        startMetricsServer();

        try (ServerSocketChannel serverChannel = ServerSocketChannel.open();
             DatagramChannel datagramChannel = DatagramChannel.open();
             Selector selector = Selector.open()) {

            this.selector = selector;

            final Selector selectorRef = selector;
            roomManager.setLobbyFanout(frame -> {
                pendingLobbyFrames.offer(frame);
                selectorRef.wakeup();
            });
            roomManager.setRoomDeletionClientHooks(this::revokeUdpAdmission, (s, k) -> flushWriteQueue(s, k));
            roomManager.setOutboundFrameHook(
                    (s, frame, k) -> safeEnqueue(s, k, frame, OutboundClass.CRITICAL));
            if (backplaneSubscriber != null || backplanePublisher != null) {
                if (backplaneSubscriber != null) {
                    backplaneSubscriber.bind(remoteMailbox, selectorRef::wakeup);
                }
                roomManager.setRoomCreatedHook(roomId -> {
                    if (backplaneSubscriber != null) {
                        backplaneSubscriber.subscribeRoom(roomId);
                    }
                    publishStateRequest(roomId);
                });
            }

            serverChannel.configureBlocking(false);
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            serverChannel.bind(new InetSocketAddress(port));
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            int actualPort = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();

            datagramChannel.configureBlocking(false);
            datagramChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            datagramChannel.bind(new InetSocketAddress(actualPort));
            datagramChannel.register(selector, SelectionKey.OP_READ, UDP_CHANNEL_ATTACHMENT);

            boundPortFuture.complete(actualPort);

            log.info("NioServer listening — tcpUdpPort={} (TCP + UDP) minConcurrentClients=4", actualPort);

            ScheduledExecutorService trafficHeartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "distrisync-traffic-metrics");
                t.setDaemon(true);
                return t;
            });
            try {
                trafficHeartbeat.scheduleAtFixedRate(this::emitTrafficHeartbeat, 10, 10, TimeUnit.SECONDS);

                while (!stopped && !Thread.currentThread().isInterrupted()) {
                    selector.select();
                    drainPendingLobbyBroadcasts();
                    processRemoteMailbox();

                    Set<SelectionKey> selected = selector.selectedKeys();
                    for (SelectionKey key : selected) {
                        try {
                            dispatch(key, selector);
                        } catch (Exception e) {
                            log.error("Unexpected error dispatching key — closing channel: {}", e.getMessage(), e);
                            closeKey(key);
                        }
                    }
                    selected.clear();
                }
            } finally {
                trafficHeartbeat.shutdownNow();
            }

        } catch (IOException e) {
            log.error("NioServer fatal I/O error — shutting down", e);
            boundPortFuture.completeExceptionally(e);
        } finally {
            stopMetricsServer();
        }

        log.info("NioServer stopped");
    }

    private void startMetricsServer() {
        int metricsPort = resolveMetricsPort();
        try {
            metricsServer = createAndStartMetricsServer(metricsPort);
        } catch (IOException e) {
            if (metricsPort != 0) {
                log.warn("Metrics HTTP server failed on port {} ({}), retrying on ephemeral port",
                        metricsPort, e.getMessage());
                try {
                    metricsServer = createAndStartMetricsServer(0);
                } catch (IOException retry) {
                    log.error("Failed to start metrics HTTP server: {}", retry.getMessage(), retry);
                }
            } else {
                log.error("Failed to start metrics HTTP server: {}", e.getMessage(), e);
            }
        }
    }

    private HttpServer createAndStartMetricsServer(int metricsPort) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(metricsPort), 0);
        server.createContext("/metrics", this::handleMetricsRequest);
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "distrisync-metrics-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        int boundPort = server.getAddress().getPort();
        log.info("Prometheus metrics listening on port {}", boundPort);
        return server;
    }

    private void stopMetricsServer() {
        HttpServer server = metricsServer;
        if (server != null) {
            server.stop(0);
            metricsServer = null;
        }
    }

    private static int resolveMetricsPort() {
        String portEnv = System.getenv("METRICS_PORT");
        if (portEnv == null || portEnv.isBlank()) {
            return 8080;
        }
        try {
            int port = Integer.parseInt(portEnv.trim());
            if (port < 0 || port > 65535) {
                throw new NumberFormatException("out of range");
            }
            return port;
        } catch (NumberFormatException e) {
            log.warn("Invalid METRICS_PORT '{}' — using default 8080", portEnv);
            return 8080;
        }
    }

    private void handleMetricsRequest(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        byte[] body = ServerMetrics.formatPrometheusText().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", ServerMetrics.PROMETHEUS_CONTENT_TYPE);
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    // =========================================================================
    // Dispatch
    // =========================================================================

    private void dispatch(SelectionKey key, Selector selector) throws IOException {
        if (!key.isValid()) return;

        if (key.isAcceptable()) {
            handleAccept((ServerSocketChannel) key.channel(), selector);
            return;
        }

        if (key.attachment() == UDP_CHANNEL_ATTACHMENT) {
            if (key.isReadable()) {
                handleUdpRead(key);
            }
            return;
        }

        if (key.isReadable()) {
            handleRead(key, selector);
        }
        if (key.isValid() && key.isWritable()) {
            handleWrite(key);
        }
    }

    // =========================================================================
    // Accept
    // =========================================================================

    /**
     * Registers the new channel for reads and attaches a fresh
     * {@link ClientSession}.  The client must send {@code HANDSHAKE} then
     * {@code JOIN_ROOM} before receiving a canvas {@code SNAPSHOT}.
     */
    private void handleAccept(ServerSocketChannel serverChannel, Selector selector) throws IOException {
        SocketChannel clientChannel = serverChannel.accept();
        if (clientChannel == null) {
            return;
        }

        clientChannel.configureBlocking(false);
        clientChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
        clientChannel.setOption(StandardSocketOptions.SO_SNDBUF, 64 * 1024);
        clientChannel.setOption(StandardSocketOptions.SO_RCVBUF, 64 * 1024);

        ClientSession session = new ClientSession();
        clientChannel.register(selector, SelectionKey.OP_READ, session);

        activeTcpSockets.incrementAndGet();
        log.info("Client connected  session={} remote={} — awaiting HANDSHAKE",
                session.sessionId, clientChannel.getRemoteAddress());
    }

    // =========================================================================
    // Read
    // =========================================================================

    private void handleRead(SelectionKey key, Selector selector) {
        ClientSession session = (ClientSession) key.attachment();
        SocketChannel channel = (SocketChannel) key.channel();

        int bytesRead;
        try {
            bytesRead = channel.read(session.readBuffer);
        } catch (IOException e) {
            log.warn("Read error session={}: {}", session.sessionId, e.getMessage());
            closeKey(key);
            return;
        }

        if (bytesRead == -1) {
            log.info("Client closed connection  session={}", session.sessionId);
            closeKey(key);
            return;
        }

        if (bytesRead == 0) {
            return;
        }

        log.debug("Read {} bytes from session={}", bytesRead, session.sessionId);

        session.readBuffer.flip();
        try {
            while (session.readBuffer.hasRemaining()) {
                Message msg;
                try {
                    msg = MessageCodec.decode(session.readBuffer);
                } catch (PartialMessageException e) {
                    log.debug("Partial frame session={} bytesNeeded={}",
                            session.sessionId, e.getBytesNeeded());
                    break;
                }
                processMessage(msg, key, selector);
                if (!key.isValid()) {
                    return;
                }
            }
        } finally {
            session.readBuffer.compact();
        }
    }

    private void emitTrafficHeartbeat() {
        log.info("[METRICS] Traffic routed: {} bytes | Active Rooms: {} | Active Sockets: {}.",
                bytesRouted.get(),
                roomManager.getActiveRoomCount(),
                activeTcpSockets.get());
    }

    private static String clientLogLabel(ClientSession session) {
        String id = session.clientId;
        if (id != null && !id.isBlank()) {
            return id;
        }
        return session.sessionId.toString();
    }

    // =========================================================================
    // Message dispatch
    // =========================================================================

    private void processMessage(Message msg, SelectionKey senderKey, Selector selector) {
        ClientSession session = (ClientSession) senderKey.attachment();

        switch (msg.type()) {
            case HANDSHAKE -> {
                if (session.handshakeComplete) {
                    log.warn("Duplicate HANDSHAKE session={} — ignoring", session.sessionId);
                    break;
                }
                MessageCodec.HandshakePayload hp = MessageCodec.decodeHandshake(msg);
                session.authorName = hp.authorName();
                session.clientId   = hp.clientId();
                ByteBuffer selfJoin = MessageCodec.encodeRoomMemberJoined(
                        session.clientId, session.authorName);
                if (safeEnqueue(session, senderKey, selfJoin, OutboundClass.CRITICAL)
                        != EnqueueResult.OVERFLOW_DISCONNECT) {
                    flushWriteQueue(session, senderKey);
                }
                session.roomId     = "";
                session.currentBoardId = "";
                session.handshakeComplete = true;
                session.connectedAtMillis = System.currentTimeMillis();
                session.permissions = RoomPermissions.SPECTATOR;

                roomManager.registerHandshakeToLobby(senderKey);

                log.info("HANDSHAKE session={} authorName='{}' clientId='{}' → lobby",
                        session.sessionId, session.authorName, session.clientId);
            }

            case JOIN_ROOM -> {
                if (!session.handshakeComplete) {
                    log.warn("JOIN_ROOM before HANDSHAKE session={}", session.sessionId);
                    break;
                }
                MessageCodec.JoinRoomPayload jp;
                try {
                    jp = MessageCodec.decodeJoinRoom(msg);
                } catch (Exception e) {
                    log.warn("Malformed JOIN_ROOM session={}: {}", session.sessionId, e.getMessage());
                    break;
                }
                String rid = jp.roomId();
                if (rid.isBlank()) {
                    log.warn("Blank JOIN_ROOM session={}", session.sessionId);
                    break;
                }
                try {
                    RoomContext room = roomManager.assignClientToRoom(senderKey, rid, session.roomId);
                    revokeUdpAdmission(session);
                    String udpToken = UUID.randomUUID().toString();
                    session.udpToken = udpToken;
                    udpTokenToSession.put(udpToken, session);
                    session.roomId = rid;
                    String requestedBoardId = jp.initialBoardId();
                    String resolvedBoardId = resolveJoinBoardId(room, requestedBoardId, session.permissions);
                    if (!resolvedBoardId.equals(requestedBoardId)) {
                        log.warn("JOIN_ROOM board override — board creation locked  session={} requested='{}' resolved='{}'",
                                session.sessionId, requestedBoardId, resolvedBoardId);
                    }
                    session.currentBoardId = resolvedBoardId;
                    ByteBuffer roleFrame = MessageCodec.encodeRoleUpdate(
                            session.clientId, session.permissions, room.hostClientId);
                    broadcastToRoom(rid, roleFrame, null, OutboundClass.CRITICAL);
                    sendSnapshot(session, senderKey, room);
                    sendUdpAdmission(session, senderKey, udpToken);
                    broadcastBoardList(room);
                    broadcastToRoom(rid,
                            MessageCodec.encodeRoomMemberJoined(session.clientId, session.authorName),
                            senderKey, OutboundClass.CRITICAL);
                    fanoutBoardSwitch(room, session.clientId, session.currentBoardId, senderKey);
                    hydrateBoardPresenceForJoiner(room, senderKey, session);
                    ByteBuffer lockFrame = MessageCodec.encodeBoardLockState(room.isBoardCreationLocked);
                    if (safeEnqueue(session, senderKey, lockFrame, OutboundClass.CRITICAL)
                            != EnqueueResult.OVERFLOW_DISCONNECT) {
                        flushWriteQueue(session, senderKey);
                    }
                    log.info("[TCP] Client {} joined Room '{}'. Active users: {}.",
                            clientLogLabel(session), rid, room.getActiveClientCount());
                } catch (IllegalArgumentException e) {
                    log.warn("JOIN_ROOM rejected session={}: {}", session.sessionId, e.getMessage());
                }
            }

            case LEAVE_ROOM -> {
                if (!session.handshakeComplete) {
                    break;
                }
                if (session.roomId.isBlank()) {
                    log.debug("LEAVE_ROOM no-op — already in lobby session={}", session.sessionId);
                    break;
                }
                String cur = session.roomId;
                String departingClientId = session.clientId;
                if (!departingClientId.isBlank()) {
                    broadcastToRoom(cur, MessageCodec.encodeRoomMemberLeft(departingClientId),
                            senderKey, OutboundClass.CRITICAL);
                }
                roomManager.returnClientToLobby(senderKey, cur);
                revokeUdpAdmission(session);
                session.roomId = "";
                session.currentBoardId = "";
                session.permissions = RoomPermissions.SPECTATOR;
                maybePromoteHostAfterDepart(cur, departingClientId);
                log.info("LEAVE_ROOM session={} → lobby", session.sessionId);
            }

            case FETCH_LOBBY -> {
                if (!session.handshakeComplete) {
                    log.trace("FETCH_LOBBY before HANDSHAKE ignored session={}", session.sessionId);
                    break;
                }
                try {
                    MessageCodec.decodeFetchLobby(msg);
                } catch (Exception e) {
                    log.warn("Malformed FETCH_LOBBY session={}: {}", session.sessionId, e.getMessage());
                    break;
                }
                ByteBuffer lobbyFrame = MessageCodec.encodeLobbyState(roomManager.snapshotLobbyRoomEntries());
                if (safeEnqueue(session, senderKey, lobbyFrame, OutboundClass.CRITICAL)
                        == EnqueueResult.OVERFLOW_DISCONNECT) {
                    break;
                }
                if (!flushWriteQueue(session, senderKey)) {
                    log.error("FETCH_LOBBY LOBBY_STATE flush failed for session={} — closing connection",
                            session.sessionId);
                    closeKey(senderKey);
                }
            }

            case DELETE_ROOM -> {
                if (!RoomPermissions.canDeleteRoom(session.permissions)) {
                    return;
                }
                if (!session.handshakeComplete) {
                    log.warn("DELETE_ROOM before HANDSHAKE session={}", session.sessionId);
                    break;
                }
                MessageCodec.DeleteRoomPayload dp;
                try {
                    dp = MessageCodec.decodeDeleteRoom(msg);
                } catch (Exception e) {
                    log.warn("Malformed DELETE_ROOM session={}: {}", session.sessionId, e.getMessage());
                    break;
                }
                String rid = dp.roomId();
                if (rid.isBlank()) {
                    log.warn("Blank DELETE_ROOM session={}", session.sessionId);
                    break;
                }
                // In a canvas room: only the current room may be deleted. From the lobby: allow
                // deleting any listed room (lobby UI sends DELETE_ROOM before JOIN).
                if (!session.roomId.isBlank() && !rid.equals(session.roomId)) {
                    log.warn("DELETE_ROOM room mismatch session={} rid='{}' currentRoom='{}'",
                            session.sessionId, rid, session.roomId);
                    break;
                }
                roomManager.deleteRoom(rid, walManager);
                broadcastLobbyState();
                log.info("DELETE_ROOM completed  roomId='{}' session={}", rid, session.sessionId);
            }

            case MUTATION -> {
                if (!RoomPermissions.canDraw(session.permissions)) {
                    return;
                }
                RoomContext room = resolveRoom(session, senderKey);
                if (room == null) return;
                if (session.currentBoardId.isBlank()) {
                    log.warn("MUTATION with no active board session={} — ignoring", session.sessionId);
                    return;
                }

                Shape shape;
                try {
                    shape = ShapeCodec.decodeMutation(msg.payload());
                } catch (Exception e) {
                    log.warn("Malformed MUTATION payload from session={}: {}", session.sessionId, e.getMessage());
                    closeKey(senderKey);
                    return;
                }

                CanvasStateManager board = room.getBoard(session.currentBoardId);
                boolean applied = board.applyMutation(shape);

                if (applied) {
                    log.debug("MUTATION accepted  type={} id={} ts={} author='{}' room='{}' board='{}' from={}",
                            shape.getClass().getSimpleName(), shape.objectId(),
                            shape.timestamp(), shape.authorName(), session.roomId, session.currentBoardId,
                            session.sessionId);

                    // Persist to WAL before broadcasting so the record survives a crash
                    // between the apply and the broadcast.
                    roomManager.appendToWal(session.roomId, session.currentBoardId, msg);

                    if (backplanePublisher != null) {
                        String eventId = UUID.randomUUID().toString();
                        backplaneEventDedup.tryRecord(eventId);
                        backplanePublisher.publish(new BackplaneEnvelope(
                                eventId,
                                backplanePublisher.originNodeId(),
                                session.roomId,
                                session.currentBoardId,
                                MessageCodec.encode(msg)));
                    }

                    ByteBuffer frame = MessageCodec.encode(msg);
                    broadcastToBoard(session.roomId, session.currentBoardId, frame, senderKey, OutboundClass.CRITICAL);
                } else {
                    log.debug("MUTATION rejected (stale)  id={} from={}", shape.objectId(), session.sessionId);
                }
            }

            case SHAPE_DELETE -> {
                if (!RoomPermissions.canDraw(session.permissions)) {
                    return;
                }
                RoomContext room = resolveRoom(session, senderKey);
                if (room == null) return;
                if (session.currentBoardId.isBlank()) {
                    log.warn("SHAPE_DELETE with no active board session={} — ignoring", session.sessionId);
                    return;
                }

                UUID shapeId;
                try {
                    JsonObject p = JsonParser.parseString(msg.payload()).getAsJsonObject();
                    shapeId = UUID.fromString(p.get("shapeId").getAsString());
                } catch (Exception e) {
                    log.warn("Malformed SHAPE_DELETE payload from session={}: {}", session.sessionId, e.getMessage());
                    return;
                }

                CanvasStateManager board = room.getBoard(session.currentBoardId);
                if (!board.deleteShape(shapeId)) {
                    log.debug("SHAPE_DELETE no-op  shapeId={} session={}", shapeId, session.sessionId);
                    return;
                }

                roomManager.appendToWal(session.roomId, session.currentBoardId, msg);
                ByteBuffer frame = MessageCodec.encode(msg);
                broadcastToBoard(session.roomId, session.currentBoardId, frame, senderKey, OutboundClass.CRITICAL);
            }

            case SHAPE_START, SHAPE_UPDATE, SHAPE_COMMIT -> {
                if (!RoomPermissions.canDraw(session.permissions)) {
                    return;
                }
                RoomContext room = resolveRoom(session, senderKey);
                if (room == null) return;
                if (session.currentBoardId.isBlank()) {
                    log.warn("{} with no active board session={} — ignoring", msg.type(), session.sessionId);
                    return;
                }
                log.debug("{} relayed  room='{}' board='{}' from session={}",
                        msg.type(), session.roomId, session.currentBoardId, session.sessionId);
                ByteBuffer frame = MessageCodec.encode(msg);
                OutboundClass shapeCls = msg.type() == MessageType.SHAPE_UPDATE
                        ? OutboundClass.EPHEMERAL : OutboundClass.CRITICAL;
                broadcastToBoard(session.roomId, session.currentBoardId, frame, senderKey, shapeCls);
            }

            case TEXT_UPDATE -> {
                RoomContext room = resolveRoom(session, senderKey);
                if (room == null) return;
                if (session.currentBoardId.isBlank()) {
                    log.warn("TEXT_UPDATE with no active board session={} — ignoring", session.sessionId);
                    return;
                }
                log.debug("TEXT_UPDATE relayed  room='{}' board='{}' from session={}",
                        session.roomId, session.currentBoardId, session.sessionId);
                ByteBuffer frame = MessageCodec.encode(msg);
                broadcastToBoard(session.roomId, session.currentBoardId, frame, senderKey, OutboundClass.EPHEMERAL);
            }

            case CLEAR_USER_SHAPES -> {
                if (!RoomPermissions.canDraw(session.permissions)) {
                    return;
                }
                RoomContext room = resolveRoom(session, senderKey);
                if (room == null) return;
                if (session.currentBoardId.isBlank()) {
                    log.warn("CLEAR_USER_SHAPES with no active board session={} — ignoring", session.sessionId);
                    return;
                }

                String targetClientId;
                try {
                    targetClientId = MessageCodec.decodeClearUserShapes(msg);
                } catch (Exception e) {
                    log.warn("Malformed CLEAR_USER_SHAPES payload from session={}: {}", session.sessionId, e.getMessage());
                    return;
                }
                CanvasStateManager board = room.getBoard(session.currentBoardId);
                board.clearUserShapes(targetClientId);
                log.debug("CLEAR_USER_SHAPES  room='{}' board='{}' from session={} targetClientId='{}'",
                        session.roomId, session.currentBoardId, session.sessionId, targetClientId);

                // Persist to WAL so the per-user purge survives a restart.
                roomManager.appendToWal(session.roomId, session.currentBoardId, msg);

                ByteBuffer frame = MessageCodec.encodeClearUserShapes(targetClientId);
                broadcastToBoard(session.roomId, session.currentBoardId, frame, senderKey, OutboundClass.CRITICAL);
            }

            case UNDO_REQUEST -> {
                if (!RoomPermissions.canDraw(session.permissions)) {
                    return;
                }
                RoomContext room = resolveRoom(session, senderKey);
                if (room == null) return;
                if (session.currentBoardId.isBlank()) {
                    log.warn("UNDO_REQUEST with no active board session={} — ignoring", session.sessionId);
                    return;
                }

                UUID shapeId;
                try {
                    JsonObject p = JsonParser.parseString(msg.payload()).getAsJsonObject();
                    shapeId = UUID.fromString(p.get("shapeId").getAsString());
                } catch (Exception e) {
                    log.warn("Malformed UNDO_REQUEST payload from session={}: {}", session.sessionId, e.getMessage());
                    return;
                }

                CanvasStateManager board = room.getBoard(session.currentBoardId);
                boolean deleted = board.deleteShape(shapeId);
                if (deleted) {
                    log.debug("UNDO_REQUEST accepted  shapeId={} room='{}' board='{}' author='{}' session={}",
                            shapeId, session.roomId, session.currentBoardId, session.authorName, session.sessionId);
                    record ShapeDeletePayload(String shapeId) {}
                    var deletePayload = new ShapeDeletePayload(shapeId.toString());
                    Message deleteMsg = new Message(
                            MessageType.SHAPE_DELETE, MessageCodec.gson().toJson(deletePayload));

                    // Persist the SHAPE_DELETE outcome (not the UNDO_REQUEST trigger) so
                    // recovery can apply a clean deleteShape() without re-evaluating intent.
                    roomManager.appendToWal(session.roomId, session.currentBoardId, deleteMsg);

                    ByteBuffer frame = MessageCodec.encode(deleteMsg);
                    broadcastToBoard(session.roomId, session.currentBoardId, frame, senderKey, OutboundClass.CRITICAL);
                } else {
                    log.debug("UNDO_REQUEST no-op (shape not found)  shapeId={} session={}",
                            shapeId, session.sessionId);
                }
            }

            case SWITCH_BOARD -> {
                if (!session.handshakeComplete) {
                    log.warn("SWITCH_BOARD before HANDSHAKE session={}", session.sessionId);
                    break;
                }
                RoomContext room = resolveRoom(session, senderKey);
                if (room == null) return;
                String boardId;
                try {
                    boardId = MessageCodec.decodeSwitchBoard(msg);
                } catch (Exception e) {
                    log.warn("Malformed SWITCH_BOARD session={}: {}", session.sessionId, e.getMessage());
                    break;
                }
                String bid = boardId != null ? boardId.strip() : "";
                if (bid.isBlank()) {
                    log.warn("Blank SWITCH_BOARD session={}", session.sessionId);
                    break;
                }
                boolean boardExisted = room.getActiveBoardIds().contains(bid);
                if (!boardExisted && room.isBoardCreationLocked
                        && !RoomPermissions.canManageRoom(session.permissions)) {
                    log.warn("SWITCH_BOARD rejected — board creation locked  session={} board='{}'",
                            session.sessionId, bid);
                    break;
                }
                session.currentBoardId = bid;
                sendSnapshot(session, senderKey, room);
                if (!boardExisted) {
                    broadcastBoardList(room);
                }
                fanoutBoardSwitch(room, session.clientId, bid, senderKey);
                log.info("[STATE] Client {} switched to Board '{}'. Hydrating state.",
                        clientLogLabel(session), bid);
            }

            case TOGGLE_BOARD_LOCK -> {
                if (!session.handshakeComplete) {
                    log.warn("TOGGLE_BOARD_LOCK before HANDSHAKE session={}", session.sessionId);
                    break;
                }
                if (!RoomPermissions.canManageRoom(session.permissions)) {
                    log.debug("TOGGLE_BOARD_LOCK denied — insufficient permissions session={}",
                            session.sessionId);
                    break;
                }
                RoomContext lockRoom = resolveRoom(session, senderKey);
                if (lockRoom == null) {
                    return;
                }
                boolean locked;
                try {
                    locked = MessageCodec.decodeBoardLockCommand(msg).locked();
                } catch (Exception e) {
                    log.warn("Malformed TOGGLE_BOARD_LOCK session={}: {}", session.sessionId, e.getMessage());
                    break;
                }
                lockRoom.isBoardCreationLocked = locked;
                fanoutBoardLockState(lockRoom, locked);
                log.info("Board creation lock set  room='{}' locked={} by={}",
                        lockRoom.roomId, locked, clientLogLabel(session));
            }

            case LOBBY_STATE -> log.trace("Ignoring client-originated LOBBY_STATE echo session={}", session.sessionId);

            case PING -> {
                if (!session.handshakeComplete) {
                    log.trace("PING before HANDSHAKE ignored session={}", session.sessionId);
                    break;
                }
                long origin;
                try {
                    origin = MessageCodec.decodePingPongOrigin(msg);
                } catch (Exception e) {
                    log.warn("Malformed PING session={}: {}", session.sessionId, e.getMessage());
                    break;
                }
                if (safeEnqueue(session, senderKey, MessageCodec.encodePong(origin), OutboundClass.EPHEMERAL)
                        == EnqueueResult.OVERFLOW_DISCONNECT) {
                    break;
                }
                if (!flushWriteQueue(session, senderKey)) {
                    log.error("PONG flush failed for session={} — closing connection", session.sessionId);
                    closeKey(senderKey);
                }
            }

            case VOICE_STATE -> {
                if (!session.handshakeComplete) {
                    log.warn("VOICE_STATE before HANDSHAKE session={}", session.sessionId);
                    break;
                }
                RoomContext room = resolveRoom(session, senderKey);
                if (room == null) {
                    return;
                }
                MessageCodec.VoiceStatePayload vs;
                try {
                    vs = MessageCodec.decodeVoiceState(msg);
                } catch (Exception e) {
                    log.warn("Malformed VOICE_STATE session={}: {}", session.sessionId, e.getMessage());
                    break;
                }
                if (!session.clientId.equals(vs.clientId())) {
                    log.warn("VOICE_STATE clientId mismatch session={} payload='{}' sessionClientId='{}'",
                            session.sessionId, vs.clientId(), session.clientId);
                    break;
                }
                session.micMuted = vs.isMuted();
                log.debug("VOICE_STATE relayed  room='{}' clientId='{}' muted={}",
                        session.roomId, vs.clientId(), vs.isMuted());
                ByteBuffer frame = MessageCodec.encodeVoiceState(vs.clientId(), vs.isMuted());
                broadcastToRoom(session.roomId, frame, senderKey, OutboundClass.CRITICAL);
            }

            case CURSOR_SYNC -> {
                if (!session.handshakeComplete) {
                    log.warn("CURSOR_SYNC before HANDSHAKE session={}", session.sessionId);
                    break;
                }
                RoomContext cursorRoom = resolveRoom(session, senderKey);
                if (cursorRoom == null) {
                    return;
                }
                MessageCodec.CursorSyncPayload cs;
                try {
                    cs = MessageCodec.decodeCursorSync(msg);
                } catch (Exception e) {
                    log.warn("Malformed CURSOR_SYNC session={}: {}", session.sessionId, e.getMessage());
                    break;
                }
                if (!session.clientId.equals(cs.clientId())) {
                    log.warn("CURSOR_SYNC clientId mismatch session={} payload='{}' sessionClientId='{}'",
                            session.sessionId, cs.clientId(), session.clientId);
                    break;
                }
                ByteBuffer cursorFrame = MessageCodec.encodeCursorSync(
                        cs.clientId(), cs.authorName(), cs.x(), cs.y());
                broadcastToRoom(session.roomId, cursorFrame, senderKey);
                publishPresenceEnvelope(session.roomId, msg);
            }

            case MODERATION_ACTION -> {
                if (!RoomPermissions.canManageUsers(session.permissions)) {
                    return;
                }
                if (!session.handshakeComplete) {
                    log.warn("MODERATION_ACTION before HANDSHAKE session={}", session.sessionId);
                    break;
                }
                RoomContext modRoom = resolveRoom(session, senderKey);
                if (modRoom == null) {
                    return;
                }
                MessageCodec.ModerationActionPayload modPayload;
                try {
                    modPayload = MessageCodec.decodeModerationAction(msg);
                } catch (Exception e) {
                    log.warn("Malformed MODERATION_ACTION session={}: {}", session.sessionId, e.getMessage());
                    break;
                }
                String actionType = modPayload.actionType();
                if ("KICK".equals(actionType)) {
                    publishControlEnvelope(session.roomId, msg);
                    executeModerationKick(modRoom, modPayload);
                    log.info("MODERATION_ACTION KICK  room='{}' target='{}' by={}",
                            session.roomId, modPayload.targetClientId(), clientLogLabel(session));
                } else if ("REVOKE_SPEAK".equals(actionType)) {
                    publishControlEnvelope(session.roomId, msg);
                    executeModerationRevokeSpeak(modRoom, modPayload);
                    log.info("MODERATION_ACTION REVOKE_SPEAK  room='{}' target='{}' by={}",
                            session.roomId, modPayload.targetClientId(), clientLogLabel(session));
                } else if ("GRANT_SPEAK".equals(actionType)) {
                    publishControlEnvelope(session.roomId, msg);
                    executeModerationGrantSpeak(modRoom, modPayload);
                    log.info("MODERATION_ACTION GRANT_SPEAK  room='{}' target='{}' by={}",
                            session.roomId, modPayload.targetClientId(), clientLogLabel(session));
                } else {
                    log.debug("MODERATION_ACTION ignored unsupported actionType='{}' session={}",
                            actionType, session.sessionId);
                }
            }

            default -> log.warn("Unexpected message type={} from session={} — ignoring",
                    msg.type(), session.sessionId);
        }
    }

    /**
     * Resolves the {@link RoomContext} for the given session.  Logs a warning
     * and returns {@code null} if the client is still in the lobby or has not
     * completed {@code JOIN_ROOM} ({@code session.roomId} is blank).
     */
    private RoomContext resolveRoom(ClientSession session, SelectionKey key) {
        if (session.roomId.isBlank()) {
            log.warn("Canvas message while in lobby (send JOIN_ROOM first) session={} — ignoring",
                    session.sessionId);
            return null;
        }
        RoomContext room = roomManager.getRoom(session.roomId);
        if (room == null) {
            log.warn("Unknown roomId='{}' for session={} — ignoring", session.roomId, session.sessionId);
            return null;
        }
        return room;
    }

    // =========================================================================
    // Snapshot delivery
    // =========================================================================

    /**
     * Encodes the room's current canvas state as a {@code SNAPSHOT} frame and
     * enqueues it for delivery to the newly joined session.
     */
    void revokeUdpAdmission(ClientSession session) {
        String tok = session.udpToken;
        if (tok != null && !tok.isBlank()) {
            udpTokenToSession.remove(tok, session);
        }
        session.udpToken = "";
        session.udpEndpoint = null;
    }

    private void sendUdpAdmission(ClientSession session, SelectionKey key, String udpToken) {
        ByteBuffer frame = MessageCodec.encodeUdpAdmission(udpToken);
        if (safeEnqueue(session, key, frame, OutboundClass.CRITICAL) == EnqueueResult.OVERFLOW_DISCONNECT) {
            return;
        }
        if (!flushWriteQueue(session, key)) {
            log.error("UDP_ADMISSION flush failed for session={} — closing connection", session.sessionId);
            closeKey(key);
            return;
        }
        log.info("[UDP] Granted admission token for Client {}. Data plane ready.", clientLogLabel(session));
    }

    private void handleUdpRead(SelectionKey key) {
        DatagramChannel dc = (DatagramChannel) key.channel();
        udpBuffer.clear();
        final SocketAddress senderAddr;
        try {
            senderAddr = dc.receive(udpBuffer);
        } catch (IOException e) {
            log.warn("UDP receive I/O error: {}", e.getMessage());
            return;
        }
        if (senderAddr == null) {
            return;
        }
        if (!(senderAddr instanceof InetSocketAddress sender)) {
            return;
        }
        udpBuffer.flip();
        int len = udpBuffer.remaining();
        if (len < UDP_IDENTITY_BYTES) {
            return;
        }

        udpBuffer.get(udpTokenScratch);
        String token = resolveUdpTokenString();

        if (len == UDP_IDENTITY_BYTES) {
            ClientSession session = udpTokenToSession.get(token);
            if (session == null) {
                return;
            }
            session.udpEndpoint = sender;
            log.debug("UDP endpoint registered  session={} remote={}", session.sessionId, sender);
            return;
        }

        ClientSession speaker = udpTokenToSession.get(token);
        if (speaker == null) {
            return;
        }
        if (!RoomPermissions.canSpeak(speaker.permissions)) {
            return;
        }
        InetSocketAddress registered = speaker.udpEndpoint;
        if (registered == null || !registered.equals(sender)) {
            return;
        }
        if (speaker.roomId == null || speaker.roomId.isBlank()) {
            return;
        }
        RoomContext room = roomManager.getRoom(speaker.roomId);
        if (room == null) {
            return;
        }

        udpBuffer.position(0);
        writeClientIdPrefix36(udpBuffer, speaker.clientId);
        udpBuffer.limit(len);

        for (SelectionKey peerKey : room.activeKeysForSelectorIteration()) {
            if (!peerKey.isValid() || !(peerKey.attachment() instanceof ClientSession peer)) {
                continue;
            }
            if (peer == speaker) {
                continue;
            }
            InetSocketAddress dest = peer.udpEndpoint;
            if (dest == null) {
                continue;
            }
            try {
                udpBuffer.rewind();
                dc.send(udpBuffer, dest);
                bytesRouted.addAndGet(len);
            } catch (IOException e) {
                log.debug("UDP relay send to {} failed: {}", dest, e.getMessage());
            }
        }
    }

    /**
     * Writes exactly {@value #UDP_IDENTITY_BYTES} bytes of UTF-8 for {@code clientId}, zero-padded.
     * Uses {@link #udpClientIdEncoder} so the hot path does not allocate a {@code CharsetEncoder} or {@code byte[]}.
     */
    private String resolveUdpTokenString() {
        if (udpTokenStringCacheValid && Arrays.equals(udpTokenScratch, udpLastTokenScratch)) {
            return udpCachedToken;
        }
        System.arraycopy(udpTokenScratch, 0, udpLastTokenScratch, 0, UDP_IDENTITY_BYTES);
        udpCachedToken = new String(udpTokenScratch, StandardCharsets.UTF_8);
        udpTokenStringCacheValid = true;
        return udpCachedToken;
    }

    private void writeClientIdPrefix36(ByteBuffer dst, String clientId) {
        int start = dst.position();
        int oldLimit = dst.limit();
        CharBuffer in = udpClientIdCharScratch;
        in.clear();
        String id = clientId != null ? clientId : "";
        for (int i = 0, n = id.length(); i < n && in.hasRemaining(); i++) {
            in.put(id.charAt(i));
        }
        in.flip();
        try {
            dst.limit(start + UDP_IDENTITY_BYTES);
            udpClientIdEncoder.reset();
            udpClientIdEncoder.encode(in, dst, true);
            CoderResult flush = udpClientIdEncoder.flush(dst);
            if (flush.isOverflow()) {
                dst.position(start + UDP_IDENTITY_BYTES);
            }
        } finally {
            dst.limit(oldLimit);
        }
        while (dst.position() < start + UDP_IDENTITY_BYTES) {
            dst.put((byte) 0);
        }
    }

    /**
     * Resolves the board id for {@code JOIN_ROOM} before {@link #sendSnapshot} materializes a board.
     * Non-managers cannot create boards when {@link RoomContext#isBoardCreationLocked} is set.
     */
    private String resolveJoinBoardId(RoomContext room, String requestedBoardId, int permissions) {
        String requested = requestedBoardId != null ? requestedBoardId.strip() : "";
        if (requested.isBlank()) {
            requested = MessageCodec.DEFAULT_INITIAL_BOARD_ID;
        }
        if (room.getActiveBoardIds().contains(requested)) {
            return requested;
        }
        if (room.isBoardCreationLocked && !RoomPermissions.canManageRoom(permissions)) {
            var active = room.getActiveBoardIds();
            if (active.isEmpty()) {
                log.warn("JOIN_ROOM: no active boards for locked member fallback  room='{}'", room.roomId);
                return MessageCodec.DEFAULT_INITIAL_BOARD_ID;
            }
            return active.stream().sorted().findFirst().orElse(MessageCodec.DEFAULT_INITIAL_BOARD_ID);
        }
        return requested;
    }

    private void sendSnapshot(ClientSession session, SelectionKey key, RoomContext room) {
        List<Shape> shapes = room.getBoard(session.currentBoardId).snapshot();
        String payload = ShapeCodec.encodeSnapshot(shapes);
        Message snapshotMsg = new Message(MessageType.SNAPSHOT, payload);
        ByteBuffer frame = MessageCodec.encode(snapshotMsg);

        log.debug("Sending SNAPSHOT  room='{}' shapes={} bytes={} to={}",
                room.roomId, shapes.size(), frame.remaining(), session.sessionId);

        if (safeEnqueue(session, key, frame, OutboundClass.CRITICAL) == EnqueueResult.OVERFLOW_DISCONNECT) {
            return;
        }
        if (!flushWriteQueue(session, key)) {
            log.error("SNAPSHOT flush failed for session={} — closing connection", session.sessionId);
            closeKey(key);
        }
    }

    /**
     * Pushes a pre-encoded {@code SNAPSHOT} frame to every local client in {@code roomId}
     * viewing {@code boardId}. Used after backplane hydration.
     *
     * @return number of recipients that accepted the frame
     */
    private int broadcastSnapshotToBoard(String roomId, String boardId, ByteBuffer frame) {
        return fanoutFrameToBoard(roomId, boardId, frame);
    }

    // =========================================================================
    // Lobby broadcast
    // =========================================================================

    private void drainPendingLobbyBroadcasts() {
        ByteBuffer frame;
        while ((frame = pendingLobbyFrames.poll()) != null) {
            deliverLobbyStateFrame(frame);
        }
    }

    /**
     * Drains {@link #remoteMailbox} on the selector thread and fans out remote mutations
     * to local TCP clients. Must not be called from the Redis listener thread.
     */
    void processRemoteMailbox() {
        BackplaneEnvelope envelope;
        while ((envelope = remoteMailbox.poll()) != null) {
            if (envelope.originNodeId().equals(localNodeId)) {
                continue;
            }
            ServerMetrics.REDIS_MESSAGES_RECEIVED.incrementAndGet();
            Message peek;
            try {
                ByteBuffer decodeBuf = envelope.serializedPayload().duplicate();
                peek = MessageCodec.decode(decodeBuf);
            } catch (Exception e) {
                log.warn("Remote backplane peek decode failed  room='{}' eventId='{}': {}",
                        envelope.roomId(), envelope.eventId(), e.getMessage());
                continue;
            }
            if (peek.type() == MessageType.CURSOR_SYNC) {
                fanoutRemoteCursorSync(envelope);
            } else if (peek.type() == MessageType.MODERATION_ACTION) {
                applyRemoteModerationAction(envelope);
            } else if (peek.type() == MessageType.BOARD_SWITCH
                    || peek.type() == MessageType.TOGGLE_BOARD_LOCK) {
                applyRemoteControlEnvelope(envelope);
            } else {
                fanoutRemoteEnvelope(envelope);
            }
        }
    }

    /**
     * Publishes a room-level {@code STATE_REQUEST} when this node first materializes a room.
     * Non-blocking; no-op when the backplane publisher is disabled.
     */
    void publishStateRequest(String roomId) {
        if (backplanePublisher == null || roomId == null || roomId.isBlank()) {
            return;
        }
        Message msg = new Message(MessageType.STATE_REQUEST, "{}");
        publishBackplaneEnvelope(roomId, MessageCodec.DEFAULT_INITIAL_BOARD_ID, msg);
        log.debug("Backplane STATE_REQUEST published  room='{}'", roomId);
    }

    private void publishBackplaneEnvelope(String roomId, String boardId, Message msg) {
        if (backplanePublisher == null) {
            return;
        }
        String eventId = UUID.randomUUID().toString();
        backplaneEventDedup.tryRecord(eventId);
        backplanePublisher.publish(new BackplaneEnvelope(
                eventId,
                backplanePublisher.originNodeId(),
                roomId,
                boardId,
                MessageCodec.encode(msg)));
    }

    /**
     * Publishes ephemeral cursor position to the presence channel without dedup or WAL.
     */
    private void publishPresenceEnvelope(String roomId, Message msg) {
        if (backplanePublisher == null) {
            return;
        }
        backplanePublisher.publishPresence(new BackplaneEnvelope(
                UUID.randomUUID().toString(),
                backplanePublisher.originNodeId(),
                roomId,
                MessageCodec.DEFAULT_INITIAL_BOARD_ID,
                MessageCodec.encode(msg)));
    }

    /**
     * Publishes a moderation command to the room control channel (no WAL).
     */
    private void publishControlEnvelope(String roomId, Message msg) {
        if (backplanePublisher == null) {
            return;
        }
        String eventId = UUID.randomUUID().toString();
        backplaneEventDedup.tryRecord(eventId);
        backplanePublisher.publishControl(new BackplaneEnvelope(
                eventId,
                backplanePublisher.originNodeId(),
                roomId,
                MessageCodec.DEFAULT_INITIAL_BOARD_ID,
                MessageCodec.encode(msg)));
    }

    /**
     * Applies a remote {@code MODERATION_ACTION} from the control channel on the selector thread.
     * Must not be called from the Redis subscriber thread.
     */
    private void applyRemoteModerationAction(BackplaneEnvelope envelope) {
        if (envelope.originNodeId().equals(localNodeId)) {
            return;
        }
        if (!backplaneEventDedup.tryRecord(envelope.eventId())) {
            log.trace("Remote moderation duplicate dropped  eventId='{}'", envelope.eventId());
            return;
        }

        Message msg;
        try {
            ByteBuffer decodeBuf = envelope.serializedPayload().duplicate();
            msg = MessageCodec.decode(decodeBuf);
        } catch (Exception e) {
            log.warn("Remote moderation decode failed  room='{}' eventId='{}': {}",
                    envelope.roomId(), envelope.eventId(), e.getMessage());
            return;
        }

        if (msg.type() != MessageType.MODERATION_ACTION) {
            return;
        }

        MessageCodec.ModerationActionPayload payload;
        try {
            payload = MessageCodec.decodeModerationAction(msg);
        } catch (Exception e) {
            log.warn("Remote MODERATION_ACTION malformed  room='{}' eventId='{}': {}",
                    envelope.roomId(), envelope.eventId(), e.getMessage());
            return;
        }

        String actionType = payload.actionType();
        if (!"KICK".equals(actionType) && !"REVOKE_SPEAK".equals(actionType)
                && !"GRANT_SPEAK".equals(actionType)) {
            log.debug("Remote MODERATION_ACTION ignored actionType='{}'  room='{}'",
                    actionType, envelope.roomId());
            return;
        }

        RoomContext room = roomManager.getRoom(envelope.roomId());
        if (room == null) {
            log.trace("Remote moderation no local room  room='{}' target='{}' action='{}'",
                    envelope.roomId(), payload.targetClientId(), actionType);
            return;
        }

        if ("KICK".equals(actionType)) {
            executeModerationKick(room, payload);
        } else if ("REVOKE_SPEAK".equals(actionType)) {
            executeModerationRevokeSpeak(room, payload);
        } else {
            executeModerationGrantSpeak(room, payload);
        }
    }

    /**
     * Terminates the local TCP session for {@code targetClientId} when hosted in {@code room},
     * then fans out a peer-depart {@link MessageType#LEAVE_ROOM} to every local room client so
     * observers (including admins on other cluster nodes' local TCP paths) update immediately.
     * Selector-thread only.
     */
    private void executeModerationKick(RoomContext room, MessageCodec.ModerationActionPayload payload) {
        SelectionKey targetKey = room.lookupClientKey(payload.targetClientId());
        if (targetKey != null && targetKey.isValid()) {
            severSession(targetKey, payload.reason());
        } else {
            log.trace("KICK target not on this node  room='{}' target='{}'",
                    room.roomId, payload.targetClientId());
        }
        broadcastToRoom(room.roomId,
                MessageCodec.encodeRoomMemberLeft(payload.targetClientId()),
                null,
                OutboundClass.CRITICAL);
    }

    /**
     * Clears {@link RoomPermissions#PERM_SPEAK} for {@code targetClientId} when local, then
     * broadcasts {@link MessageType#ROLE_UPDATE} to all local room clients. Selector-thread only.
     */
    private void executeModerationRevokeSpeak(RoomContext room, MessageCodec.ModerationActionPayload payload) {
        int newPerms = RoomPermissions.PERM_DRAW;
        SelectionKey targetKey = room.lookupClientKey(payload.targetClientId());
        if (targetKey != null && targetKey.isValid()) {
            Object attachment = targetKey.attachment();
            if (attachment instanceof ClientSession targetSession) {
                if (targetSession.clientId.equals(room.hostClientId)
                        && RoomPermissions.canDeleteRoom(targetSession.permissions)) {
                    log.trace("REVOKE_SPEAK ignored for room owner  room='{}' target='{}'",
                            room.roomId, payload.targetClientId());
                    return;
                }
                if (!RoomPermissions.canSpeak(targetSession.permissions)) {
                    log.trace("REVOKE_SPEAK no-op — target already cannot speak  room='{}' target='{}'",
                            room.roomId, payload.targetClientId());
                    return;
                }
                targetSession.permissions &= ~RoomPermissions.PERM_SPEAK;
                newPerms = targetSession.permissions;
            }
        } else {
            log.trace("REVOKE_SPEAK target not on this node  room='{}' target='{}'",
                    room.roomId, payload.targetClientId());
        }
        ByteBuffer frame = MessageCodec.encodeRoleUpdate(
                payload.targetClientId(), newPerms, room.hostClientId);
        broadcastToRoom(room.roomId, frame, null, OutboundClass.CRITICAL);
    }

    /**
     * Sets {@link RoomPermissions#PERM_SPEAK} for {@code targetClientId} when local, then
     * broadcasts {@link MessageType#ROLE_UPDATE} to all local room clients. Selector-thread only.
     */
    private void executeModerationGrantSpeak(RoomContext room, MessageCodec.ModerationActionPayload payload) {
        int newPerms = RoomPermissions.PERM_DRAW | RoomPermissions.PERM_SPEAK;
        SelectionKey targetKey = room.lookupClientKey(payload.targetClientId());
        if (targetKey != null && targetKey.isValid()) {
            Object attachment = targetKey.attachment();
            if (attachment instanceof ClientSession targetSession) {
                if (targetSession.clientId.equals(room.hostClientId)
                        && RoomPermissions.canDeleteRoom(targetSession.permissions)) {
                    log.trace("GRANT_SPEAK ignored for room owner  room='{}' target='{}'",
                            room.roomId, payload.targetClientId());
                    return;
                }
                if (RoomPermissions.canSpeak(targetSession.permissions)) {
                    log.trace("GRANT_SPEAK no-op — target already can speak  room='{}' target='{}'",
                            room.roomId, payload.targetClientId());
                    return;
                }
                targetSession.permissions |= RoomPermissions.PERM_SPEAK;
                newPerms = targetSession.permissions;
            }
        } else {
            log.trace("GRANT_SPEAK target not on this node  room='{}' target='{}'",
                    room.roomId, payload.targetClientId());
        }
        ByteBuffer frame = MessageCodec.encodeRoleUpdate(
                payload.targetClientId(), newPerms, room.hostClientId);
        broadcastToRoom(room.roomId, frame, null, OutboundClass.CRITICAL);
    }

    /**
     * Notifies the client with {@link MessageType#SESSION_REVOKED}, then closes the connection.
     * Selector-thread only — must never be invoked from the Redis subscriber thread.
     */
    private void severSession(SelectionKey targetKey, String reason) {
        Object attachment = targetKey.attachment();
        if (!(attachment instanceof ClientSession session)) {
            closeKey(targetKey);
            return;
        }

        ByteBuffer frame = MessageCodec.encodeSessionRevoked(reason);
        if (safeEnqueue(session, targetKey, frame, OutboundClass.CRITICAL) == EnqueueResult.OVERFLOW_DISCONNECT) {
            return;
        }
        flushWriteQueue(session, targetKey);
        closeKey(targetKey);
        log.info("Session severed  session={} clientId='{}' room='{}'",
                session.sessionId, session.clientId, session.roomId);
    }

    /**
     * Fans out a remote {@code CURSOR_SYNC} to all local room clients. Skips dedup and WAL.
     */
    private void fanoutRemoteCursorSync(BackplaneEnvelope envelope) {
        String roomId = envelope.roomId();
        ByteBuffer frame = envelope.serializedPayload().duplicate();
        int recipients = fanoutFrameToRoom(roomId, frame);
        log.trace("Remote CURSOR_SYNC fan-out  roomId='{}' recipients={}", roomId, recipients);
    }

    private void respondToStateRequest(RoomContext room, String roomId) {
        if (backplanePublisher == null) {
            return;
        }
        for (String activeBoardId : room.getActiveBoardIds()) {
            List<Shape> shapes = room.getBoard(activeBoardId).snapshot();
            if (shapes.isEmpty()) {
                continue;
            }
            Message snapshotMsg = new Message(
                    MessageType.STATE_SNAPSHOT,
                    ShapeCodec.encodeSnapshot(shapes));
            publishBackplaneEnvelope(roomId, activeBoardId, snapshotMsg);
            log.debug("Backplane STATE_SNAPSHOT published  room='{}' board='{}' shapes={}",
                    roomId, activeBoardId, shapes.size());
        }
    }

    private void applyRemoteStateSnapshot(RoomContext room, String roomId, String boardId, Message msg) {
        List<Shape> incoming;
        try {
            incoming = ShapeCodec.decodeSnapshot(msg.payload());
        } catch (Exception e) {
            log.warn("Remote STATE_SNAPSHOT malformed  room='{}' board='{}': {}",
                    roomId, boardId, e.getMessage());
            return;
        }

        CanvasStateManager board = room.getBoard(boardId);
        for (Shape shape : incoming) {
            board.applyMutation(shape);
        }

        if (walManager != null) {
            try {
                walManager.compactWal(roomId, boardId, board.snapshot());
            } catch (IOException e) {
                log.error("WAL compact after STATE_SNAPSHOT failed  room='{}' board='{}': {}",
                        roomId, boardId, e.getMessage(), e);
            }
        }

        Message clientSnapshot = new Message(
                MessageType.SNAPSHOT,
                ShapeCodec.encodeSnapshot(board.snapshot()));
        ByteBuffer frame = MessageCodec.encode(clientSnapshot);
        int recipients = broadcastSnapshotToBoard(roomId, boardId, frame);
        log.debug("Remote STATE_SNAPSHOT hydrated  room='{}' board='{}' shapes={} recipients={}",
                roomId, boardId, board.snapshot().size(), recipients);
    }

    /**
     * Applies a remote backplane envelope to local authoritative state, persists to WAL when
     * applicable, then fans out to local TCP clients. Mutation paths are receive-only;
     * {@link MessageType#STATE_REQUEST} may trigger {@code STATE_SNAPSHOT} publishes from this node.
     * Must run on the selector thread.
     */
    void fanoutRemoteEnvelope(BackplaneEnvelope envelope) {
        if (!backplaneEventDedup.tryRecord(envelope.eventId())) {
            log.trace("Remote backplane duplicate dropped  eventId='{}'", envelope.eventId());
            return;
        }

        String roomId = envelope.roomId();
        String boardId = envelope.boardId();
        RoomContext room = roomManager.getOrCreateRoom(roomId);

        Message msg;
        try {
            ByteBuffer decodeBuf = envelope.serializedPayload().duplicate();
            msg = MessageCodec.decode(decodeBuf);
        } catch (PartialMessageException e) {
            log.warn("Remote backplane incomplete frame  room='{}' board='{}' eventId='{}': {}",
                    roomId, boardId, envelope.eventId(), e.getMessage());
            return;
        } catch (Exception e) {
            log.warn("Remote backplane decode failed  room='{}' board='{}' eventId='{}': {}",
                    roomId, boardId, envelope.eventId(), e.getMessage());
            return;
        }

        ByteBuffer fanoutFrame = envelope.serializedPayload().duplicate();
        boolean fanout = switch (msg.type()) {
            case MUTATION -> applyRemoteMutation(room, roomId, boardId, msg, fanoutFrame);
            case SHAPE_DELETE -> applyRemoteShapeDelete(room, roomId, boardId, msg, fanoutFrame);
            case CLEAR_USER_SHAPES -> applyRemoteClearUserShapes(room, roomId, boardId, msg, fanoutFrame);
            case STATE_REQUEST -> {
                respondToStateRequest(room, roomId);
                yield false;
            }
            case STATE_SNAPSHOT -> {
                applyRemoteStateSnapshot(room, roomId, boardId, msg);
                yield false;
            }
            default -> {
                log.debug("Remote backplane ignored non-durable type={}  room='{}' eventId='{}'",
                        msg.type(), roomId, envelope.eventId());
                yield false;
            }
        };

        if (fanout) {
            int recipients = fanoutFrameToBoard(roomId, boardId, fanoutFrame);
            log.debug("Remote backplane applied  roomId='{}' boardId='{}' eventId='{}' type={} recipients={}",
                    roomId, boardId, envelope.eventId(), msg.type(), recipients);
        }
    }

    private boolean applyRemoteMutation(
            RoomContext room, String roomId, String boardId, Message msg, ByteBuffer fanoutFrame) {
        Shape shape;
        try {
            shape = ShapeCodec.decodeMutation(msg.payload());
        } catch (Exception e) {
            log.warn("Remote MUTATION malformed  room='{}' board='{}': {}", roomId, boardId, e.getMessage());
            return false;
        }

        CanvasStateManager board = room.getBoard(boardId);
        if (!board.applyMutation(shape)) {
            log.debug("Remote MUTATION rejected (stale)  id={} room='{}' board='{}'",
                    shape.objectId(), roomId, boardId);
            return false;
        }

        roomManager.appendToWal(roomId, boardId, msg);
        fanoutFrame.rewind();
        return true;
    }

    private boolean applyRemoteShapeDelete(
            RoomContext room, String roomId, String boardId, Message msg, ByteBuffer fanoutFrame) {
        UUID shapeId;
        try {
            JsonObject p = JsonParser.parseString(msg.payload()).getAsJsonObject();
            shapeId = UUID.fromString(p.get("shapeId").getAsString());
        } catch (Exception e) {
            log.warn("Remote SHAPE_DELETE malformed  room='{}' board='{}': {}", roomId, boardId, e.getMessage());
            return false;
        }

        CanvasStateManager board = room.getBoard(boardId);
        if (!board.deleteShape(shapeId)) {
            log.debug("Remote SHAPE_DELETE no-op  id={} room='{}' board='{}'", shapeId, roomId, boardId);
            return false;
        }

        roomManager.appendToWal(roomId, boardId, msg);
        fanoutFrame.rewind();
        return true;
    }

    private boolean applyRemoteClearUserShapes(
            RoomContext room, String roomId, String boardId, Message msg, ByteBuffer fanoutFrame) {
        String targetClientId;
        try {
            targetClientId = MessageCodec.decodeClearUserShapes(msg);
        } catch (Exception e) {
            log.warn("Remote CLEAR_USER_SHAPES malformed  room='{}' board='{}': {}", roomId, boardId, e.getMessage());
            return false;
        }

        CanvasStateManager board = room.getBoard(boardId);
        board.clearUserShapes(targetClientId);
        roomManager.appendToWal(roomId, boardId, msg);
        fanoutFrame.rewind();
        return true;
    }

    /**
     * Delivers {@code frame} to every active client in {@code roomId} on {@code boardId}.
     *
     * @return number of recipients that accepted the frame
     */
    private int fanoutFrameToBoard(String roomId, String boardId, ByteBuffer frame) {
        var activeKeys = roomManager.getActiveClientKeys(roomId);
        if (activeKeys.isEmpty()) {
            return 0;
        }

        int frameBytes = frame.remaining();
        List<SelectionKey> toClose = new ArrayList<>();
        int recipientCount = 0;

        for (SelectionKey key : activeKeys) {
            if (!key.isValid()) {
                continue;
            }

            ClientSession session = (ClientSession) key.attachment();
            if (!boardId.equals(session.currentBoardId)) {
                continue;
            }

            frame.rewind();
            EnqueueResult result = safeEnqueue(session, key, frame, OutboundClass.CRITICAL);
            if (result == EnqueueResult.OVERFLOW_DISCONNECT) {
                continue;
            }
            if (!flushWriteQueue(session, key)) {
                toClose.add(key);
            } else {
                bytesRouted.addAndGet(frameBytes);
                recipientCount++;
            }
        }

        toClose.forEach(this::closeKey);
        return recipientCount;
    }

    /**
     * Delivers {@code frame} to every active client in {@code roomId} (all boards).
     *
     * @return number of recipients that accepted the frame
     */
    private int fanoutFrameToRoom(String roomId, ByteBuffer frame) {
        var activeKeys = roomManager.getActiveClientKeys(roomId);
        if (activeKeys.isEmpty()) {
            return 0;
        }

        int frameBytes = frame.remaining();
        List<SelectionKey> toClose = new ArrayList<>();
        int recipientCount = 0;

        for (SelectionKey key : activeKeys) {
            if (!key.isValid()) {
                continue;
            }

            ClientSession session = (ClientSession) key.attachment();
            frame.rewind();
            EnqueueResult result = safeEnqueue(session, key, frame, OutboundClass.EPHEMERAL);
            if (result == EnqueueResult.OVERFLOW_DISCONNECT) {
                continue;
            }
            if (!flushWriteQueue(session, key)) {
                toClose.add(key);
            } else {
                bytesRouted.addAndGet(frameBytes);
                recipientCount++;
            }
        }

        toClose.forEach(this::closeKey);
        return recipientCount;
    }

    /** Package-private for tests — whether {@code eventId} is in the dedup cache. */
    boolean isEventIdRecorded(String eventId) {
        return backplaneEventDedup.contains(eventId);
    }

    /**
     * Enqueues one {@code LOBBY_STATE} frame to every client currently in the lobby.
     * Must run on the selector thread.
     */
    private void deliverLobbyStateFrame(ByteBuffer frame) {
        List<SelectionKey> keys = roomManager.snapshotLobbyKeys();
        List<SelectionKey> toClose = new ArrayList<>();

        for (SelectionKey key : keys) {
            if (!key.isValid()) {
                continue;
            }
            if (!(key.attachment() instanceof ClientSession session)) {
                continue;
            }
            EnqueueResult result = safeEnqueue(session, key, frame, OutboundClass.EPHEMERAL);
            if (result == EnqueueResult.OVERFLOW_DISCONNECT) {
                continue;
            }
            if (!flushWriteQueue(session, key)) {
                toClose.add(key);
            }
        }

        log.debug("LOBBY_STATE fan-out  lobbyClients={}", keys.size());
        toClose.forEach(this::closeKey);
    }

    /**
     * Builds and fans out a fresh {@code LOBBY_STATE} from current in-memory rooms + persisted WAL stems.
     * Must run after lifecycle-changing operations (e.g. DELETE_ROOM) and on the selector thread.
     */
    private void broadcastLobbyState() {
        Set<String> persistedStems = Set.of();
        if (walManager != null) {
            try {
                persistedStems = walManager.getPersistedRoomIds();
            } catch (IOException e) {
                log.warn("Failed to list persisted WAL room ids during lobby broadcast: {}", e.getMessage());
            }
        }

        Map<String, Integer> activeCounts = new HashMap<>(roomManager.getRooms().size());
        for (var entry : roomManager.getRooms().entrySet()) {
            activeCounts.put(entry.getKey(), entry.getValue().getActiveClientCount());
        }

        List<MessageCodec.LobbyRoomEntry> entries =
                RoomManager.mergePersistedWalStemsWithActiveRooms(persistedStems, activeCounts);
        deliverLobbyStateFrame(MessageCodec.encodeLobbyState(entries));
    }

    // =========================================================================
    // Room + board scoped broadcast
    // =========================================================================

    /**
     * Delivers {@code frame} to every active client in {@code roomId}
     * <em>except</em> the sender.
     *
     * <p>Only keys registered in that specific {@link RoomContext} are
     * considered; clients in other rooms never see this frame.  Keys that
     * fail during the write are collected and closed after the iteration
     * to avoid a {@link java.util.ConcurrentModificationException}.
     *
     * @param roomId    the target room identifier
     * @param boardId   only clients whose {@link ClientSession#currentBoardId} matches
     * @param frame     the encoded binary frame to deliver (read-mode)
     * @param senderKey the originating client's key; excluded from delivery
     */
    private void broadcastToBoard(String roomId, String boardId, ByteBuffer frame, SelectionKey senderKey,
                                OutboundClass cls) {
        var activeKeys = roomManager.getActiveClientKeys(roomId);
        if (activeKeys.isEmpty()) {
            if (roomManager.getRoom(roomId) == null) {
                log.warn("broadcastToBoard called for unknown roomId='{}'", roomId);
            }
            return;
        }

        int frameBytes = frame.remaining();
        List<SelectionKey> toClose = new ArrayList<>();
        int recipientCount = 0;

        for (SelectionKey key : activeKeys) {
            if (!key.isValid() || key == senderKey) {
                continue;
            }

            ClientSession session = (ClientSession) key.attachment();
            if (!boardId.equals(session.currentBoardId)) {
                continue;
            }

            EnqueueResult result = safeEnqueue(session, key, frame, cls);
            if (result == EnqueueResult.OVERFLOW_DISCONNECT) {
                continue;
            }
            if (!flushWriteQueue(session, key)) {
                toClose.add(key);
            } else {
                bytesRouted.addAndGet(frameBytes);
                recipientCount++;
            }
        }

        log.debug("Board broadcast  roomId='{}' boardId='{}' recipients={}", roomId, boardId, recipientCount);
        toClose.forEach(this::closeKey);
    }

    /**
     * Delivers {@code frame} to every active client in {@code roomId} except the sender.
     * Used for room-wide control-plane events (e.g. {@link MessageType#VOICE_STATE}) that are
     * not scoped to a single board.
     */
    private void broadcastToRoom(String roomId, ByteBuffer frame, SelectionKey senderKey) {
        broadcastToRoom(roomId, frame, senderKey, OutboundClass.EPHEMERAL);
    }

    private void broadcastToRoom(String roomId, ByteBuffer frame, SelectionKey senderKey,
                               OutboundClass outboundClass) {
        var activeKeys = roomManager.getActiveClientKeys(roomId);
        if (activeKeys.isEmpty()) {
            if (roomManager.getRoom(roomId) == null) {
                log.warn("broadcastToRoom called for unknown roomId='{}'", roomId);
            }
            return;
        }

        int frameBytes = frame.remaining();
        List<SelectionKey> toClose = new ArrayList<>();
        int recipientCount = 0;

        for (SelectionKey key : activeKeys) {
            if (!key.isValid() || key == senderKey) {
                continue;
            }

            ClientSession peer = (ClientSession) key.attachment();
            EnqueueResult result = safeEnqueue(peer, key, frame, outboundClass);
            if (result == EnqueueResult.OVERFLOW_DISCONNECT) {
                continue;
            }
            if (!flushWriteQueue(peer, key)) {
                toClose.add(key);
            } else {
                bytesRouted.addAndGet(frameBytes);
                recipientCount++;
            }
        }

        log.debug("Room broadcast  roomId='{}' recipients={}", roomId, recipientCount);
        toClose.forEach(this::closeKey);
    }

    /**
     * Broadcasts {@link MessageType#BOARD_LIST_UPDATE} to every TCP client currently in
     * {@code room} (all boards). Uses a deterministic lexicographic ordering of ids on the wire.
     */
    private void fanoutBoardSwitch(RoomContext room, String clientId, String boardId, SelectionKey excludeKey) {
        if (clientId == null || clientId.isBlank() || boardId == null || boardId.isBlank()) {
            return;
        }
        ByteBuffer frame = MessageCodec.encodeBoardSwitch(clientId, boardId);
        broadcastToRoom(room.roomId, frame, excludeKey, OutboundClass.CRITICAL);
        publishControlEnvelope(room.roomId,
                new Message(MessageType.BOARD_SWITCH,
                        MessageCodec.gson().toJson(new MessageCodec.BoardSwitchPayload(clientId, boardId))));
    }

    private void hydrateBoardPresenceForJoiner(RoomContext room, SelectionKey joinerKey, ClientSession joiner) {
        for (SelectionKey key : room.activeKeysForSelectorIteration()) {
            if (!key.isValid() || key == joinerKey) {
                continue;
            }
            if (!(key.attachment() instanceof ClientSession peer)) {
                continue;
            }
            String peerId = peer.clientId;
            if (peerId == null || peerId.isBlank()) {
                continue;
            }
            ByteBuffer joinFrame = MessageCodec.encodeRoomMemberJoined(peerId, peer.authorName);
            if (safeEnqueue(joiner, joinerKey, joinFrame, OutboundClass.CRITICAL) == EnqueueResult.OVERFLOW_DISCONNECT) {
                return;
            }
            ByteBuffer roleFrame = MessageCodec.encodeRoleUpdate(
                    peerId, peer.permissions, room.hostClientId);
            if (safeEnqueue(joiner, joinerKey, roleFrame, OutboundClass.CRITICAL) == EnqueueResult.OVERFLOW_DISCONNECT) {
                return;
            }
            String peerBoard = peer.currentBoardId;
            if (peerBoard != null && !peerBoard.isBlank()) {
                ByteBuffer boardFrame = MessageCodec.encodeBoardSwitch(peerId, peerBoard);
                if (safeEnqueue(joiner, joinerKey, boardFrame, OutboundClass.CRITICAL) == EnqueueResult.OVERFLOW_DISCONNECT) {
                    return;
                }
                ByteBuffer voiceFrame = MessageCodec.encodeVoiceState(peerId, peer.micMuted);
                if (safeEnqueue(joiner, joinerKey, voiceFrame, OutboundClass.CRITICAL) == EnqueueResult.OVERFLOW_DISCONNECT) {
                    return;
                }
            }
        }
        flushWriteQueue(joiner, joinerKey);
    }

    private void fanoutBoardLockState(RoomContext room, boolean locked) {
        ByteBuffer frame = MessageCodec.encodeBoardLockState(locked);
        broadcastToRoom(room.roomId, frame, null, OutboundClass.CRITICAL);
        publishControlEnvelope(room.roomId,
                new Message(MessageType.TOGGLE_BOARD_LOCK,
                        MessageCodec.gson().toJson(new MessageCodec.BoardLockPayload(locked))));
    }

    /**
     * Applies {@code BOARD_SWITCH} or {@code TOGGLE_BOARD_LOCK} from the control channel on this node.
     */
    private void applyRemoteControlEnvelope(BackplaneEnvelope envelope) {
        if (envelope.originNodeId().equals(localNodeId)) {
            return;
        }
        if (!backplaneEventDedup.tryRecord(envelope.eventId())) {
            log.trace("Remote control duplicate dropped  eventId='{}'", envelope.eventId());
            return;
        }

        Message msg;
        try {
            ByteBuffer decodeBuf = envelope.serializedPayload().duplicate();
            msg = MessageCodec.decode(decodeBuf);
        } catch (Exception e) {
            log.warn("Remote control decode failed  room='{}' eventId='{}': {}",
                    envelope.roomId(), envelope.eventId(), e.getMessage());
            return;
        }

        RoomContext room = roomManager.getRoom(envelope.roomId());
        if (room == null) {
            return;
        }

        if (msg.type() == MessageType.TOGGLE_BOARD_LOCK) {
            try {
                MessageCodec.BoardLockPayload lockPayload = MessageCodec.decodeBoardLockState(msg);
                room.isBoardCreationLocked = lockPayload.locked();
                ByteBuffer frame = MessageCodec.encodeBoardLockState(lockPayload.locked());
                broadcastToRoom(room.roomId, frame, null, OutboundClass.CRITICAL);
            } catch (Exception e) {
                log.warn("Remote TOGGLE_BOARD_LOCK malformed  room='{}': {}", room.roomId, e.getMessage());
            }
            return;
        }

        if (msg.type() == MessageType.BOARD_SWITCH) {
            try {
                MessageCodec.BoardSwitchPayload payload = MessageCodec.decodeBoardSwitch(msg);
                SelectionKey targetKey = room.lookupClientKey(payload.clientId());
                if (targetKey != null && targetKey.attachment() instanceof ClientSession targetSession) {
                    targetSession.currentBoardId = payload.newBoardId();
                }
                ByteBuffer frame = MessageCodec.encodeBoardSwitch(payload.clientId(), payload.newBoardId());
                broadcastToRoom(room.roomId, frame, null, OutboundClass.CRITICAL);
            } catch (Exception e) {
                log.warn("Remote BOARD_SWITCH malformed  room='{}': {}", room.roomId, e.getMessage());
            }
        }
    }

    private void broadcastBoardList(RoomContext room) {
        List<String> ids = new ArrayList<>(room.getActiveBoardIds());
        Collections.sort(ids);
        ByteBuffer frame = MessageCodec.encodeBoardListUpdate(ids);

        List<SelectionKey> toClose = new ArrayList<>();
        int recipientCount = 0;

        for (SelectionKey key : room.activeKeysForSelectorIteration()) {
            if (!key.isValid()) {
                continue;
            }
            if (!(key.attachment() instanceof ClientSession peer)) {
                continue;
            }
            EnqueueResult result = safeEnqueue(peer, key, frame, OutboundClass.CRITICAL);
            if (result == EnqueueResult.OVERFLOW_DISCONNECT) {
                continue;
            }
            if (!flushWriteQueue(peer, key)) {
                toClose.add(key);
            } else {
                recipientCount++;
            }
        }

        log.debug("BOARD_LIST_UPDATE fan-out  roomId='{}' boardCount={} recipients={}",
                room.roomId, ids.size(), recipientCount);
        toClose.forEach(this::closeKey);
    }

    // =========================================================================
    // Write / drain
    // =========================================================================

    private EnqueueResult safeEnqueue(ClientSession session, SelectionKey key,
                                      Message msg, OutboundClass cls) {
        return safeEnqueue(session, key, MessageCodec.encode(msg), cls);
    }

    private EnqueueResult safeEnqueue(ClientSession session, SelectionKey key,
                                      ByteBuffer frame, OutboundClass cls) {
        EnqueueResult result = session.enqueue(frame, cls);
        if (result == EnqueueResult.OVERFLOW_DISCONNECT) {
            closeKey(key);
        }
        return result;
    }

    private void handleWrite(SelectionKey key) {
        ClientSession session = (ClientSession) key.attachment();
        if (!flushWriteQueue(session, key)) {
            closeKey(key);
        }
    }

    /**
     * Attempts to drain the session's write queue into the socket's send buffer.
     *
     * @return {@code true} on success; {@code false} if an {@link IOException}
     *         occurred (caller should close the key)
     */
    boolean flushWriteQueue(ClientSession session, SelectionKey key) {
        SocketChannel channel = (SocketChannel) key.channel();

        try {
            while (!session.writeQueue.isEmpty()) {
                ByteBuffer buf = session.writeQueue.peek();
                channel.write(buf);

                if (buf.hasRemaining()) {
                    key.interestOpsOr(SelectionKey.OP_WRITE);
                    log.debug("Write queue stalled (send-buffer full) session={}", session.sessionId);
                    return true;
                }

                session.writeQueue.poll();
                ServerMetrics.FRAMES_SENT_TOTAL.incrementAndGet();
            }

            key.interestOpsAnd(~SelectionKey.OP_WRITE);
            return true;

        } catch (IOException e) {
            log.warn("Write error session={}: {}", session.sessionId, e.getMessage());
            return false;
        }
    }

    // =========================================================================
    // Host migration (local election on OWNER depart)
    // =========================================================================

    /**
     * When the room host disconnects or leaves, promotes the oldest remaining session
     * ({@link ClientSession#connectedAtMillis}, then lexicographic {@code clientId}).
     */
    private void maybePromoteHostAfterDepart(String roomId, String departingClientId) {
        if (roomId == null || roomId.isBlank()) {
            return;
        }
        if (departingClientId == null || departingClientId.isBlank()) {
            return;
        }
        RoomContext room = roomManager.getRoom(roomId);
        if (room == null) {
            return;
        }
        if (!departingClientId.equals(room.hostClientId)) {
            return;
        }
        if (room.getActiveClientCount() == 0) {
            return;
        }

        ClientSession newHost = selectHostCandidate(room);
        if (newHost == null) {
            return;
        }

        room.hostClientId = newHost.clientId;
        newHost.permissions = RoomPermissions.OWNER;

        ByteBuffer frame = MessageCodec.encodeRoleUpdate(newHost.clientId, RoomPermissions.OWNER);
        broadcastToRoom(roomId, frame, null, OutboundClass.CRITICAL);

        log.info("Host migration  room='{}' departed='{}' newHost='{}'",
                roomId, departingClientId, newHost.clientId);
    }

    /**
     * Selects the session with the minimum {@link ClientSession#connectedAtMillis};
     * ties break on lexicographically smaller {@code clientId}.
     */
    static ClientSession selectHostCandidate(RoomContext room) {
        ClientSession best = null;
        for (SelectionKey key : room.activeKeysForSelectorIteration()) {
            if (!key.isValid()) {
                continue;
            }
            if (!(key.attachment() instanceof ClientSession session)) {
                continue;
            }
            String clientId = session.clientId;
            if (clientId == null || clientId.isBlank()) {
                continue;
            }
            if (best == null) {
                best = session;
                continue;
            }
            int cmp = Long.compare(session.connectedAtMillis, best.connectedAtMillis);
            if (cmp < 0) {
                best = session;
            } else if (cmp == 0 && clientId.compareTo(best.clientId) < 0) {
                best = session;
            }
        }
        return best;
    }

    // =========================================================================
    // Session teardown
    // =========================================================================

    private void closeKey(SelectionKey key) {
        Object attachment = key.attachment();

        if (attachment instanceof ClientSession s) {
            String roomId = s.roomId;
            String departingClientId = s.clientId;
            revokeUdpAdmission(s);
            if (roomManager.isInLobby(key)) {
                roomManager.removeFromLobby(key);
            } else if (!roomId.isBlank()) {
                roomManager.removeClientFromRoom(roomId, key);
                if (departingClientId != null && !departingClientId.isBlank()) {
                    broadcastToRoom(roomId, MessageCodec.encodeRoomMemberLeft(departingClientId),
                            key, OutboundClass.CRITICAL);
                }
                maybePromoteHostAfterDepart(roomId, departingClientId);
            }
            activeTcpSockets.decrementAndGet();
            log.info("Closing channel  session={} room='{}'", s.sessionId,
                    s.roomId.isBlank() ? "(lobby)" : s.roomId);
        } else {
            log.info("Closing server channel");
        }

        key.cancel();

        try {
            key.channel().close();
        } catch (IOException e) {
            log.warn("Error closing channel: {}", e.getMessage());
        }
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    /** The TCP port configured at construction time. */
    public int getPort() {
        return port;
    }

    /** The {@link RoomManager} backing this server instance. */
    public RoomManager getRoomManager() {
        return roomManager;
    }

    /**
     * The live UDP token → session registry (used by {@link #handleUdpRead}).
     * Package-private for tests in {@code com.distrisync.server}.
     */
    ConcurrentHashMap<String, ClientSession> udpTokenRegistryForTests() {
        return udpTokenToSession;
    }

    /** Bytes routed through board-scoped TCP fan-out and UDP audio relay since server start. */
    public long getBytesRouted() {
        return bytesRouted.get();
    }

    /**
     * Returns a {@link CompletableFuture} that completes with the actual TCP
     * port the server bound to.
     */
    public CompletableFuture<Integer> getBoundPortFuture() {
        return boundPortFuture;
    }

    /**
     * Signals the event loop to exit gracefully.  Safe to call from any thread.
     */
    public void stop() {
        stopped = true;
        stopMetricsServer();
        Selector sel = this.selector;
        if (sel != null && sel.isOpen()) {
            sel.wakeup();
        }
    }
}
