package com.distrisync.server;

import com.distrisync.protocol.Message;
import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.MessageCodec.LobbyRoomEntry;
import com.distrisync.protocol.MessageType;
import com.distrisync.protocol.PartialMessageException;
import com.distrisync.protocol.RoomPermissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Pipe;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Phase 1 RBAC on the NIO hot path.
 */
class NioServerRbacTest {

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
    void lobbyClient_deleteRoomIsSilentlyDropped() throws Exception {
        final String roomId = "protected-room";
        final String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;

        WalManager walManager = new WalManager(tempDir);
        RoomManager roomManager = new RoomManager(walManager);
        walManager.append(roomId, boardId, new Message(MessageType.SHAPE_COMMIT, "{\"seed\":1}"));
        roomManager.getOrCreateRoom(roomId);

        startServer(roomManager, walManager);

        try (SocketChannel channel = SocketChannel.open()) {
            channel.configureBlocking(true);
            channel.connect(new InetSocketAddress(HOST, serverPort()));

            writeFully(channel, MessageCodec.encodeHandshake("lobby-user", "lobby-client"));
            drainUntilQuiet(channel, ByteBuffer.allocate(256 * 1024), 250, 5_000);

            writeFully(channel, MessageCodec.encodeDeleteRoom(roomId));
            drainUntilQuiet(channel, ByteBuffer.allocate(256 * 1024), 250, 2_000);

            assertThat(walManager.getPersistedRoomIds())
                    .contains(WalManager.sanitize(roomId));
            assertThat(roomManager.getRoom(roomId)).isNotNull();
        }
    }

    @Test
    void memberClient_deleteRoomIsSilentlyDropped() throws Exception {
        final String roomId = "shared-room";
        final String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;

        WalManager walManager = new WalManager(tempDir);
        RoomManager roomManager = new RoomManager(walManager);
        walManager.append(roomId, boardId, new Message(MessageType.SHAPE_COMMIT, "{\"seed\":1}"));

        startServer(roomManager, walManager);
        int port = serverPort();

        try (SocketChannel owner = SocketChannel.open();
             SocketChannel member = SocketChannel.open()) {
            owner.configureBlocking(true);
            member.configureBlocking(true);
            owner.connect(new InetSocketAddress(HOST, port));
            member.connect(new InetSocketAddress(HOST, port));

            writeFully(owner, MessageCodec.encodeHandshake("owner", "owner-client"));
            drainUntilQuiet(owner, ByteBuffer.allocate(256 * 1024), 250, 5_000);
            writeFully(owner, MessageCodec.encodeJoinRoom(roomId, boardId));
            drainUntilQuiet(owner, ByteBuffer.allocate(256 * 1024), 250, 5_000);

            writeFully(member, MessageCodec.encodeHandshake("member", "member-client"));
            drainUntilQuiet(member, ByteBuffer.allocate(256 * 1024), 250, 5_000);
            writeFully(member, MessageCodec.encodeJoinRoom(roomId, boardId));
            drainUntilQuiet(member, ByteBuffer.allocate(256 * 1024), 250, 5_000);

            ClientSession ownerSession = findSession(roomManager, roomId, "owner-client");
            ClientSession memberSession = findSession(roomManager, roomId, "member-client");
            assertThat(ownerSession).isNotNull();
            assertThat(memberSession).isNotNull();
            assertThat(ownerSession.permissions).isEqualTo(RoomPermissions.OWNER);
            assertThat(memberSession.permissions).isEqualTo(RoomPermissions.MEMBER);

            writeFully(member, MessageCodec.encodeDeleteRoom(roomId));
            drainUntilQuiet(member, ByteBuffer.allocate(256 * 1024), 250, 2_000);

            assertThat(walManager.getPersistedRoomIds()).contains(WalManager.sanitize(roomId));
            assertThat(roomManager.getRoom(roomId)).isNotNull();
            assertThat(roomManager.getRoom(roomId).getActiveClientCount()).isEqualTo(2);
        }
    }

