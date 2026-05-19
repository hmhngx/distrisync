package com.distrisync.server;

import com.distrisync.protocol.Message;
import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.MessageType;
import com.distrisync.protocol.PartialMessageException;
import com.distrisync.protocol.RoomPermissions;
import com.distrisync.server.backplane.BackplaneEnvelope;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class NioServerModerationGrantSpeakTest {

    @Test
    void remoteMailboxGrantSpeak_unicastsRoleUpdateAndSetsSpeakBit() throws Exception {
        String localNodeId = "local-node";
        RoomManager roomManager = new RoomManager();
        NioServer server = new NioServer(0, roomManager, null, null, null, localNodeId);

        RoomContext room = roomManager.getOrCreateRoom("room-1");
        ClientSession target = sessionFor("member-client", RoomPermissions.PERM_DRAW);

        Selector selector = Selector.open();
        ServerSocketChannel acceptor = ServerSocketChannel.open();
        SocketChannel client = SocketChannel.open();
        try {
            registerClient(acceptor, selector, client, target, room);

            processRemoteGrantSpeak(server, "member-client");

            assertThat(target.permissions).isEqualTo(RoomPermissions.MEMBER);
            assertThat(RoomPermissions.canSpeak(target.permissions)).isTrue();
            assertThat(room.lookupClientKey("member-client")).isNotNull();

            List<Message> received = readAllMessages(client, 2_000);
            assertThat(received).anyMatch(m -> m.type() == MessageType.ROLE_UPDATE);
            Message roleUpdate = received.stream()
                    .filter(m -> m.type() == MessageType.ROLE_UPDATE)
                    .findFirst()
                    .orElseThrow();
            MessageCodec.RoleUpdatePayload payload = MessageCodec.decodeRoleUpdate(roleUpdate);
            assertThat(payload.newHostClientId()).isEqualTo("member-client");
            assertThat(payload.newPermissions()).isEqualTo(RoomPermissions.MEMBER);
            assertThat(RoomPermissions.canSpeak(payload.newPermissions())).isTrue();
        } finally {
            client.close();
            selector.close();
            acceptor.close();
        }
    }

    @Test
    void remoteMailboxGrantSpeak_broadcastsRoleUpdateWhenTargetNotLocal() throws Exception {
        String localNodeId = "local-node";
        RoomManager roomManager = new RoomManager();
        NioServer server = new NioServer(0, roomManager, null, null, null, localNodeId);

        RoomContext room = roomManager.getOrCreateRoom("room-1");
        ClientSession observer = sessionFor("admin-observer", RoomPermissions.OWNER);

        Selector selector = Selector.open();
        ServerSocketChannel acceptor = ServerSocketChannel.open();
        SocketChannel observerSocket = SocketChannel.open();
        try {
            registerClient(acceptor, selector, observerSocket, observer, room);

            processRemoteGrantSpeak(server, "remote-member");

            assertThat(room.lookupClientKey("remote-member")).isNull();

            List<Message> received = readAllMessages(observerSocket, 2_000);
            assertThat(received).anyMatch(m -> m.type() == MessageType.ROLE_UPDATE);
            Message roleUpdate = received.stream()
                    .filter(m -> m.type() == MessageType.ROLE_UPDATE)
                    .findFirst()
                    .orElseThrow();
            MessageCodec.RoleUpdatePayload payload = MessageCodec.decodeRoleUpdate(roleUpdate);
            assertThat(payload.newHostClientId()).isEqualTo("remote-member");
            assertThat(payload.newPermissions()).isEqualTo(RoomPermissions.MEMBER);
            assertThat(RoomPermissions.canSpeak(payload.newPermissions())).isTrue();
        } finally {
            observerSocket.close();
            selector.close();
            acceptor.close();
        }
    }

    private static ClientSession sessionFor(String clientId, int permissions) {
        ClientSession session = new ClientSession();
        session.clientId = clientId;
        session.roomId = "room-1";
        session.currentBoardId = "board-1";
        session.handshakeComplete = true;
        session.permissions = permissions;
        return session;
    }

    private static void registerClient(
            ServerSocketChannel acceptor,
            Selector selector,
            SocketChannel client,
            ClientSession session,
            RoomContext room) throws Exception {
        if (acceptor.socket().getLocalPort() <= 0) {
            acceptor.bind(new InetSocketAddress("127.0.0.1", 0));
            acceptor.configureBlocking(false);
            acceptor.register(selector, SelectionKey.OP_ACCEPT);
        }
        int port = acceptor.socket().getLocalPort();
        assertThat(port).isPositive();

        client.configureBlocking(false);
        client.connect(new InetSocketAddress("127.0.0.1", port));
        while (!client.finishConnect()) {
            selector.selectNow();
        }
        selector.selectNow();
        SocketChannel serverChannel = acceptor.accept();
        assertThat(serverChannel).isNotNull();
        serverChannel.configureBlocking(false);
        SelectionKey key = serverChannel.register(selector, SelectionKey.OP_READ);
        key.attach(session);
        room.addKey(key);
    }

    private static void processRemoteGrantSpeak(NioServer server, String targetClientId) {
        Message grant = new Message(
                MessageType.MODERATION_ACTION,
                MessageCodec.gson().toJson(new MessageCodec.ModerationActionPayload(
                        "GRANT_SPEAK", targetClientId, "unmuted by moderator")));
        ByteBuffer encoded = MessageCodec.encode(grant);
        byte[] frameBytes = new byte[encoded.remaining()];
        encoded.duplicate().get(frameBytes);

        BackplaneEnvelope remoteEnvelope = new BackplaneEnvelope(
                "event-grant-" + targetClientId,
                "remote-node",
                "room-1",
                MessageCodec.DEFAULT_INITIAL_BOARD_ID,
                ByteBuffer.wrap(frameBytes));

        server.remoteMailbox.offer(remoteEnvelope);
        server.processRemoteMailbox();
    }

    private static List<Message> readAllMessages(SocketChannel channel, long maxWaitMs) throws Exception {
        ByteBuffer acc = ByteBuffer.allocate(8 * 1024);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMs);
        List<Message> out = new ArrayList<>();
        channel.configureBlocking(false);
        while (System.nanoTime() < deadline) {
            int n = channel.read(acc);
            if (n > 0) {
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
            }
            if (n < 0 && !out.isEmpty()) {
                break;
            }
            if (n < 0) {
                break;
            }
            Thread.sleep(10);
        }
        channel.configureBlocking(true);
        return out;
    }
}
