package com.distrisync.server.backplane;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.codec.ByteArrayCodec;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Lettuce-backed {@link RedisSubscribeClient} using binary channels and payloads.
 */
public final class LettuceRedisSubscribeClient implements RedisSubscribeClient {

    private final RedisClient redisClient;
    private final StatefulRedisPubSubConnection<byte[], byte[]> connection;

    public LettuceRedisSubscribeClient(String redisUri) {
        Objects.requireNonNull(redisUri, "redisUri must not be null");
        if (redisUri.isBlank()) throw new IllegalArgumentException("redisUri must not be blank");

        RedisURI uri = RedisURI.create(redisUri);
        if (uri.getTimeout() == null) {
            uri.setTimeout(Duration.ofSeconds(2));
        }

        this.redisClient = RedisClient.create(uri);
        this.connection = redisClient.connectPubSub(ByteArrayCodec.INSTANCE);
    }

    LettuceRedisSubscribeClient(
            RedisClient redisClient,
            StatefulRedisPubSubConnection<byte[], byte[]> connection) {
        this.redisClient = Objects.requireNonNull(redisClient, "redisClient must not be null");
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
    }

    @Override
    public void addMessageListener(Consumer<byte[]> listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        connection.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void message(byte[] channel, byte[] message) {
                listener.accept(message);
            }
        });
    }

    @Override
    public void subscribe(byte[] channel) {
        Objects.requireNonNull(channel, "channel must not be null");
        connection.sync().subscribe(channel);
    }

    @Override
    public void unsubscribe(byte[] channel) {
        Objects.requireNonNull(channel, "channel must not be null");
        connection.sync().unsubscribe(channel);
    }

    @Override
    public void close() {
        connection.close();
        redisClient.shutdown();
    }
}
