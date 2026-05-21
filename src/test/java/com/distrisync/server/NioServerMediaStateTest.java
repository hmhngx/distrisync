package com.distrisync.server;

import com.distrisync.protocol.Message;
import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.MessageType;
import com.distrisync.protocol.PartialMessageException;
import com.distrisync.protocol.RoomPermissions;
import com.distrisync.server.backplane.BackplaneEnvelope;
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

class NioServerMediaStateTest {

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
    void mediaControl_play_broadcastsStateUpdateToPeers() throws Exception {
        WalManager walManager = new WalManager(tempDir);
        roomManager = new RoomManager(walManager);
        startServer(roomManager, walManager);
        int port = serverPort();
        String roomId = "media-play-room";
        String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;

        try (SocketChannel owner = SocketChannel.open();
             SocketChannel member = SocketChannel.open()) {
            owner.configureBlocking(true);
            member.configureBlocking(true);
            owner.connect(new InetSocketAddress(HOST, port));
            member.connect(new InetSocketAddress(HOST, port));

            writeFully(owner, MessageCodec.encodeHandshake("Owner", "owner-client"));
            writeFully(member, MessageCodec.encodeHandshake("Member", "member-client"));
            drainUntilQuiet(owner, ByteBuffer.allocate(256 * 1024), 250, 5_000);
            drainUntilQuiet(member, ByteBuffer.allocate(256 * 1024), 250, 5_000);

            writeFully(owner, MessageCodec.encodeJoinRoom(roomId, boardId));
            drainUntilQuiet(owner, ByteBuffer.allocate(256 * 1024), 250, 5_000);
            writeFully(member, MessageCodec.encodeJoinRoom(roomId, boardId));
            drainUntilQuiet(member, ByteBuffer.allocate(256 * 1024), 250, 5_000);

            RoomContext room = roomManager.getRoom(roomId);
            assertThat(room).isNotNull();
            assertThat(room.currentMediaState).isNull();

            writeFully(owner, MessageCodec.encodeMediaControl(
                    new MessageCodec.MediaControlPayload("PLAY", 5.0, "")));
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(room.currentMediaState).isNotNull());
            assertThat(room.currentMediaState.state()).isEqualTo("PLAYING");
            assertThat(room.currentMediaState.mediaTimeSeconds()).isEqualTo(5.0);
            assertThat(room.currentMediaState.serverEpochMs()).isGreaterThan(0);

            List<Message> memberMessages = drainMessages(member, 2_000);
            assertThat(memberMessages).anyMatch(m -> m.type() == MessageType.MEDIA_STATE_UPDATE);
            MessageCodec.MediaStatePayload received = memberMessages.stream()
                    .filter(m -> m.type() == MessageType.MEDIA_STATE_UPDATE)
                    .map(MessageCodec::decodeMediaState)
                    .findFirst()
                    .orElseThrow();
            assertThat(received.state()).isEqualTo("PLAYING");
            assertThat(received.mediaTimeSeconds()).isEqualTo(5.0);
        }
    }

    @Test
    void mediaControl_deniedForMemberWithoutManageMedia() throws Exception {
        WalManager walManager = new WalManager(tempDir);
        roomManager = new RoomManager(walManager);
        startServer(roomManager, walManager);
        int port = serverPort();
        String roomId = "media-deny-room";
        String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;

        try (SocketChannel owner = SocketChannel.open();
             SocketChannel member = SocketChannel.open()) {
            owner.configureBlocking(true);
            member.configureBlocking(true);
            owner.connect(new InetSocketAddress(HOST, port));
            member.connect(new InetSocketAddress(HOST, port));

            writeFully(owner, MessageCodec.encodeHandshake("Owner", "owner-client"));
            writeFully(member, MessageCodec.encodeHandshake("Member", "member-client"));
            drainUntilQuiet(owner, ByteBuffer.allocate(256 * 1024), 250, 5_000);
            drainUntilQuiet(member, ByteBuffer.allocate(256 * 1024), 250, 5_000);

            writeFully(owner, MessageCodec.encodeJoinRoom(roomId, boardId));
            drainUntilQuiet(owner, ByteBuffer.allocate(256 * 1024), 250, 5_000);
            writeFully(member, MessageCodec.encodeJoinRoom(roomId, boardId));
            drainUntilQuiet(member, ByteBuffer.allocate(256 * 1024), 250, 5_000);

            RoomContext room = roomManager.getRoom(roomId);
            assertThat(room).isNotNull();

            writeFully(member, MessageCodec.encodeMediaControl(
                    new MessageCodec.MediaControlPayload("PLAY", 1.0, "")));
            drainUntilQuiet(member, ByteBuffer.allocate(256 * 1024), 250, 1_000);

            assertThat(room.currentMediaState).isNull();
            ClientSession memberSession = findSession(roomId, "member-client");
            assertThat(memberSession).isNotNull();
            assertThat(RoomPermissions.canManageMedia(memberSession.permissions)).isFalse();
        }
    }

    @Test
    void mediaControl_stop_clearsStateAndBroadcastsStopToPeers() throws Exception {
        WalManager walManager = new WalManager(tempDir);
        roomManager = new RoomManager(walManager);
        startServer(roomManager, walManager);
        int port = serverPort();
        String roomId = "media-stop-room";
        String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;

        try (SocketChannel owner = SocketChannel.open();
             SocketChannel member = SocketChannel.open()) {
            owner.configureBlocking(true);
            member.configureBlocking(true);
            owner.connect(new InetSocketAddress(HOST, port));
            member.connect(new InetSocketAddress(HOST, port));

            writeFully(owner, MessageCodec.encodeHandshake("Owner", "owner-client"));
            writeFully(member, MessageCodec.encodeHandshake("Member", "member-client"));
            drainUntilQuiet(owner, ByteBuffer.allocate(256 * 1024), 250, 5_000);
            drainUntilQuiet(member, ByteBuffer.allocate(256 * 1024), 250, 5_000);

            writeFully(owner, MessageCodec.encodeJoinRoom(roomId, boardId));
            drainUntilQuiet(owner, ByteBuffer.allocate(256 * 1024), 250, 5_000);
            writeFully(member, MessageCodec.encodeJoinRoom(roomId, boardId));
            drainUntilQuiet(member, ByteBuffer.allocate(256 * 1024), 250, 5_000);

            writeFully(owner, MessageCodec.encodeMediaControl(
                    new MessageCodec.MediaControlPayload("LOAD", 0, "abc123")));
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                RoomContext room = roomManager.getRoom(roomId);
                assertThat(room).isNotNull();
                assertThat(room.currentMediaState).isNotNull();
            });

            writeFully(owner, MessageCodec.encodeMediaControl(
                    new MessageCodec.MediaControlPayload("STOP", 0, "")));
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                RoomContext room = roomManager.getRoom(roomId);
                assertThat(room).isNotNull();
                assertThat(room.currentMediaState).isNull();
            });

            List<Message> memberMessages = drainMessages(member, 2_000);
            assertThat(memberMessages).anyMatch(m -> m.type() == MessageType.MEDIA_STATE_UPDATE);
            MessageCodec.MediaStatePayload stopUpdate = memberMessages.stream()
                    .filter(m -> m.type() == MessageType.MEDIA_STATE_UPDATE)
                    .map(MessageCodec::decodeMediaState)
                    .filter(p -> "STOP".equals(p.state()))
                    .findFirst()
                    .orElseThrow();
            assertThat(stopUpdate.videoId()).isEmpty();
        }
    }

    @Test
    void remoteMailboxMediaStateUpdate_stopClearsRoomState() throws Exception {
        WalManager walManager = new WalManager(tempDir);
        roomManager = new RoomManager(walManager);
        String localNodeId = "node-local";
        server = new NioServer(0, roomManager, walManager, null, null, localNodeId);
        serverThread = new Thread(server, "nio-server-media-stop-remote");
        serverThread.setDaemon(true);
        serverThread.start();

        String roomId = "media-stop-remote-room";
        RoomContext room = roomManager.getOrCreateRoom(roomId);
        room.currentMediaState = new MessageCodec.MediaStatePayload(
                "PLAYING", 1.0, 1_000L, "vid");

        MessageCodec.MediaStatePayload stopPayload =
                new MessageCodec.MediaStatePayload("STOP", 0, 9_999L, "");
        ByteBuffer frame = MessageCodec.encodeMediaState(stopPayload);
        byte[] frameBytes = new byte[frame.remaining()];
        frame.duplicate().get(frameBytes);

        BackplaneEnvelope remoteEnvelope = new BackplaneEnvelope(
                "evt-media-stop",
                "node-remote",
                roomId,
                MessageCodec.DEFAULT_INITIAL_BOARD_ID,
                ByteBuffer.wrap(frameBytes));
        server.remoteMailbox.offer(remoteEnvelope);
        server.processRemoteMailbox();

        assertThat(room.currentMediaState).isNull();
    }

    @Test
    void remoteMailboxMediaStateUpdate_updatesRoomAndBroadcasts() throws Exception {
        WalManager walManager = new WalManager(tempDir);
        roomManager = new RoomManager(walManager);
        String localNodeId = "node-local";
        server = new NioServer(0, roomManager, walManager, null, null, localNodeId);
        serverThread = new Thread(server, "nio-server-media-remote");
        serverThread.setDaemon(true);
        serverThread.start();

        String roomId = "media-remote-room";
        RoomContext room = roomManager.getOrCreateRoom(roomId);
        assertThat(room.currentMediaState).isNull();

        MessageCodec.MediaStatePayload payload =
                new MessageCodec.MediaStatePayload("PAUSED", 42.0, 9_999L, "remote-vid");
        ByteBuffer frame = MessageCodec.encodeMediaState(payload);
        byte[] frameBytes = new byte[frame.remaining()];
        frame.duplicate().get(frameBytes);

        BackplaneEnvelope remoteEnvelope = new BackplaneEnvelope(
                "evt-media-remote",
                "node-remote",
                roomId,
                MessageCodec.DEFAULT_INITIAL_BOARD_ID,
                ByteBuffer.wrap(frameBytes));
        server.remoteMailbox.offer(remoteEnvelope);
        server.processRemoteMailbox();

        assertThat(room.currentMediaState).isNotNull();
        assertThat(room.currentMediaState.state()).isEqualTo("PAUSED");
        assertThat(room.currentMediaState.mediaTimeSeconds()).isEqualTo(42.0);
        assertThat(room.currentMediaState.serverEpochMs()).isEqualTo(9_999L);
        assertThat(room.currentMediaState.videoId()).isEqualTo("remote-vid");
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
        serverThread = new Thread(server, "nio-server-media-test");
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
