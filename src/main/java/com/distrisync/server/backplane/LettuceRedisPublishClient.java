package com.distrisync.server.backplane;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;

import java.time.Duration;
import java.util.Objects;

/**
 * Lettuce-backed {@link RedisPublishClient} using binary channels and payloads.
 */
public final class LettuceRedisPublishClient implements RedisPublishClient {

    private final RedisClient redisClient;
    private final StatefulRedisConnection<byte[], byte[]> connection;

    public LettuceRedisPublishClient(String redisUri) {
        Objects.requireNonNull(redisUri, "redisUri must not be null");
        if (redisUri.isBlank()) throw new IllegalArgumentException("redisUri must not be blank");

        RedisURI uri = RedisURI.create(redisUri);
        if (uri.getTimeout() == null) {
            uri.setTimeout(Duration.ofSeconds(2));
        }

        this.redisClient = RedisClient.create(uri);
        this.connection = redisClient.connect(ByteArrayCodec.INSTANCE);
    }

    LettuceRedisPublishClient(RedisClient redisClient, StatefulRedisConnection<byte[], byte[]> connection) {
        this.redisClient = Objects.requireNonNull(redisClient, "redisClient must not be null");
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
    }

    @Override
    public long publish(String channel, byte[] message) {
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(message, "message must not be null");
        byte[] channelBytes = channel.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return connection.sync().publish(channelBytes, message);
    }

    @Override
    public void close() {
        connection.close();
        redisClient.shutdown();
    }
}
