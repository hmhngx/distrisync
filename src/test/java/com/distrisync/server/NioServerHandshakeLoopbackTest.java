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
 * Verifies HANDSHAKE unicasts a self {@link MessageType#JOIN_ROOM} loopback before lobby fan-out.
 * Display identity is empty until {@code JOIN_ROOM}.
 */
class NioServerHandshakeLoopbackTest {

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
    void handshake_unicastsSelfJoinRoomWithEmptyDisplayName() throws Exception {
        WalManager walManager = new WalManager(tempDir);
        RoomManager roomManager = new RoomManager(walManager);
        startServer(roomManager, walManager);
        int port = serverPort();

        try (SocketChannel channel = SocketChannel.open()) {
            channel.configureBlocking(true);
            channel.connect(new InetSocketAddress(HOST, port));
            writeFully(channel, MessageCodec.encodeHandshake("client-a"));

            List<Message> afterHandshake = drainMessages(channel, 5_000);

            MessageCodec.RoomMemberJoinedPayload selfJoin = afterHandshake.stream()
                    .filter(m -> m.type() == MessageType.JOIN_ROOM)
                    .map(MessageCodec::decodeRoomMemberJoined)
                    .filter(p -> "client-a".equals(p.clientId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("expected self JOIN_ROOM after HANDSHAKE"));
            assertThat(selfJoin.authorName()).isEmpty();
        }
    }

    private void startServer(RoomManager roomManager, WalManager walManager) {
        server = new NioServer(0, roomManager, walManager);
        serverThread = new Thread(server, "nio-server-handshake-loopback-test");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private int serverPort() throws Exception {
        return server.getBoundPortFuture().get(5, TimeUnit.SECONDS);
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
