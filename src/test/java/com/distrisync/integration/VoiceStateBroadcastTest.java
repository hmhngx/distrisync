package com.distrisync.integration;

import com.distrisync.client.CanvasUpdateListener;
import com.distrisync.client.NetworkClient;
import com.distrisync.client.VoiceStateListener;
import com.distrisync.model.Shape;
import com.distrisync.protocol.MessageCodec;
import com.distrisync.server.NioServer;
import com.distrisync.server.RoomManager;
import javafx.application.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * {@link com.distrisync.protocol.MessageType#VOICE_STATE} is relayed room-wide over TCP.
 */
@ExtendWith(MockitoExtension.class)
class VoiceStateBroadcastTest {

    private static final String HOST = "127.0.0.1";
    private static final int SETUP_TIMEOUT_S = 5;
    private static final int BCAST_TIMEOUT_S = 5;
    private static final long POLL_MS = 50;

    private RoomManager roomManager;
    private NioServer server;
    private Thread serverThread;
    private int serverPort;

    private NetworkClient clientA;
    private NetworkClient clientB;
    private VoiceStateListener listenerB;
    private final AtomicBoolean clientASnapshot = new AtomicBoolean(false);
    private final AtomicBoolean clientBSnapshot = new AtomicBoolean(false);

    @BeforeAll
    static void initJavaFxToolkit() {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException ignored) {
            // Toolkit already started by another test class in the same JVM.
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        roomManager = new RoomManager();
        server = new NioServer(0, roomManager);
        serverThread = new Thread(server, "voice-state-test-server");
        serverThread.setDaemon(true);
        serverThread.start();
        serverPort = server.getBoundPortFuture().get(SETUP_TIMEOUT_S, TimeUnit.SECONDS);

        listenerB = mock(VoiceStateListener.class);

        clientA = new NetworkClient(HOST, serverPort, "client-a");
        clientB = new NetworkClient(HOST, serverPort, "client-b");
        clientB.addVoiceStateListener(listenerB);

        clientA.addListener(new SnapshotGate(clientASnapshot));
        clientB.addListener(new SnapshotGate(clientBSnapshot));

        clientA.connect();
        clientB.connect();
        clientA.sendJoinRoom("Global", "UserA");
        clientB.sendJoinRoom("Global", "UserB");

        await("both clients receive initial SNAPSHOT")
                .atMost(SETUP_TIMEOUT_S, TimeUnit.SECONDS)
                .pollInterval(POLL_MS, TimeUnit.MILLISECONDS)
                .until(() -> clientASnapshot.get() && clientBSnapshot.get());
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (clientA != null) {
            try {
                clientA.close();
            } catch (Exception ignored) {
            }
        }
        if (clientB != null) {
            try {
                clientB.close();
            } catch (Exception ignored) {
            }
        }
        if (server != null) {
            server.stop();
        }
        if (serverThread != null) {
            serverThread.interrupt();
            serverThread.join(10_000);
        }
        server = null;
        serverThread = null;
        roomManager = null;
    }

    @Test
    void testVoiceStateBroadcast() {
        // Late-join hydration sends default micMuted=true; use false after reset so only
        // the live relay matches.
        reset(listenerB);
        clientA.sendVoiceState(false);

        await("Client B receives VOICE_STATE from Client A")
                .atMost(BCAST_TIMEOUT_S, TimeUnit.SECONDS)
                .pollInterval(POLL_MS, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        verify(listenerB).onVoiceState(eq("client-a"), eq(false)));
    }

    private static final class SnapshotGate implements CanvasUpdateListener {
        private final AtomicBoolean snapshotReceived;

        SnapshotGate(AtomicBoolean snapshotReceived) {
            this.snapshotReceived = snapshotReceived;
        }

        @Override
        public void onSnapshotReceived(List<Shape> shapes) {
            snapshotReceived.set(true);
        }

        @Override
        public void onMutationReceived(Shape shape) {
            // not used
        }
    }
}
