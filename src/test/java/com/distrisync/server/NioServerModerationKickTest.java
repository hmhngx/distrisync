package com.distrisync.server;

import com.distrisync.protocol.Message;
import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.MessageType;
import com.distrisync.protocol.PartialMessageException;
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

class NioServerModerationKickTest {

    @Test
    void remoteMailboxKick_seversLocalTarget() throws Exception {
        String localNodeId = "local-node";
        RoomManager roomManager = new RoomManager();
        NioServer server = new NioServer(0, roomManager, null, null, null, localNodeId);

        RoomContext room = roomManager.getOrCreateRoom("room-1");
        ClientSession victim = new ClientSession();
        victim.clientId = "victim-client";
        victim.roomId = "room-1";
        victim.currentBoardId = "board-1";
        victim.handshakeComplete = true;

        Selector selector = Selector.open();
        ServerSocketChannel acceptor = ServerSocketChannel.open();
        SocketChannel client = SocketChannel.open();
        try {
            registerClient(acceptor, selector, client, victim, room);

            processRemoteKick(server, "victim-client");

            assertThat(room.lookupClientKey("victim-client")).isNull();
            assertThat(room.getActiveClientCount()).isZero();

            List<Message> received = readAllMessages(client, 2_000);
            assertThat(received).anyMatch(m -> m.type() == MessageType.SESSION_REVOKED);
            Message revoked = received.stream()
                    .filter(m -> m.type() == MessageType.SESSION_REVOKED)
                    .findFirst()
                    .orElseThrow();
            assertThat(MessageCodec.decodeSessionRevoked(revoked)).isEqualTo("policy violation");
        } finally {
            client.close();
            selector.close();
            acceptor.close();
        }
    }

    @Test
    void remoteMailboxKick_broadcastsLeaveRoomToObserver() throws Exception {
        String localNodeId = "local-node";
        RoomManager roomManager = new RoomManager();
        NioServer server = new NioServer(0, roomManager, null, null, null, localNodeId);

        RoomContext room = roomManager.getOrCreateRoom("room-1");
        ClientSession victim = sessionFor("victim-client");
        ClientSession observer = sessionFor("admin-observer");

        Selector selector = Selector.open();
        ServerSocketChannel acceptor = ServerSocketChannel.open();
        SocketChannel victimSocket = SocketChannel.open();
        SocketChannel observerSocket = SocketChannel.open();
        try {
            registerClient(acceptor, selector, victimSocket, victim, room);
            registerClient(acceptor, selector, observerSocket, observer, room);

            processRemoteKick(server, "victim-client");

            assertThat(room.lookupClientKey("victim-client")).isNull();
            assertThat(room.lookupClientKey("admin-observer")).isNotNull();

            List<Message> observerMessages = readAllMessages(observerSocket, 2_000);
            assertThat(observerMessages).anyMatch(m -> m.type() == MessageType.LEAVE_ROOM);
            String departedId = observerMessages.stream()
                    .filter(m -> m.type() == MessageType.LEAVE_ROOM)
                    .map(MessageCodec::decodeRoomMemberLeft)
                    .filter("victim-client"::equals)
                    .findFirst()
                    .orElseThrow();
            assertThat(departedId).isEqualTo("victim-client");
        } finally {
            victimSocket.close();
            observerSocket.close();
            selector.close();
            acceptor.close();
        }
    }

    @Test
    void remoteMailboxKick_broadcastsLeaveRoomWhenTargetNotLocal() throws Exception {
        String localNodeId = "local-node";
        RoomManager roomManager = new RoomManager();
        NioServer server = new NioServer(0, roomManager, null, null, null, localNodeId);

        RoomContext room = roomManager.getOrCreateRoom("room-1");
        ClientSession observer = sessionFor("admin-observer");

        Selector selector = Selector.open();
        ServerSocketChannel acceptor = ServerSocketChannel.open();
        SocketChannel observerSocket = SocketChannel.open();
        try {
            registerClient(acceptor, selector, observerSocket, observer, room);

            processRemoteKick(server, "remote-victim");

            assertThat(room.lookupClientKey("remote-victim")).isNull();
            assertThat(room.getActiveClientCount()).isEqualTo(1);

            List<Message> observerMessages = readAllMessages(observerSocket, 2_000);
            assertThat(observerMessages).anyMatch(m -> m.type() == MessageType.LEAVE_ROOM);
            String departedId = observerMessages.stream()
                    .filter(m -> m.type() == MessageType.LEAVE_ROOM)
                    .map(MessageCodec::decodeRoomMemberLeft)
                    .filter("remote-victim"::equals)
                    .findFirst()
                    .orElseThrow();
            assertThat(departedId).isEqualTo("remote-victim");
        } finally {
            observerSocket.close();
            selector.close();
            acceptor.close();
        }
    }

    private static ClientSession sessionFor(String clientId) {
        ClientSession session = new ClientSession();
        session.clientId = clientId;
        session.roomId = "room-1";
        session.currentBoardId = "board-1";
        session.handshakeComplete = true;
        return session;
    }

    private static SelectionKey registerClient(
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
        return key;
    }

    private static void processRemoteKick(NioServer server, String targetClientId) {
        Message kick = new Message(
                MessageType.MODERATION_ACTION,
                MessageCodec.gson().toJson(new MessageCodec.ModerationActionPayload(
                        "KICK", targetClientId, "policy violation")));
        ByteBuffer encoded = MessageCodec.encode(kick);
        byte[] frameBytes = new byte[encoded.remaining()];
        encoded.duplicate().get(frameBytes);

        BackplaneEnvelope remoteEnvelope = new BackplaneEnvelope(
                "event-kick-" + targetClientId,
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
