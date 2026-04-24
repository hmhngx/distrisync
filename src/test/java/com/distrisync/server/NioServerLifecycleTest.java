package com.distrisync.server;

import com.distrisync.protocol.Message;
import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.MessageCodec.LobbyRoomEntry;
import com.distrisync.protocol.PartialMessageException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class NioServerLifecycleTest {

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
            serverThread.join(3_000);
        }
    }

    @Test
    void testDeleteRoom_broadcastsFreshLobbyStateAfterDeletion() throws Exception {
        final String roomId = "room-a";
        final String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;

        WalManager walManager = new WalManager(tempDir);
        RoomManager roomManager = new RoomManager(walManager);

        walManager.append(roomId, boardId, new Message(com.distrisync.protocol.MessageType.SHAPE_COMMIT, "{\"seed\":1}"));
        roomManager.getOrCreateRoom(roomId);

        server = new NioServer(0, roomManager, walManager);
        serverThread = new Thread(server, "nio-server-lifecycle-test");
        serverThread.setDaemon(true);
        serverThread.start();
        int serverPort = server.getBoundPortFuture().get(5, TimeUnit.SECONDS);

        try (SocketChannel channel = SocketChannel.open()) {
            channel.configureBlocking(true);
            channel.connect(new InetSocketAddress(HOST, serverPort));

            writeFully(channel, MessageCodec.encodeHandshake("deleter", "deleter-client"));
            drainUntilQuiet(channel, ByteBuffer.allocate(256 * 1024), 250, 5_000);

            writeFully(channel, MessageCodec.encodeDeleteRoom(roomId));

            Message lobbyState = readUntilType(channel, ByteBuffer.allocate(256 * 1024),
                    com.distrisync.protocol.MessageType.LOBBY_STATE, 5_000);

            List<LobbyRoomEntry> entries = MessageCodec.decodeLobbyState(lobbyState);
            assertThat(entries).extracting(LobbyRoomEntry::roomId).doesNotContain(roomId, WalManager.sanitize(roomId));
            assertThat(walManager.getPersistedRoomIds()).doesNotContain(roomId, WalManager.sanitize(roomId));
        }
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

    private static Message readUntilType(
            SocketChannel channel,
            ByteBuffer acc,
            com.distrisync.protocol.MessageType targetType,
            long maxWaitMs) throws Exception {
        channel.configureBlocking(false);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMs);
        while (System.nanoTime() < deadline) {
            channel.read(acc);
            for (Message m : decodeAvailable(acc)) {
                if (m.type() == targetType) {
                    channel.configureBlocking(true);
                    return m;
                }
            }
            Thread.sleep(10);
        }
        channel.configureBlocking(true);
        throw new AssertionError("Timed out waiting for message type " + targetType);
    }

    private static List<Message> decodeAvailable(ByteBuffer acc) {
        var out = new java.util.ArrayList<Message>();
        acc.flip();
        while (acc.hasRemaining()) {
            int start = acc.position();
            try {
                out.add(MessageCodec.decode(acc));
            } catch (PartialMessageException e) {
                acc.position(start);
                break;
            }
        }
        acc.compact();
        return out;
    }
}
