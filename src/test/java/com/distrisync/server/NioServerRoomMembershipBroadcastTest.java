package com.distrisync.server;

import com.distrisync.protocol.Message;
import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.MessageType;
import com.distrisync.protocol.PartialMessageException;
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
 * Verifies server→client {@code JOIN_ROOM} / {@code LEAVE_ROOM} membership broadcasts.
 */
class NioServerRoomMembershipBroadcastTest {

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
    void peerJoin_broadcastsJoinRoomToExistingMembers() throws Exception {
        final String roomId = "membership-join";
        final String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;

        WalManager walManager = new WalManager(tempDir);
        RoomManager roomManager = new RoomManager(walManager);
        startServer(roomManager, walManager);
        int port = serverPort();

        try (SocketChannel first = SocketChannel.open();
             SocketChannel second = SocketChannel.open()) {
            first.configureBlocking(true);
            second.configureBlocking(true);

            joinRoom(first, port, "Alice", "client-a", roomId, boardId);
            joinRoom(second, port, "Bob", "client-b", roomId, boardId);

            List<Message> receivedByFirst = drainMessages(first, 2_000);
            assertThat(receivedByFirst).anyMatch(m -> m.type() == MessageType.JOIN_ROOM);
            MessageCodec.RoomMemberJoinedPayload join = receivedByFirst.stream()
                    .filter(m -> m.type() == MessageType.JOIN_ROOM)
                    .map(MessageCodec::decodeRoomMemberJoined)
                    .filter(p -> "client-b".equals(p.clientId()))
                    .findFirst()
                    .orElseThrow();
            assertThat(join.authorName()).isEqualTo("Bob");
        }
    }

    @Test
    void lateJoiner_receivesJoinRoomHydrationBeforeBoardSwitch() throws Exception {
        final String roomId = "membership-late-hydrate";
        final String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;

        WalManager walManager = new WalManager(tempDir);
        RoomManager roomManager = new RoomManager(walManager);
        startServer(roomManager, walManager);
        int port = serverPort();

        try (SocketChannel first = SocketChannel.open();
             SocketChannel second = SocketChannel.open()) {
            first.configureBlocking(true);
            second.configureBlocking(true);

            joinRoom(first, port, "Alice", "client-a", roomId, boardId);

            second.connect(new InetSocketAddress(HOST, port));
            writeFully(second, MessageCodec.encodeHandshake("Bob", "client-b"));
            drainUntilQuiet(second, ByteBuffer.allocate(256 * 1024), 250, 5_000);
            writeFully(second, MessageCodec.encodeJoinRoom(roomId, boardId));

            List<Message> receivedBySecond = drainMessages(second, 2_000);

            MessageCodec.RoomMemberJoinedPayload hydration = receivedBySecond.stream()
                    .filter(m -> m.type() == MessageType.JOIN_ROOM)
                    .map(MessageCodec::decodeRoomMemberJoined)
                    .filter(p -> "client-a".equals(p.clientId()))
                    .findFirst()
                    .orElseThrow();
            assertThat(hydration.authorName()).isEqualTo("Alice");

            int joinIdx = indexOfJoinRoomFor(receivedBySecond, "client-a");
            int boardIdx = indexOfBoardSwitchFor(receivedBySecond, "client-a");
            int voiceIdx = indexOfVoiceStateFor(receivedBySecond, "client-a");
            assertThat(joinIdx).isGreaterThanOrEqualTo(0);
            assertThat(boardIdx).isGreaterThanOrEqualTo(0);
            assertThat(voiceIdx).isGreaterThanOrEqualTo(0);
            assertThat(joinIdx).isLessThan(boardIdx);
            assertThat(boardIdx).isLessThan(voiceIdx);
        }
    }

