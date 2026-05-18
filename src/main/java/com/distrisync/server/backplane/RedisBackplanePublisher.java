package com.distrisync.server.backplane;

import com.distrisync.server.ServerMetrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.distrisync.server.ServerEnvironment;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Fire-and-forget Redis Pub/Sub publisher for cross-node mutation fanout.
 *
 * <p>Network I/O runs on a dedicated worker pool; callers (e.g. the NIO selector thread)
 * must never block on Redis.
 */
public final class RedisBackplanePublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisBackplanePublisher.class);

    private static final int DEFAULT_WORKER_THREADS = 4;

    private final String originNodeId;
    private final RedisPublishClient client;
    private final ExecutorService executor;
    private final boolean ownsExecutor;

    public static RedisBackplanePublisher connect(String originNodeId, String redisUri) {
        Objects.requireNonNull(originNodeId, "originNodeId must not be null");
        Objects.requireNonNull(redisUri, "redisUri must not be null");
        return new RedisBackplanePublisher(
                originNodeId,
                new LettuceRedisPublishClient(redisUri),
                Executors.newFixedThreadPool(DEFAULT_WORKER_THREADS),
                true);
    }

    /**
     * Connects when {@link ServerEnvironment#resolveRedisUri()} is present; otherwise empty.
     */
    public static Optional<RedisBackplanePublisher> connectFromEnvironment(String nodeId) {
        return ServerEnvironment.resolveRedisUri().map(uri -> connect(nodeId, uri));
    }

    public RedisBackplanePublisher(String originNodeId, RedisPublishClient client) {
        this(originNodeId, client, Executors.newFixedThreadPool(DEFAULT_WORKER_THREADS), true);
    }

    public RedisBackplanePublisher(
            String originNodeId,
            RedisPublishClient client,
            ExecutorService executor,
            boolean ownsExecutor) {
        if (originNodeId == null || originNodeId.isBlank()) {
            throw new IllegalArgumentException("originNodeId must not be blank");
        }
        this.originNodeId = originNodeId;
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.ownsExecutor = ownsExecutor;
    }

    public String originNodeId() {
        return originNodeId;
    }

    /**
     * Enqueues a Redis publish; returns immediately without waiting for network I/O.
     */
    public void publish(BackplaneEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope must not be null");
        executor.execute(() -> publishOnWorker(envelope, BackplaneEnvelopeCodec.roomChannel(envelope.roomId())));
    }

    /**
     * Publishes to the room presence channel for ephemeral cursor fanout (no WAL / dedup).
     */
    public void publishPresence(BackplaneEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope must not be null");
        executor.execute(() -> publishOnWorker(
                envelope, BackplaneEnvelopeCodec.presenceChannel(envelope.roomId())));
    }

    private void publishOnWorker(BackplaneEnvelope envelope, String channel) {
        try {
            byte[] body = BackplaneEnvelopeCodec.encode(envelope);
            long subscribers = client.publish(channel, body);
            ServerMetrics.REDIS_MESSAGES_PUBLISHED.incrementAndGet();
            log.debug("Backplane published  channel='{}' eventId='{}' subscribers={}",
                    channel, envelope.eventId(), subscribers);
        } catch (Exception e) {
            log.error("Backplane publish failed  room='{}' board='{}' eventId='{}': {}",
                    envelope.roomId(), envelope.boardId(), envelope.eventId(), e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        if (ownsExecutor) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        client.close();
    }
}
