package com.distrisync.server.backplane;

import java.util.function.Consumer;

/**
 * Test seam for Redis {@code SUBSCRIBE} / pub/sub message delivery.
 */
public interface RedisSubscribeClient extends AutoCloseable {

    /**
     * Registers a handler invoked on the Redis listener thread for each message body.
     */
    void addMessageListener(Consumer<byte[]> listener);

    void subscribe(byte[] channel);

    void unsubscribe(byte[] channel);

    @Override
    void close();
}
