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
import static org.awaitility.Awaitility.await;

class NioServerBoardLockTest {

    private static final String HOST = "127.0.0.1";

    @TempDir
    Path tempDir;

    private NioServer server;
    private Thread serverThread;
    private RoomManager roomManager;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (server != null) {
            server.stop();
        }
        if (serverThread != null) {
            serverThread.interrupt();
            serverThread.join(10_000);
        }
    }

    @Test
    void boardCreationLock_blocksMemberFromCreatingBoard() throws Exception {
        WalManager walManager = new WalManager(tempDir);
        roomManager = new RoomManager(walManager);
        startServer(roomManager, walManager);
        int port = serverPort();
        String roomId = "lock-room";
        String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;

        try (SocketChannel owner = SocketChannel.open();
             SocketChannel member = SocketChannel.open()) {
            owner.configureBlocking(true);
            member.configureBlocking(true);
            owner.connect(new InetSocketAddress(HOST, port));
            member.connect(new InetSocketAddress(HOST, port));

            writeFully(owner, MessageCodec.encodeHandshake("owner-client"));
            writeFully(member, MessageCodec.encodeHandshake("member-client"));
            drainUntilQuiet(owner, ByteBuffer.allocate(256 * 1024), 250, 5_000);
            drainUntilQuiet(member, ByteBuffer.allocate(256 * 1024), 250, 5_000);

            writeFully(owner, MessageCodec.encodeJoinRoom(roomId, "Owner", boardId));
            drainUntilQuiet(owner, ByteBuffer.allocate(256 * 1024), 250, 5_000);
            writeFully(member, MessageCodec.encodeJoinRoom(roomId, "Member", boardId));
            drainUntilQuiet(member, ByteBuffer.allocate(256 * 1024), 250, 5_000);

            RoomContext room = roomManager.getRoom(roomId);
            assertThat(room).isNotNull();
            assertThat(room.isBoardCreationLocked).isTrue();

            int boardsBefore = room.getActiveBoardIds().size();
            writeFully(member, MessageCodec.encodeSwitchBoard("Blocked-Board"));
            drainUntilQuiet(member, ByteBuffer.allocate(256 * 1024), 250, 2_000);

            ClientSession memberSession = findSession(roomId, "member-client");
            assertThat(memberSession).isNotNull();
            assertThat(memberSession.currentBoardId).isNotEqualTo("Blocked-Board");
            assertThat(room.getActiveBoardIds()).hasSize(boardsBefore);

            writeFully(owner, MessageCodec.encodeSwitchBoard("Owner-New-Board"));
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(room.getActiveBoardIds()).contains("Owner-New-Board"));
        }
    }

    @Test
    void toggleBoardLock_requiresManageRoomPermission() throws Exception {
        WalManager walManager = new WalManager(tempDir);
        roomManager = new RoomManager(walManager);
        startServer(roomManager, walManager);
        int port = serverPort();
        String roomId = "lock-auth-room";
        String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;

        try (SocketChannel owner = SocketChannel.open();
             SocketChannel member = SocketChannel.open()) {
            owner.configureBlocking(true);
            member.configureBlocking(true);
            owner.connect(new InetSocketAddress(HOST, port));
            member.connect(new InetSocketAddress(HOST, port));

            writeFully(owner, MessageCodec.encodeHandshake("owner-client"));
            writeFully(member, MessageCodec.encodeHandshake("member-client"));
            drainUntilQuiet(owner, ByteBuffer.allocate(256 * 1024), 250, 5_000);
            drainUntilQuiet(member, ByteBuffer.allocate(256 * 1024), 250, 5_000);

            writeFully(owner, MessageCodec.encodeJoinRoom(roomId, "Owner", boardId));
            drainUntilQuiet(owner, ByteBuffer.allocate(256 * 1024), 250, 5_000);
            writeFully(member, MessageCodec.encodeJoinRoom(roomId, "Member", boardId));
            drainUntilQuiet(member, ByteBuffer.allocate(256 * 1024), 250, 5_000);

            RoomContext room = roomManager.getRoom(roomId);
            assertThat(room).isNotNull();
            assertThat(room.isBoardCreationLocked).isTrue();

            writeFully(member, MessageCodec.encodeBoardLockCommand(false));
            drainUntilQuiet(member, ByteBuffer.allocate(256 * 1024), 250, 1_000);
            assertThat(room.isBoardCreationLocked).isTrue();

            ClientSession memberSession = findSession(roomId, "member-client");
            assertThat(memberSession).isNotNull();
            assertThat(RoomPermissions.canManageRoom(memberSession.permissions)).isFalse();
        }
    }

    @Test
    void joinRoom_blocksMemberFromCreatingBoardViaInitialBoardId() throws Exception {
        WalManager walManager = new WalManager(tempDir);
        roomManager = new RoomManager(walManager);
        startServer(roomManager, walManager);
        int port = serverPort();
        String roomId = "join-lock-room";
        String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;

        try (SocketChannel owner = SocketChannel.open();
             SocketChannel member = SocketChannel.open()) {
            owner.configureBlocking(true);
            member.configureBlocking(true);
            owner.connect(new InetSocketAddress(HOST, port));
            member.connect(new InetSocketAddress(HOST, port));

            writeFully(owner, MessageCodec.encodeHandshake("owner-client"));
            writeFully(member, MessageCodec.encodeHandshake("member-client"));
            drainUntilQuiet(owner, ByteBuffer.allocate(256 * 1024), 250, 5_000);
            drainUntilQuiet(member, ByteBuffer.allocate(256 * 1024), 250, 5_000);

            writeFully(owner, MessageCodec.encodeJoinRoom(roomId, "Owner", boardId));
            drainUntilQuiet(owner, ByteBuffer.allocate(256 * 1024), 250, 5_000);

            writeFully(member, MessageCodec.encodeJoinRoom(roomId, "Member", "Evil-Board"));
            drainUntilQuiet(member, ByteBuffer.allocate(256 * 1024), 250, 5_000);

            RoomContext room = roomManager.getRoom(roomId);
            assertThat(room).isNotNull();
            assertThat(room.isBoardCreationLocked).isTrue();

            ClientSession memberSession = findSession(roomId, "member-client");
            assertThat(memberSession).isNotNull();
            assertThat(memberSession.currentBoardId).isEqualTo(boardId);
            assertThat(memberSession.currentBoardId).isNotEqualTo("Evil-Board");
            assertThat(room.getActiveBoardIds()).contains(boardId);
            assertThat(room.getActiveBoardIds()).doesNotContain("Evil-Board");
        }
    }

    private ClientSession findSession(String roomId, String clientId) {
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

    private void startServer(RoomManager roomManager, WalManager walManager) {
        server = new NioServer(0, roomManager, walManager);
        serverThread = new Thread(server, "nio-server-board-lock-test");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private int serverPort() throws Exception {
        return server.getBoundPortFuture().get(5, TimeUnit.SECONDS);
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
