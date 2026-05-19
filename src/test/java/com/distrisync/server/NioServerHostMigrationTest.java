package com.distrisync.server;

import com.distrisync.protocol.Message;
import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.MessageType;
import com.distrisync.protocol.PartialMessageException;
import com.distrisync.protocol.RoomPermissions;
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
 * Integration tests for deterministic host migration when the room OWNER departs.
 */
class NioServerHostMigrationTest {

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
    void ownerDisconnect_promotesOldestRemainingAndBroadcastsRoleUpdate() throws Exception {
        final String roomId = "migrate-disconnect";
        final String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;

        WalManager walManager = new WalManager(tempDir);
        RoomManager roomManager = new RoomManager(walManager);
        startServer(roomManager, walManager);
        int port = serverPort();

        try (SocketChannel owner = SocketChannel.open();
             SocketChannel memberOld = SocketChannel.open();
             SocketChannel memberNew = SocketChannel.open()) {
            owner.configureBlocking(true);
            memberOld.configureBlocking(true);
            memberNew.configureBlocking(true);

            joinRoom(owner, port, "owner", "owner-client", roomId, boardId);
            Thread.sleep(50);
            joinRoom(memberOld, port, "old", "member-old", roomId, boardId);
            Thread.sleep(50);
            joinRoom(memberNew, port, "new", "member-new", roomId, boardId);

            RoomContext room = roomManager.getRoom(roomId);
            assertThat(room.hostClientId).isEqualTo("owner-client");

            owner.close();
            Thread.sleep(500);

            assertThat(room.hostClientId).isEqualTo("member-old");
            ClientSession oldSession = findSession(roomManager, roomId, "member-old");
            ClientSession newSession = findSession(roomManager, roomId, "member-new");
            assertThat(oldSession).isNotNull();
            assertThat(newSession).isNotNull();
            assertThat(oldSession.permissions).isEqualTo(RoomPermissions.OWNER);
            assertThat(newSession.permissions).isEqualTo(RoomPermissions.MEMBER);

            List<Message> received = drainMessages(memberNew, 2_000);
            assertThat(received).anyMatch(m -> m.type() == MessageType.ROLE_UPDATE);
            MessageCodec.RoleUpdatePayload roleUpdate = received.stream()
                    .filter(m -> m.type() == MessageType.ROLE_UPDATE)
                    .findFirst()
                    .map(MessageCodec::decodeRoleUpdate)
                    .orElseThrow();
            assertThat(roleUpdate.newHostClientId()).isEqualTo("member-old");
            assertThat(roleUpdate.newPermissions()).isEqualTo(RoomPermissions.OWNER);
        }
    }

    @Test
    void ownerLeaveRoom_promotesOldestRemainingAndBroadcastsRoleUpdate() throws Exception {
        final String roomId = "migrate-leave";
        final String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;

        WalManager walManager = new WalManager(tempDir);
        RoomManager roomManager = new RoomManager(walManager);
        startServer(roomManager, walManager);
        int port = serverPort();

        try (SocketChannel owner = SocketChannel.open();
             SocketChannel memberOld = SocketChannel.open();
             SocketChannel memberNew = SocketChannel.open()) {
            owner.configureBlocking(true);
            memberOld.configureBlocking(true);
            memberNew.configureBlocking(true);

            joinRoom(owner, port, "owner", "owner-client", roomId, boardId);
            Thread.sleep(50);
            joinRoom(memberOld, port, "old", "member-old", roomId, boardId);
            Thread.sleep(50);
            joinRoom(memberNew, port, "new", "member-new", roomId, boardId);

            writeFully(owner, MessageCodec.encodeLeaveRoom());
            Thread.sleep(500);

            RoomContext room = roomManager.getRoom(roomId);
            assertThat(room).isNotNull();
            assertThat(room.hostClientId).isEqualTo("member-old");
            assertThat(findSession(roomManager, roomId, "member-old").permissions)
                    .isEqualTo(RoomPermissions.OWNER);
            assertThat(findSession(roomManager, roomId, "member-new").permissions)
                    .isEqualTo(RoomPermissions.MEMBER);

            List<Message> received = drainMessages(memberNew, 2_000);
            assertThat(received).anyMatch(m -> m.type() == MessageType.ROLE_UPDATE);
            MessageCodec.RoleUpdatePayload roleUpdate = received.stream()
                    .filter(m -> m.type() == MessageType.ROLE_UPDATE)
                    .findFirst()
                    .map(MessageCodec::decodeRoleUpdate)
                    .orElseThrow();
            assertThat(roleUpdate.newHostClientId()).isEqualTo("member-old");
            assertThat(roleUpdate.newPermissions()).isEqualTo(RoomPermissions.OWNER);
        }
    }

    @Test
    void promotedOwner_canDeleteRoom() throws Exception {
        final String roomId = "migrate-delete";
        final String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;

        WalManager walManager = new WalManager(tempDir);
        RoomManager roomManager = new RoomManager(walManager);
        walManager.append(roomId, boardId, new Message(MessageType.SHAPE_COMMIT, "{\"seed\":1}"));
        startServer(roomManager, walManager);
        int port = serverPort();

        try (SocketChannel owner = SocketChannel.open();
             SocketChannel memberOld = SocketChannel.open()) {
            owner.configureBlocking(true);
            memberOld.configureBlocking(true);

            joinRoom(owner, port, "owner", "owner-client", roomId, boardId);
            Thread.sleep(50);
            joinRoom(memberOld, port, "old", "member-old", roomId, boardId);

            owner.close();
            Thread.sleep(500);

            writeFully(memberOld, MessageCodec.encodeDeleteRoom(roomId));
            Thread.sleep(500);

            assertThat(roomManager.getRoom(roomId)).isNull();
            assertThat(walManager.getPersistedRoomIds())
                    .doesNotContain(roomId, WalManager.sanitize(roomId));
        }
    }

    private void startServer(RoomManager roomManager, WalManager walManager) {
        server = new NioServer(0, roomManager, walManager);
        serverThread = new Thread(server, "nio-server-host-migration-test");
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
