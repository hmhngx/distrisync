package com.distrisync.tools;

import com.distrisync.model.Line;
import com.distrisync.protocol.Message;
import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.MessageType;
import com.distrisync.protocol.PartialMessageException;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Headless TCP load generator for horizontally scaled {@code NioServer} instances.
 *
 * <p>Usage:
 * <pre>
 *   mvn -q compile exec:java -Dexec.mainClass=com.distrisync.tools.BotSwarm \
 *       -Dexec.args="127.0.0.1 9000 50 my-room"
 * </pre>
 *
 * <p>Arguments: {@code serverIp port botCount roomId}
 */
public final class BotSwarm {

    private static final int FIREHOSE_INTERVAL_MS = 50;
    private static final int STATS_INTERVAL_MS = 2_000;
    private static final int READ_BUFFER_BYTES = 256 * 1024;
    private static final String TYPE_FIELD = "_type";

    private BotSwarm() {}

    public static void main(String[] args) throws InterruptedException {
        if (args.length != 4) {
            System.err.println("Usage: BotSwarm <serverIp> <port> <botCount> <roomId>");
            System.exit(1);
        }

        String serverIp = args[0];
        int port = parsePositiveInt(args[1], "port");
        int botCount = parsePositiveInt(args[2], "botCount");
        String roomId = args[3].strip();
        if (roomId.isBlank()) {
            System.err.println("roomId must not be blank");
            System.exit(1);
        }

        SwarmMetrics metrics = new SwarmMetrics();
        System.out.printf(
                "BotSwarm starting: target=%s:%d bots=%d room='%s'%n",
                serverIp, port, botCount, roomId);

        Thread.startVirtualThread(() -> statsLoop(metrics));

        for (int i = 0; i < botCount; i++) {
            int botIndex = i;
            Thread.startVirtualThread(() -> runBot(botIndex, serverIp, port, roomId, metrics));
        }

        Thread.currentThread().join();
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

    private static void statsLoop(SwarmMetrics metrics) {
        long prevMessagesWritten = 0;
        long prevBytesWritten = 0;
        long prevMessagesRead = 0;
        long prevBytesRead = 0;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(STATS_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            long messagesWritten = metrics.messagesWritten.get();
            long bytesWritten = metrics.bytesWritten.get();
            long messagesRead = metrics.messagesRead.get();
            long bytesRead = metrics.bytesRead.get();
            int activeBots = metrics.activeBots.get();

            double windowSec = STATS_INTERVAL_MS / 1000.0;
            long deltaMsgOut = messagesWritten - prevMessagesWritten;
            long deltaBytesOut = bytesWritten - prevBytesWritten;
            long deltaMsgIn = messagesRead - prevMessagesRead;
            long deltaBytesIn = bytesRead - prevBytesRead;

            System.out.printf(
                    "[%ds] bots=%d  OUT: %,.0f msg/s, %,.0f B/s  |  IN: %,.0f msg/s, %,.0f B/s  "
                            + "(totals out=%d msg / %d B, in=%d msg / %d B)%n",
                    STATS_INTERVAL_MS / 1000,
                    activeBots,
                    deltaMsgOut / windowSec,
                    deltaBytesOut / windowSec,
                    deltaMsgIn / windowSec,
                    deltaBytesIn / windowSec,
                    messagesWritten,
                    bytesWritten,
                    messagesRead,
                    bytesRead);

            prevMessagesWritten = messagesWritten;
            prevBytesWritten = bytesWritten;
            prevMessagesRead = messagesRead;
            prevBytesRead = bytesRead;
        }
    }

    private static void runBot(int botIndex, String host, int port, String roomId, SwarmMetrics metrics) {
        String authorName = "bot-" + botIndex;
        String clientId = "bot-swarm-" + botIndex + "-" + System.nanoTime();

        try (SocketChannel channel = openChannel(host, port)) {
            metrics.activeBots.incrementAndGet();

            writeFrame(channel, MessageCodec.encodeHandshake(authorName, clientId), metrics);
            writeFrame(channel, MessageCodec.encodeJoinRoom(roomId), metrics);

            AtomicBoolean running = new AtomicBoolean(true);

            Thread readThread = Thread.startVirtualThread(
                    () -> readLoop(channel, metrics, running, botIndex));

            try {
                firehoseLoop(channel, metrics, running, authorName, clientId);
            } finally {
                running.set(false);
                channel.close();
                readThread.join();
            }
        } catch (IOException | InterruptedException e) {
            if (!(e instanceof InterruptedException)) {
                System.err.printf("Bot %d disconnected: %s%n", botIndex, e.getMessage());
            } else {
                Thread.currentThread().interrupt();
            }
        } finally {
            metrics.activeBots.decrementAndGet();
        }
    }

    private static SocketChannel openChannel(String host, int port) throws IOException {
        SocketChannel channel = SocketChannel.open();
        channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
        channel.setOption(StandardSocketOptions.SO_SNDBUF, 64 * 1024);
        channel.setOption(StandardSocketOptions.SO_RCVBUF, 64 * 1024);
        channel.configureBlocking(true);
        channel.connect(new InetSocketAddress(host, port));
        return channel;
    }

    private static void firehoseLoop(
            SocketChannel channel,
            SwarmMetrics metrics,
            AtomicBoolean running,
            String authorName,
            String clientId) throws IOException, InterruptedException {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        while (running.get()) {
            Line line = randomLine(rng, authorName, clientId);
            writeFrame(channel, encodeLineMutation(line), metrics);
            Thread.sleep(FIREHOSE_INTERVAL_MS);
        }
    }

    private static void readLoop(
            SocketChannel channel,
            SwarmMetrics metrics,
            AtomicBoolean running,
            int botIndex) {
        ByteBuffer accumulator = ByteBuffer.allocate(READ_BUFFER_BYTES);
        ByteBuffer readScratch = ByteBuffer.allocate(16 * 1024);

        try {
            while (running.get()) {
                readScratch.clear();
                int n = channel.read(readScratch);
                if (n < 0) {
                    break;
                }
                if (n == 0) {
                    continue;
                }

                readScratch.flip();
                if (accumulator.remaining() < readScratch.remaining()) {
                    accumulator.compact();
                    if (accumulator.remaining() < readScratch.remaining()) {
                        throw new IOException("read accumulator overflow bot=" + botIndex);
                    }
                }
                accumulator.put(readScratch);
                accumulator.flip();
                drainInboundFrames(accumulator, metrics);
                accumulator.compact();
            }
        } catch (IOException e) {
            if (running.get()) {
                System.err.printf("Bot %d read error: %s%n", botIndex, e.getMessage());
            }
            running.set(false);
        }
    }

    private static void drainInboundFrames(ByteBuffer buffer, SwarmMetrics metrics) {
        while (buffer.hasRemaining()) {
            int frameStart = buffer.position();
            try {
                Message msg = MessageCodec.decode(buffer);
                int frameBytes = buffer.position() - frameStart;
                metrics.messagesRead.incrementAndGet();
                metrics.bytesRead.addAndGet(frameBytes);
            } catch (PartialMessageException e) {
                break;
            }
        }
    }

    private static Line randomLine(ThreadLocalRandom rng, String authorName, String clientId) {
        double x1 = rng.nextDouble(0, 4_000);
        double y1 = rng.nextDouble(0, 3_000);
        double x2 = x1 + rng.nextDouble(-400, 400);
        double y2 = y1 + rng.nextDouble(-400, 400);
        String color = String.format("#%06x", rng.nextInt(0, 0x1000000));
        double stroke = 1.0 + rng.nextDouble(0, 8);
        return Line.create(color, x1, y1, x2, y2, stroke, authorName, clientId);
    }

    private static ByteBuffer encodeLineMutation(Line line) {
        JsonObject envelope = MessageCodec.gson().toJsonTree(line).getAsJsonObject();
        envelope.addProperty(TYPE_FIELD, "Line");
        return MessageCodec.encode(new Message(MessageType.MUTATION, MessageCodec.gson().toJson(envelope)));
    }

    private static void writeFrame(SocketChannel channel, ByteBuffer frame, SwarmMetrics metrics)
            throws IOException {
        ByteBuffer buf = frame.duplicate();
        int frameBytes = buf.remaining();
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
        metrics.messagesWritten.incrementAndGet();
        metrics.bytesWritten.addAndGet(frameBytes);
    }

    private static final class SwarmMetrics {
        final AtomicLong messagesWritten = new AtomicLong();
        final AtomicLong bytesWritten = new AtomicLong();
        final AtomicLong messagesRead = new AtomicLong();
        final AtomicLong bytesRead = new AtomicLong();
        final AtomicInteger activeBots = new AtomicInteger();
    }
}
