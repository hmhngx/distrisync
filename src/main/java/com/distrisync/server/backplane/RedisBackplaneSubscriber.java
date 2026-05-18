package com.distrisync.server.backplane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.distrisync.server.ServerEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Redis Pub/Sub consumer for cross-node mutation fanout.
 *
 * <p>Incoming messages are deserialized and placed on the NIO {@code remoteMailbox};
 * the subscriber thread never touches {@link java.nio.channels.SelectionKey} or
 * {@link com.distrisync.server.ClientSession} queues.
 */
public final class RedisBackplaneSubscriber implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisBackplaneSubscriber.class);

    private final RedisSubscribeClient client;
    private final Set<String> subscribedChannels = ConcurrentHashMap.newKeySet();

    private volatile ConcurrentLinkedQueue<BackplaneEnvelope> mailbox;
    private volatile Runnable wakeup;

    public static RedisBackplaneSubscriber connect(String redisUri) {
        Objects.requireNonNull(redisUri, "redisUri must not be null");
        return new RedisBackplaneSubscriber(new LettuceRedisSubscribeClient(redisUri));
    }

    /**
     * Connects when {@link ServerEnvironment#resolveRedisUri()} is present; otherwise empty.
     */
    public static Optional<RedisBackplaneSubscriber> connectFromEnvironment() {
        return ServerEnvironment.resolveRedisUri().map(RedisBackplaneSubscriber::connect);
    }

    public RedisBackplaneSubscriber(RedisSubscribeClient client) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        client.addMessageListener(this::onRedisMessage);
    }

    /**
     * Wires the cross-thread mailbox and selector wakeup; invoked once from {@link com.distrisync.server.NioServer#run()}.
     */
    public void bind(ConcurrentLinkedQueue<BackplaneEnvelope> mailbox, Runnable wakeup) {
        this.mailbox = Objects.requireNonNull(mailbox, "mailbox must not be null");
        this.wakeup = Objects.requireNonNull(wakeup, "wakeup must not be null");
    }

    /**
     * Subscribes to {@link BackplaneEnvelopeCodec#roomChannel(String)} when a room is first created locally.
     */
    public void subscribeRoom(String roomId) {
        if (roomId == null || roomId.isBlank()) {
            throw new IllegalArgumentException("roomId must not be blank");
        }
        subscribeChannel(BackplaneEnvelopeCodec.roomChannel(roomId));
        subscribeChannel(BackplaneEnvelopeCodec.presenceChannel(roomId));
    }

    private void subscribeChannel(String channel) {
        if (!subscribedChannels.add(channel)) {
            return;
        }
        byte[] channelBytes = channel.getBytes(StandardCharsets.UTF_8);
        client.subscribe(channelBytes);
        log.debug("Backplane subscribed  channel='{}'", channel);
    }

    private void onRedisMessage(byte[] message) {
        ConcurrentLinkedQueue<BackplaneEnvelope> queue = mailbox;
        Runnable wake = wakeup;
        if (queue == null || wake == null) {
            log.warn("Backplane message received before bind — dropping");
            return;
        }
        try {
            BackplaneEnvelope envelope = BackplaneEnvelopeCodec.decode(message);
            queue.offer(envelope);
            wake.run();
        } catch (Exception e) {
            log.error("Backplane message decode failed: {}", e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        for (String channel : subscribedChannels) {
            try {
                client.unsubscribe(channel.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                log.warn("Backplane unsubscribe failed  channel='{}': {}", channel, e.getMessage());
            }
        }
        subscribedChannels.clear();
        client.close();
    }
}
