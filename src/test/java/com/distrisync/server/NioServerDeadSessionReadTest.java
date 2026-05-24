package com.distrisync.server;

import com.distrisync.model.Line;
import com.distrisync.protocol.Message;
import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.MessageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the dead-session short-circuit in {@link NioServer#handleRead}.
 */
class NioServerDeadSessionReadTest {

    private static final String HOST = "127.0.0.1";

    @TempDir
    Path tempDir;

    private NioServer server;
    private Thread serverThread;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (server != null) {
            server.stop();
        }
        if (serverThread != null) {
            serverThread.interrupt();
            serverThread.join(10_000);
        }
        server = null;
        serverThread = null;
    }

    @Test
    void readLoopStopsAfterMalformedMutationClosesSession() throws Exception {
        final String roomId = "dead-read-malformed";
        final String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;
        RoomManager roomManager = startRoom(roomId, boardId);

        try (SocketChannel client = connectAndJoin("drawer", "drawer-client", roomId, boardId)) {
            drainUntilQuiet(client, ByteBuffer.allocate(256 * 1024), 200, 3_000);

            Line line = Line.create("#FF0000", 1, 1, 40, 40, 2.0, "drawer", "drawer-client");
            ByteBuffer malformed = MessageCodec.encode(
                    new Message(MessageType.MUTATION, "{not-a-shape-envelope}"));
            ByteBuffer valid = MessageCodec.encode(new Message(
                    MessageType.MUTATION, ShapeCodec.encodeMutation(line)));
            ByteBuffer batch = ByteBuffer.allocate(malformed.remaining() + valid.remaining());
            batch.put(malformed.duplicate());
            batch.put(valid.duplicate());
            batch.flip();

            writeFully(client, batch);
            drainUntilQuiet(client, ByteBuffer.allocate(256 * 1024), 300, 5_000);

            assertThat(board(roomManager, roomId, boardId).snapshot())
                    .as("frames after session teardown must not be applied")
                    .isEmpty();
        }
    }

    @Test
    void readLoopStopsWhenSessionMarkedSevered() throws Exception {
        final String roomId = "dead-read-severed-flag";
        final String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;
        RoomManager roomManager = startRoom(roomId, boardId);

        try (SocketChannel client = connectAndJoin("drawer", "drawer-client", roomId, boardId)) {
            drainUntilQuiet(client, ByteBuffer.allocate(256 * 1024), 200, 3_000);

            ClientSession session = findSession(roomManager, roomId, "drawer-client");
            assertThat(session).isNotNull();
            session.severed = true;

            Line line = Line.create("#00FF00", 2, 2, 30, 30, 2.0, "drawer", "drawer-client");
            writeFully(client, MessageCodec.encode(new Message(
                    MessageType.MUTATION, ShapeCodec.encodeMutation(line))));
            drainUntilQuiet(client, ByteBuffer.allocate(256 * 1024), 300, 5_000);

            assertThat(board(roomManager, roomId, boardId).snapshot())
                    .as("inbound frames must be ignored when session.severed is set")
                    .isEmpty();
        }
    }

    private RoomManager startRoom(String roomId, String boardId) throws Exception {
        WalManager walManager = new WalManager(tempDir);
        RoomManager roomManager = new RoomManager(walManager);
        walManager.append(roomId, boardId, new Message(MessageType.SHAPE_COMMIT, "{\"seed\":1}"));
        roomManager.getOrCreateRoom(roomId);
        startServer(roomManager, walManager);
        return roomManager;
    }

    private SocketChannel connectAndJoin(String author, String clientId, String roomId, String boardId)
            throws Exception {
        SocketChannel channel = SocketChannel.open();
        channel.configureBlocking(true);
        channel.connect(new InetSocketAddress(HOST, serverPort()));
        writeFully(channel, MessageCodec.encodeHandshake(clientId));
        drainUntilQuiet(channel, ByteBuffer.allocate(256 * 1024), 250, 5_000);
        writeFully(channel, MessageCodec.encodeJoinRoom(roomId, author, boardId));
        drainUntilQuiet(channel, ByteBuffer.allocate(256 * 1024), 250, 5_000);
        return channel;
    }

    private static CanvasStateManager board(RoomManager roomManager, String roomId, String boardId) {
        RoomContext room = roomManager.getRoom(roomId);
        assertThat(room).isNotNull();
        CanvasStateManager board = room.getBoard(boardId);
        assertThat(board).isNotNull();
        return board;
    }

    private void startServer(RoomManager roomManager, WalManager walManager) {
        server = new NioServer(0, roomManager, walManager);
        serverThread = new Thread(server, "nio-server-dead-session-read-test");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private int serverPort() throws Exception {
        return server.getBoundPortFuture().get(5, TimeUnit.SECONDS);
    }

    private static ClientSession findSession(RoomManager roomManager, String roomId, String clientId) {
        RoomContext room = roomManager.getRoom(roomId);
        if (room == null) {
            return null;
        }
        for (var key : room.getActiveKeys()) {
            if (key.attachment() instanceof ClientSession session && clientId.equals(session.clientId)) {
                return session;
            }
        }
        return null;
    }

    private static void writeFully(SocketChannel channel, ByteBuffer frame) throws Exception {
        ByteBuffer src = frame.duplicate();
        while (src.hasRemaining()) {
            channel.write(src);
        }
    }

    private static void drainUntilQuiet(SocketChannel channel, ByteBuffer acc, long quietMs, long maxWaitMs)
            throws Exception {
        channel.configureBlocking(false);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMs);
        long lastRead = System.nanoTime();
        while (System.nanoTime() < deadline) {
            int n = channel.read(acc);
            if (n > 0) {
                lastRead = System.nanoTime();
            }
            if (System.nanoTime() - lastRead > TimeUnit.MILLISECONDS.toNanos(quietMs)) {
                channel.configureBlocking(true);
                return;
            }
            Thread.sleep(10);
        }
        channel.configureBlocking(true);
    }
}
