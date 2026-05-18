package com.distrisync.server;

import com.distrisync.model.Line;
import com.distrisync.protocol.Message;
import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.MessageType;
import com.distrisync.server.backplane.BackplaneEnvelope;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class NioServerRemoteMailboxTest {

    @Test
    void testMailboxProtectsSelectorThread() throws Exception {
        String localNodeId = "local-node";
        RoomManager roomManager = new RoomManager();
        NioServer server = new NioServer(0, roomManager, null, null, null, localNodeId);

        RoomContext room = roomManager.getOrCreateRoom("room-1");
        ClientSession session = new ClientSession();
        session.currentBoardId = "board-1";
        session.roomId = "room-1";

        Line shape = Line.create("#E63946", 1.0, 2.0, 100.0, 200.0, 2.0, "Alice", "client-a");
        Message mutation = new Message(MessageType.MUTATION, ShapeCodec.encodeMutation(shape));
        ByteBuffer encoded = MessageCodec.encode(mutation);
        byte[] frameBytes = new byte[encoded.remaining()];
        encoded.duplicate().get(frameBytes);
        int frameLength = frameBytes.length;

        Selector selector = Selector.open();
        ServerSocketChannel acceptor = ServerSocketChannel.open();
        SocketChannel client = SocketChannel.open();
        try {
            acceptor.bind(new InetSocketAddress("127.0.0.1", 0));
            acceptor.configureBlocking(false);
            int port = acceptor.socket().getLocalPort();
            acceptor.register(selector, SelectionKey.OP_ACCEPT);

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

            ByteBuffer payload = ByteBuffer.wrap(frameBytes);
            BackplaneEnvelope remoteEnvelope = new BackplaneEnvelope(
                    "event-remote",
                    "remote-node",
                    "room-1",
                    "board-1",
                    payload);

            AtomicReference<Throwable> producerError = new AtomicReference<>();
            CountDownLatch offered = new CountDownLatch(1);
            Thread producer = new Thread(() -> {
                try {
                    server.remoteMailbox.offer(remoteEnvelope);
                    offered.countDown();
                } catch (Throwable t) {
                    producerError.set(t);
                }
            }, "mailbox-producer");
            producer.start();

            assertThat(offered.await(2, TimeUnit.SECONDS)).isTrue();
            producer.join(2_000);
            assertThat(producerError.get()).isNull();

            long routedBefore = server.getBytesRouted();
            server.processRemoteMailbox();
            assertThat(server.getBytesRouted() - routedBefore).isEqualTo(frameLength);

            ByteBuffer received = ByteBuffer.allocate(frameLength);
            while (received.hasRemaining()) {
                int read = client.read(received);
                assertThat(read).isGreaterThan(0);
            }
            received.flip();
            assertThat(received).isEqualByComparingTo(ByteBuffer.wrap(frameBytes));

            BackplaneEnvelope localEnvelope = new BackplaneEnvelope(
                    "event-local",
                    localNodeId,
                    "room-1",
                    "board-1",
                    ByteBuffer.wrap(frameBytes));
            long routedAfterRemote = server.getBytesRouted();
            server.remoteMailbox.offer(localEnvelope);
            server.processRemoteMailbox();
            assertThat(server.getBytesRouted()).isEqualTo(routedAfterRemote);
        } finally {
            client.close();
            selector.close();
            acceptor.close();
        }
    }
}
