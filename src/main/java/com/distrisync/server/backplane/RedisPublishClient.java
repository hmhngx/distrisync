package com.distrisync.server.backplane;

/**
 * Test seam for Redis {@code PUBLISH}; implemented by {@link LettuceRedisPublishClient} in production.
 */
public interface RedisPublishClient extends AutoCloseable {

    /**
     * @return number of subscribers that received the message
     */
    long publish(String channel, byte[] message);

    @Override
    void close();
}
