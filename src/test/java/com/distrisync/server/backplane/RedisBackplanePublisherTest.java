package com.distrisync.server.backplane;

import com.distrisync.server.ServerMetrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RedisBackplanePublisherTest {

    @Mock
    private RedisPublishClient redisClient;

    private RedisBackplanePublisher publisher;

    @BeforeEach
    void resetPublishedCounter() {
        ServerMetrics.REDIS_MESSAGES_PUBLISHED.set(0);
    }

    @AfterEach
    void tearDown() {
        if (publisher != null) {
            publisher.close();
        }
    }

    @Test
    void testRedisPublisherIsAsynchronous() throws Exception {
        doNothing().when(redisClient).close();

        CountDownLatch publishStarted = new CountDownLatch(1);
        CountDownLatch publishFinished = new CountDownLatch(1);

        doAnswer(invocation -> {
            publishStarted.countDown();
            Thread.sleep(500);
            publishFinished.countDown();
            return 0L;
        }).when(redisClient).publish(anyString(), any(byte[].class));

        publisher = new RedisBackplanePublisher(
                "test-node-id",
                redisClient,
                Executors.newSingleThreadExecutor(),
                true);

        BackplaneEnvelope envelope = new BackplaneEnvelope(
                "event-1",
                "test-node-id",
                "room-a",
                "Board-1",
                ByteBuffer.wrap(new byte[] { 0x03, 0, 0, 0, 2, '{', '}' }));

        long startNanos = System.nanoTime();
        publisher.publish(envelope);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertThat(elapsedMs).isLessThan(10);

        assertThat(publishStarted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(publishFinished.await(2, TimeUnit.SECONDS)).isTrue();
        verify(redisClient).publish(eq(BackplaneEnvelopeCodec.roomChannel("room-a")), any(byte[].class));
        assertThat(ServerMetrics.REDIS_MESSAGES_PUBLISHED.get()).isEqualTo(1);
    }

    @Test
    void testPublishPresenceUsesPresenceChannel() throws Exception {
        doNothing().when(redisClient).close();

        CountDownLatch publishFinished = new CountDownLatch(1);
        doAnswer(invocation -> {
            publishFinished.countDown();
            return 0L;
        }).when(redisClient).publish(anyString(), any(byte[].class));

        publisher = new RedisBackplanePublisher(
                "test-node-id",
                redisClient,
                Executors.newSingleThreadExecutor(),
                true);

        BackplaneEnvelope envelope = new BackplaneEnvelope(
                "event-cursor",
                "test-node-id",
                "room-a",
                "Board-1",
                ByteBuffer.wrap(new byte[] { 0x1A, 0, 0, 0, 2, '{', '}' }));

        publisher.publishPresence(envelope);

        assertThat(publishFinished.await(2, TimeUnit.SECONDS)).isTrue();
        verify(redisClient).publish(eq(BackplaneEnvelopeCodec.presenceChannel("room-a")), any(byte[].class));
    }
}
