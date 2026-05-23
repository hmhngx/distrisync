package com.distrisync.server;

import com.distrisync.model.Line;
import com.distrisync.model.Shape;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Join hydration streams board state as {@code SNAPSHOT} {@code []},
 * chunked {@code MUTATION_BATCH} frames, and {@code SNAPSHOT_END}.
 */
class NioServerChunkedSnapshotTest {

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
    void join_streamsChunkedSnapshotWithAllSeededShapes() throws Exception {
        final String roomId = "chunked-snapshot-room";
        final String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;
        final int shapeCount = 50;

        WalManager walManager = new WalManager(tempDir);
        RoomManager roomManager = new RoomManager(walManager);
        RoomContext room = roomManager.getOrCreateRoom(roomId);
        CanvasStateManager board = room.getBoard(boardId);
        for (int i = 0; i < shapeCount; i++) {
            board.applyMutation(Line.create("#89b4fa", i, i, i + 1, i + 1, 2.0, "seed", "server"));
        }

        startServer(roomManager, walManager);
        int port = serverPort();

        try (SocketChannel channel = SocketChannel.open()) {
            channel.configureBlocking(true);
            channel.connect(new InetSocketAddress(HOST, port));
            writeFully(channel, MessageCodec.encodeHandshake("joiner", "joiner-client"));
            drainUntilQuiet(channel, ByteBuffer.allocate(256 * 1024), 250, 5_000);

            writeFully(channel, MessageCodec.encodeJoinRoom(roomId, boardId));
            List<Message> afterJoin = drainMessages(channel, 5_000);

            int snapIdx = indexOfType(afterJoin, MessageType.SNAPSHOT);
            int snapEndIdx = indexOfType(afterJoin, MessageType.SNAPSHOT_END);
            assertThat(snapIdx).isGreaterThanOrEqualTo(0);
            assertThat(snapEndIdx).isGreaterThan(snapIdx);
            assertThat(afterJoin.get(snapIdx).payload()).isEqualTo("[]");

            List<Message> hydrationBodies = afterJoin.subList(snapIdx + 1, snapEndIdx);
            assertThat(hydrationBodies)
                    .as("shapes must be delivered as MUTATION_BATCH frames between SNAPSHOT and SNAPSHOT_END")
                    .isNotEmpty()
                    .allMatch(m -> m.type() == MessageType.MUTATION_BATCH);

            List<Shape> decoded = new ArrayList<>();
            for (Message batch : hydrationBodies) {
                decoded.addAll(ShapeCodec.decodeMutationBatch(batch.payload()));
            }
            assertThat(decoded).hasSize(shapeCount);
            assertThat(afterJoin.get(snapEndIdx).payload()).isEmpty();
        }
    }

    private void startServer(RoomManager roomManager, WalManager walManager) {
        server = new NioServer(0, roomManager, walManager);
        serverThread = new Thread(server, "nio-server-chunked-snapshot-test");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private int serverPort() throws Exception {
        return server.getBoundPortFuture().get(5, TimeUnit.SECONDS);
    }

    private static int indexOfType(List<Message> messages, MessageType type) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).type() == type) {
                return i;
            }
        }
        return -1;
    }

    private static List<Message> drainMessages(SocketChannel channel, long maxWaitMs) throws Exception {
        ByteBuffer acc = ByteBuffer.allocate(512 * 1024);
        channel.configureBlocking(false);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMs);
        long lastRead = System.nanoTime();
        while (System.nanoTime() < deadline) {
            int n = channel.read(acc);
            if (n > 0) {
                lastRead = System.nanoTime();
            }
            if (System.nanoTime() - lastRead > TimeUnit.MILLISECONDS.toNanos(250)) {
                break;
            }
            Thread.sleep(10);
        }
        channel.configureBlocking(true);
        return decodeAvailable(acc);
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
                decodeAvailable(acc);
            }
            if (System.nanoTime() - lastRead > TimeUnit.MILLISECONDS.toNanos(quietMs)) {
                channel.configureBlocking(true);
                return;
            }
            Thread.sleep(10);
        }
        channel.configureBlocking(true);
    }

    private static List<Message> decodeAvailable(ByteBuffer acc) {
        var out = new ArrayList<Message>();
        acc.flip();
        while (acc.hasRemaining()) {
            int start = acc.position();
            try {
                out.add(MessageCodec.decode(acc));
            } catch (com.distrisync.protocol.PartialMessageException e) {
                acc.position(start);
                break;
            }
        }
        acc.compact();
        return out;
    }
}
