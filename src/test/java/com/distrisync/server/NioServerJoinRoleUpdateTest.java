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
 * Join path must deliver authoritative {@link MessageType#ROLE_UPDATE} before chunked
 * {@link MessageType#SNAPSHOT} / {@link MessageType#SNAPSHOT_END} hydration,
 * broadcast joiner roles to the room, and hydrate peer permission masks for late joiners.
 */
class NioServerJoinRoleUpdateTest {

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
    void firstJoiner_receivesRoleUpdateBeforeSnapshot_withOwnerAndSelfAsHost() throws Exception {
        final String roomId = "join-role-room";
        final String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;

        WalManager walManager = new WalManager(tempDir);
        RoomManager roomManager = new RoomManager(walManager);
        startServer(roomManager, walManager);
        int port = serverPort();

        try (SocketChannel channel = SocketChannel.open()) {
            channel.configureBlocking(true);
            channel.connect(new InetSocketAddress(HOST, port));
            writeFully(channel, MessageCodec.encodeHandshake("owner-client"));
            drainUntilQuiet(channel, ByteBuffer.allocate(256 * 1024), 250, 5_000);

            writeFully(channel, MessageCodec.encodeJoinRoom(roomId, "owner", boardId));
            List<Message> afterJoin = drainMessages(channel, 5_000);

            int roleIdx = indexOfType(afterJoin, MessageType.ROLE_UPDATE);
            int snapIdx = indexOfType(afterJoin, MessageType.SNAPSHOT);
            int snapEndIdx = indexOfType(afterJoin, MessageType.SNAPSHOT_END);
            assertThat(roleIdx).as("ROLE_UPDATE must be present after join").isGreaterThanOrEqualTo(0);
            assertThat(snapIdx).as("SNAPSHOT must be present after join").isGreaterThanOrEqualTo(0);
            assertThat(snapEndIdx).as("SNAPSHOT_END must be present after join").isGreaterThanOrEqualTo(0);
            assertThat(roleIdx).as("ROLE_UPDATE must precede SNAPSHOT on join").isLessThan(snapIdx);
            assertThat(snapIdx).as("SNAPSHOT must precede SNAPSHOT_END on join").isLessThan(snapEndIdx);
            assertThat(afterJoin.get(snapIdx).payload()).isEqualTo("[]");

            MessageCodec.RoleUpdatePayload role = MessageCodec.decodeRoleUpdate(afterJoin.get(roleIdx));
            assertThat(role.newHostClientId()).isEqualTo("owner-client");
            assertThat(role.newPermissions()).isEqualTo(RoomPermissions.OWNER);
            assertThat(role.roomHostClientId()).isEqualTo("owner-client");
        }
    }

    @Test
    void secondJoiner_receivesMemberRoleUpdateWithRoomHostBeforeSnapshot() throws Exception {
        final String roomId = "join-role-shared";
        final String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;

        WalManager walManager = new WalManager(tempDir);
        RoomManager roomManager = new RoomManager(walManager);
        startServer(roomManager, walManager);
        int port = serverPort();

        try (SocketChannel owner = SocketChannel.open();
             SocketChannel member = SocketChannel.open()) {
            owner.configureBlocking(true);
            member.configureBlocking(true);
            owner.connect(new InetSocketAddress(HOST, port));
            writeFully(owner, MessageCodec.encodeHandshake("owner-client"));
            drainUntilQuiet(owner, ByteBuffer.allocate(256 * 1024), 250, 5_000);
            writeFully(owner, MessageCodec.encodeJoinRoom(roomId, "owner", boardId));
            drainUntilQuiet(owner, ByteBuffer.allocate(256 * 1024), 250, 5_000);

            member.connect(new InetSocketAddress(HOST, port));
            writeFully(member, MessageCodec.encodeHandshake("member-client"));
            drainUntilQuiet(member, ByteBuffer.allocate(256 * 1024), 250, 5_000);

            writeFully(member, MessageCodec.encodeJoinRoom(roomId, "member", boardId));
            List<Message> afterJoin = drainMessages(member, 5_000);

            int roleIdx = indexOfType(afterJoin, MessageType.ROLE_UPDATE);
            int snapIdx = indexOfType(afterJoin, MessageType.SNAPSHOT);
            int snapEndIdx = indexOfType(afterJoin, MessageType.SNAPSHOT_END);
            assertThat(roleIdx).isGreaterThanOrEqualTo(0);
            assertThat(snapIdx).isGreaterThanOrEqualTo(0);
            assertThat(snapEndIdx).isGreaterThanOrEqualTo(0);
            assertThat(roleIdx).isLessThan(snapIdx);
            assertThat(snapIdx).isLessThan(snapEndIdx);
            assertThat(afterJoin.get(snapIdx).payload()).isEqualTo("[]");

            MessageCodec.RoleUpdatePayload role = MessageCodec.decodeRoleUpdate(afterJoin.get(roleIdx));
            assertThat(role.newHostClientId()).isEqualTo("member-client");
            assertThat(role.newPermissions()).isEqualTo(RoomPermissions.MEMBER);
            assertThat(role.roomHostClientId()).isEqualTo("owner-client");

            MessageCodec.RoleUpdatePayload ownerHydration = afterJoin.stream()
                    .filter(m -> m.type() == MessageType.ROLE_UPDATE)
                    .map(MessageCodec::decodeRoleUpdate)
                    .filter(p -> "owner-client".equals(p.newHostClientId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("expected hydrated ROLE_UPDATE for room owner"));
            assertThat(ownerHydration.newPermissions()).isEqualTo(RoomPermissions.OWNER);
            assertThat(ownerHydration.roomHostClientId()).isEqualTo("owner-client");

            List<Message> ownerAfterMemberJoin = drainMessages(owner, 2_000);
            assertThat(ownerAfterMemberJoin.stream()
                    .filter(m -> m.type() == MessageType.ROLE_UPDATE)
                    .map(MessageCodec::decodeRoleUpdate)
                    .anyMatch(p -> "member-client".equals(p.newHostClientId())
                            && p.newPermissions() == RoomPermissions.MEMBER))
                    .isTrue();
        }
    }

    private void startServer(RoomManager roomManager, WalManager walManager) {
        server = new NioServer(0, roomManager, walManager);
        serverThread = new Thread(server, "nio-server-join-role-update-test");
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
