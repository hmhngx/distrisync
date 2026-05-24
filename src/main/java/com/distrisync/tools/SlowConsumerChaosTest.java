package com.distrisync.tools;

import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.MessageType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;

/**
 * Chaos test that proves {@link com.distrisync.server.ClientSession} load shedding:
 * join a hot room, freeze inbound reads so TCP and the server {@code writeQueue}
 * (capacity 1024) back up, then observe {@code OVERFLOW_DISCONNECT}.
 *
 * <p>Prerequisites: {@code WhiteboardServer} plus {@link BotSwarm} spamming the same
 * {@code roomId} on the target host/port <em>before</em> this tool connects.
 *
 * <p>Usage:
 * <pre>
 *   mvn -q compile exec:java -Dexec.mainClass=com.distrisync.tools.SlowConsumerChaosTest \
 *       -Dexec.args="127.0.0.1 9090 load-test"
 * </pre>
 *
 * <p>Arguments: {@code serverIp port roomId [readFreezeMs]}
 */
public final class SlowConsumerChaosTest {

    private static final int DEFAULT_READ_FREEZE_MS = 60_000;
    /** Small recv window so OS TCP backlog + server writeQueue fill faster under BotSwarm. */
    private static final int CHAOS_RCVBUF_BYTES = 4_096;
    private static final int DRAIN_POLL_TIMEOUT_MS = 1_000;
    private static final int MAX_DRAIN_WAIT_MS = 30_000;

    private SlowConsumerChaosTest() {}

    public static void main(String[] args) {
        if (args.length < 3 || args.length > 4) {
            System.err.println("Usage: SlowConsumerChaosTest <serverIp> <port> <roomId> [readFreezeMs]");
            System.exit(1);
        }

        String serverIp = args[0];
        int port = parsePositiveInt(args[1], "port");
        String roomId = args[2].strip();
        int readFreezeMs = args.length == 4
                ? parsePositiveInt(args[3], "readFreezeMs")
                : DEFAULT_READ_FREEZE_MS;

        if (roomId.isBlank()) {
            System.err.println("roomId must not be blank");
            System.exit(1);
        }

        String clientId = "chaos-slow-consumer-" + System.nanoTime();
        System.out.printf(
                "SlowConsumerChaosTest -> %s:%d room='%s' freeze=%dms%n",
                serverIp, port, roomId, readFreezeMs);
        System.out.println("Ensure BotSwarm is already running on this room (e.g. 30+ bots).");

        try (Socket socket = new Socket(serverIp, port)) {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setReceiveBufferSize(CHAOS_RCVBUF_BYTES);

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            writeFrame(out, MessageCodec.encodeHandshake(clientId));
            writeFrame(out, MessageCodec.encodeJoinRoom(roomId, "chaos-slow-consumer"));
            System.out.printf(
                    "HANDSHAKE + JOIN_ROOM sent (SO_RCVBUF=%d) — starting read-freeze thread.%n",
                    socket.getReceiveBufferSize());

            boolean disconnected = runChaosReadThread(socket, in, readFreezeMs);
            if (!disconnected) {
                System.err.println();
                System.err.println("FAIL: Server did not disconnect this slow consumer.");
                System.err.println("  1. Start BotSwarm FIRST in another terminal, same roomId.");
                System.err.println("  2. Use enough bots (try 30–50):");
                System.err.println("     mvn -q exec:java \"-Dexec.mainClass=com.distrisync.tools.BotSwarm\"");
                System.err.println("       \"-Dexec.args=127.0.0.1 9090 50 load-test\"");
                System.err.println("  3. Then re-run this chaos test.");
                System.exit(1);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted.");
            System.exit(1);
        } catch (IOException e) {
            if (isPeerDisconnect(e)) {
                printSuccess();
                return;
            }
            System.err.printf("FAIL: %s%n", e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Dedicated read thread: no {@link InputStream} reads for {@code readFreezeMs}, then
     * drains until the server closes the socket ({@code OVERFLOW_DISCONNECT}).
     */
    private static boolean runChaosReadThread(Socket socket, InputStream in, int readFreezeMs)
            throws InterruptedException {
        boolean[] disconnected = {false};
        Thread readThread = Thread.startVirtualThread(() -> {
            try {
                System.out.printf(
                        "Chaos: read thread frozen for %d ms — zero InputStream.read() calls.%n",
                        readFreezeMs);
                Thread.sleep(readFreezeMs);

                System.out.println("Chaos: freeze complete — draining until server disconnects...");
                disconnected[0] = drainUntilDisconnect(socket, in);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                if (isPeerDisconnect(e)) {
                    disconnected[0] = true;
                    printSuccess();
                } else {
                    System.err.printf("FAIL: read thread I/O error: %s%n", e.getMessage());
                }
            }
        });

        readThread.join();
        return disconnected[0];
    }

    /**
     * After the freeze, reads and discards backlog until EOF or reset. Success means
     * the server already closed the connection during the freeze window.
     */
    private static boolean drainUntilDisconnect(Socket socket, InputStream in) throws IOException {
        socket.setSoTimeout(DRAIN_POLL_TIMEOUT_MS);
        byte[] scratch = new byte[16 * 1024];
        long deadline = System.currentTimeMillis() + MAX_DRAIN_WAIT_MS;
        boolean sawSnapshot = false;

        while (System.currentTimeMillis() < deadline) {
            try {
                int n = in.read(scratch);
                if (n == -1) {
                    printSuccess();
                    return true;
                }
                if (n > 0 && (scratch[0] & 0xFF) == MessageType.SNAPSHOT.wireCode()) {
                    sawSnapshot = true;
                }
            } catch (SocketTimeoutException e) {
                // No data right now — server may still be backpressured or already closed.
            } catch (SocketException e) {
                printSuccess();
                return true;
            }
        }

        if (sawSnapshot) {
            System.err.println(
                    "Hint: saw SNAPSHOT (0x02) but never disconnected — BotSwarm likely not "
                            + "flooding this room during the freeze.");
        } else {
            System.err.println("Hint: no disconnect within " + MAX_DRAIN_WAIT_MS + " ms after freeze.");
        }
        return false;
    }

    private static void printSuccess() {
        System.out.println();
        System.out.println("=".repeat(72));
        System.out.println("SUCCESS: Server successfully detected slow consumer and terminated connection.");
        System.out.println("=".repeat(72));
        System.out.println();
    }

    private static boolean isPeerDisconnect(IOException e) {
        if (e instanceof SocketException) {
            return true;
        }
        String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        String lower = msg.toLowerCase();
        return lower.contains("connection reset")
                || lower.contains("connection aborted")
                || lower.contains("forcibly closed")
                || lower.contains("broken pipe")
                || lower.contains("closed by peer")
                || lower.contains("end of stream");
    }

    private static void writeFrame(OutputStream out, ByteBuffer frame) throws IOException {
        ByteBuffer buf = frame.duplicate();
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        out.write(bytes);
        out.flush();
    }

    private static int parsePositiveInt(String raw, String label) {
        try {
            int value = Integer.parseInt(raw);
            if (value <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return value;
        } catch (NumberFormatException e) {
            System.err.printf("Invalid %s: '%s'%n", label, raw);
            System.exit(1);
            return -1;
        }
    }
}
