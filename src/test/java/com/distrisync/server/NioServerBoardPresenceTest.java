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
import static org.awaitility.Awaitility.await;

class NioServerBoardPresenceTest {

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
    }

    @Test
    void switchBoard_broadcastsBoardSwitchToPeer() throws Exception {
        WalManager walManager = new WalManager(tempDir);
        RoomManager roomManager = new RoomManager(walManager);
        startServer(roomManager, walManager);
        int port = serverPort();
        String roomId = "presence-room";
        String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;

        try (SocketChannel clientA = SocketChannel.open();
             SocketChannel clientB = SocketChannel.open()) {
            clientA.configureBlocking(true);
            clientB.configureBlocking(true);
            clientA.connect(new InetSocketAddress(HOST, port));
            clientB.connect(new InetSocketAddress(HOST, port));

            writeFully(clientA, MessageCodec.encodeHandshake("client-a"));
            writeFully(clientB, MessageCodec.encodeHandshake("client-b"));
            drainUntilQuiet(clientA, ByteBuffer.allocate(256 * 1024), 250, 5_000);
            drainUntilQuiet(clientB, ByteBuffer.allocate(256 * 1024), 250, 5_000);

            writeFully(clientA, MessageCodec.encodeJoinRoom(roomId, "UserA", boardId));
            drainUntilQuiet(clientA, ByteBuffer.allocate(256 * 1024), 250, 5_000);
            writeFully(clientB, MessageCodec.encodeJoinRoom(roomId, "UserB", boardId));
            drainUntilQuiet(clientB, ByteBuffer.allocate(256 * 1024), 250, 5_000);

            ByteBuffer accB = ByteBuffer.allocate(256 * 1024);
            accB.clear();

            writeFully(clientA, MessageCodec.encodeSwitchBoard("Diagrams"));

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                clientB.configureBlocking(false);
                clientB.read(accB);
                clientB.configureBlocking(true);
                assertThat(collectBoardSwitchFor(accB, "client-a"))
                        .anyMatch(p -> "Diagrams".equals(p.newBoardId()));
            });
        }
    }

    private static List<MessageCodec.BoardSwitchPayload> collectBoardSwitchFor(ByteBuffer acc, String clientId) {
        List<MessageCodec.BoardSwitchPayload> found = new ArrayList<>();
        for (Message m : decodeAvailable(acc)) {
            if (m.type() == MessageType.BOARD_SWITCH) {
                MessageCodec.BoardSwitchPayload p = MessageCodec.decodeBoardSwitch(m);
                if (clientId.equals(p.clientId())) {
                    found.add(p);
                }
            }
        }
        return found;
    }

    private void startServer(RoomManager roomManager, WalManager walManager) {
        server = new NioServer(0, roomManager, walManager);
        serverThread = new Thread(server, "nio-server-board-presence-test");
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