    @Test
    void memberClient_moderationKickIsSilentlyDropped() throws Exception {
        final String roomId = "mod-room";
        final String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;

        WalManager walManager = new WalManager(tempDir);
        RoomManager roomManager = new RoomManager(walManager);
        startServer(roomManager, walManager);
        int port = serverPort();

        try (SocketChannel owner = SocketChannel.open();
             SocketChannel member = SocketChannel.open();
             SocketChannel victim = SocketChannel.open()) {
            owner.configureBlocking(true);
            member.configureBlocking(true);
            victim.configureBlocking(true);
            owner.connect(new InetSocketAddress(HOST, port));
            member.connect(new InetSocketAddress(HOST, port));
            victim.connect(new InetSocketAddress(HOST, port));

            writeFully(owner, MessageCodec.encodeHandshake("owner", "owner-client"));
            drainUntilQuiet(owner, ByteBuffer.allocate(256 * 1024), 250, 5_000);
            writeFully(owner, MessageCodec.encodeJoinRoom(roomId, boardId));
            drainUntilQuiet(owner, ByteBuffer.allocate(256 * 1024), 250, 5_000);

            writeFully(member, MessageCodec.encodeHandshake("member", "member-client"));
            drainUntilQuiet(member, ByteBuffer.allocate(256 * 1024), 250, 5_000);
            writeFully(member, MessageCodec.encodeJoinRoom(roomId, boardId));
            drainUntilQuiet(member, ByteBuffer.allocate(256 * 1024), 250, 5_000);

            writeFully(victim, MessageCodec.encodeHandshake("victim", "victim-client"));
            drainUntilQuiet(victim, ByteBuffer.allocate(256 * 1024), 250, 5_000);
            writeFully(victim, MessageCodec.encodeJoinRoom(roomId, boardId));
            drainUntilQuiet(victim, ByteBuffer.allocate(256 * 1024), 250, 5_000);

            assertThat(roomManager.getRoom(roomId).getActiveClientCount()).isEqualTo(3);

            writeFully(member, MessageCodec.encodeModerationAction("KICK", "victim-client", "test"));
            drainUntilQuiet(member, ByteBuffer.allocate(256 * 1024), 250, 2_000);

            assertThat(roomManager.getRoom(roomId).getActiveClientCount()).isEqualTo(3);
            assertThat(findSession(roomManager, roomId, "victim-client")).isNotNull();
        }
    }

    @Test
    void ownerClient_moderationKickRemovesTarget() throws Exception {
        final String roomId = "kick-room";
        final String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;

        WalManager walManager = new WalManager(tempDir);
        RoomManager roomManager = new RoomManager(walManager);
        startServer(roomManager, walManager);
        int port = serverPort();

        try (SocketChannel owner = SocketChannel.open();
             SocketChannel victim = SocketChannel.open()) {
            owner.configureBlocking(true);
            victim.configureBlocking(true);
            owner.connect(new InetSocketAddress(HOST, port));
            victim.connect(new InetSocketAddress(HOST, port));

            writeFully(owner, MessageCodec.encodeHandshake("owner", "owner-client"));
            drainUntilQuiet(owner, ByteBuffer.allocate(256 * 1024), 250, 5_000);
            writeFully(owner, MessageCodec.encodeJoinRoom(roomId, boardId));
            drainUntilQuiet(owner, ByteBuffer.allocate(256 * 1024), 250, 5_000);

            writeFully(victim, MessageCodec.encodeHandshake("victim", "victim-client"));
            drainUntilQuiet(victim, ByteBuffer.allocate(256 * 1024), 250, 5_000);
            writeFully(victim, MessageCodec.encodeJoinRoom(roomId, boardId));
            drainUntilQuiet(victim, ByteBuffer.allocate(256 * 1024), 250, 5_000);

            assertThat(roomManager.getRoom(roomId).getActiveClientCount()).isEqualTo(2);

            writeFully(owner, MessageCodec.encodeModerationAction("KICK", "victim-client", "removed"));
            Thread.sleep(500);

            assertThat(roomManager.getRoom(roomId).getActiveClientCount()).isEqualTo(1);
            assertThat(findSession(roomManager, roomId, "victim-client")).isNull();
        }
    }

    @Test
    void assignClientToRoom_firstJoinerIsOwner_secondIsMember() throws Exception {
        WalManager walManager = new WalManager(tempDir);
        RoomManager roomManager = new RoomManager(walManager);
        Selector sel = Selector.open();
        Pipe p1 = Pipe.open();
        Pipe p2 = Pipe.open();
        try {
            p1.source().configureBlocking(false);
            p2.source().configureBlocking(false);
            SelectionKey keyA = p1.source().register(sel, SelectionKey.OP_READ);
            SelectionKey keyB = p2.source().register(sel, SelectionKey.OP_READ);
            ClientSession sessionA = new ClientSession();
            ClientSession sessionB = new ClientSession();
            sessionA.clientId = "owner-client";
            sessionB.clientId = "member-client";
            keyA.attach(sessionA);
            keyB.attach(sessionB);

            roomManager.registerHandshakeToLobby(keyA);
            roomManager.registerHandshakeToLobby(keyB);

            RoomContext room = roomManager.assignClientToRoom(keyA, "rbac-room", "");
            assertThat(room.hostClientId).isEqualTo("owner-client");
            assertThat(sessionA.permissions).isEqualTo(RoomPermissions.OWNER);

            roomManager.assignClientToRoom(keyB, "rbac-room", "");
            assertThat(sessionB.permissions).isEqualTo(RoomPermissions.MEMBER);
            assertThat(room.hostClientId).isEqualTo("owner-client");
        } finally {
            sel.close();
            p1.source().close();
            p1.sink().close();
            p2.source().close();
            p2.sink().close();
        }
    }

    private void startServer(RoomManager roomManager, WalManager walManager) {
        server = new NioServer(0, roomManager, walManager);
        serverThread = new Thread(server, "nio-server-rbac-test");
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
