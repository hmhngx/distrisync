package com.distrisync.server.backplane;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RedisBackplaneSubscriberTest {

    @Mock
    private RedisSubscribeClient redisClient;

    private RedisBackplaneSubscriber subscriber;

    @AfterEach
    void tearDown() {
        if (subscriber != null) {
            subscriber.close();
        }
    }

    @Test
    void subscribeRoomUsesRoomChannel() {
        doNothing().when(redisClient).addMessageListener(any());
        doNothing().when(redisClient).subscribe(any());

        subscriber = new RedisBackplaneSubscriber(redisClient);
        subscriber.subscribeRoom("room-a");

        byte[] roomChannel = BackplaneEnvelopeCodec.roomChannel("room-a").getBytes(StandardCharsets.UTF_8);
        byte[] presenceChannel = BackplaneEnvelopeCodec.presenceChannel("room-a").getBytes(StandardCharsets.UTF_8);
        verify(redisClient).subscribe(eq(roomChannel));
        verify(redisClient).subscribe(eq(presenceChannel));
        verify(redisClient, times(2)).subscribe(any());
    }

    @Test
    void onMessageOffersToMailboxAndWakesSelector() {
        AtomicBoolean woken = new AtomicBoolean(false);
        ConcurrentLinkedQueue<BackplaneEnvelope> mailbox = new ConcurrentLinkedQueue<>();
        AtomicReference<Consumer<byte[]>> listenerRef = new AtomicReference<>();

        doAnswer(invocation -> {
            listenerRef.set(invocation.getArgument(0));
            return null;
        }).when(redisClient).addMessageListener(any());

        subscriber = new RedisBackplaneSubscriber(redisClient);
        subscriber.bind(mailbox, () -> woken.set(true));

        byte[] body = BackplaneEnvelopeCodec.encode(new BackplaneEnvelope(
                "evt-1",
                "remote-node",
                "room-a",
                "board-1",
                ByteBuffer.wrap(new byte[] { 1, 2, 3 })));
        listenerRef.get().accept(body);

        assertThat(mailbox).hasSize(1);
        assertThat(woken.get()).isTrue();
    }
}
