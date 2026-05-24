package com.distrisync.server;

import com.distrisync.protocol.MessageCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ServerMetricsFlushTest {

    private static final String HOST = "127.0.0.1";

    private NioServer server;
    private Thread serverThread;

    @BeforeEach
    void resetCounters() {
        ServerMetrics.FRAMES_SENT_TOTAL.set(0);
    }

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
    void flushWriteQueue_incrementsFramesSentTotal() throws Exception {
        RoomManager roomManager = new RoomManager();
        server = new NioServer(0, roomManager, null);
        serverThread = new Thread(server, "nio-server-metrics-flush-test");
        serverThread.setDaemon(true);
        serverThread.start();
        int serverPort = server.getBoundPortFuture().get(5, TimeUnit.SECONDS);

        try (SocketChannel channel = SocketChannel.open()) {
            channel.configureBlocking(true);
            channel.connect(new InetSocketAddress(HOST, serverPort));

            ByteBuffer frame = MessageCodec.encodeHandshake("metrics-client");
            while (frame.hasRemaining()) {
                channel.write(frame);
            }

            Thread.sleep(200);
        }

        assertThat(ServerMetrics.FRAMES_SENT_TOTAL.get()).isGreaterThan(0);
    }
}