    @Test
    void peerDisconnect_broadcastsLeaveRoomWithClientId() throws Exception {
        final String roomId = "membership-leave";
        final String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;

        WalManager walManager = new WalManager(tempDir);
        RoomManager roomManager = new RoomManager(walManager);
        startServer(roomManager, walManager);
        int port = serverPort();

        try (SocketChannel leaver = SocketChannel.open();
             SocketChannel survivor = SocketChannel.open()) {
            leaver.configureBlocking(true);
            survivor.configureBlocking(true);

            joinRoom(leaver, port, "Alice", "client-a", roomId, boardId);
            joinRoom(survivor, port, "Bob", "client-b", roomId, boardId);
            drainMessages(survivor, 500);

            leaver.close();
            Thread.sleep(500);

            List<Message> received = drainMessages(survivor, 2_000);
            assertThat(received).anyMatch(m -> m.type() == MessageType.LEAVE_ROOM);
            String departedId = received.stream()
                    .filter(m -> m.type() == MessageType.LEAVE_ROOM)
                    .map(MessageCodec::decodeRoomMemberLeft)
                    .filter("client-a"::equals)
                    .findFirst()
                    .orElseThrow();
            assertThat(departedId).isEqualTo("client-a");
        }
    }

    @Test
    void voluntaryLeaveRoom_broadcastsLeaveRoomToRemainingPeers() throws Exception {
        final String roomId = "membership-voluntary-leave";
        final String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;

        WalManager walManager = new WalManager(tempDir);
        RoomManager roomManager = new RoomManager(walManager);
        startServer(roomManager, walManager);
        int port = serverPort();

        try (SocketChannel leaver = SocketChannel.open();
             SocketChannel survivor = SocketChannel.open()) {
            leaver.configureBlocking(true);
            survivor.configureBlocking(true);

            joinRoom(leaver, port, "Alice", "client-a", roomId, boardId);
            joinRoom(survivor, port, "Bob", "client-b", roomId, boardId);
            drainMessages(survivor, 500);

            writeFully(leaver, MessageCodec.encodeLeaveRoom());
            drainUntilQuiet(leaver, ByteBuffer.allocate(256 * 1024), 250, 5_000);

            List<Message> received = drainMessages(survivor, 2_000);
            assertThat(received).anyMatch(m -> m.type() == MessageType.LEAVE_ROOM);
            assertThat(received.stream()
                    .filter(m -> m.type() == MessageType.LEAVE_ROOM)
                    .map(MessageCodec::decodeRoomMemberLeft)
                    .anyMatch("client-a"::equals)).isTrue();
        }
    }

    private void startServer(RoomManager roomManager, WalManager walManager) {
        server = new NioServer(0, roomManager, walManager);
        serverThread = new Thread(server, "nio-server-membership-broadcast-test");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private int serverPort() throws Exception {
        return server.getBoundPortFuture().get(5, TimeUnit.SECONDS);
    }

    private static void joinRoom(SocketChannel channel, int port, String authorName, String clientId,
                                 String roomId, String boardId) throws Exception {
        channel.connect(new InetSocketAddress(HOST, port));
        writeFully(channel, MessageCodec.encodeHandshake(authorName, clientId));
        drainUntilQuiet(channel, ByteBuffer.allocate(256 * 1024), 250, 5_000);
        writeFully(channel, MessageCodec.encodeJoinRoom(roomId, boardId));
        drainUntilQuiet(channel, ByteBuffer.allocate(256 * 1024), 250, 5_000);
    }

    private static List<Message> drainMessages(SocketChannel channel, long maxWaitMs) throws Exception {
        ByteBuffer acc = ByteBuffer.allocate(256 * 1024);
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

    private static int indexOfJoinRoomFor(List<Message> messages, String clientId) {
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (m.type() != MessageType.JOIN_ROOM) {
                continue;
            }
            try {
                if (clientId.equals(MessageCodec.decodeRoomMemberJoined(m).clientId())) {
                    return i;
                }
            } catch (IllegalArgumentException ignored) {
                // client JOIN_ROOM request shape, not peer-join
            }
        }
        return -1;
    }

    private static int indexOfBoardSwitchFor(List<Message> messages, String clientId) {
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (m.type() == MessageType.BOARD_SWITCH
                    && clientId.equals(MessageCodec.decodeBoardSwitch(m).clientId())) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfVoiceStateFor(List<Message> messages, String clientId) {
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (m.type() == MessageType.VOICE_STATE
                    && clientId.equals(MessageCodec.decodeVoiceState(m).clientId())) {
                return i;
            }
        }
        return -1;
    }

    private static List<Message> decodeAvailable(ByteBuffer acc) {
        var out = new ArrayList<Message>();
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
