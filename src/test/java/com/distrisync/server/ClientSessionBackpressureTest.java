package com.distrisync.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;

class ClientSessionBackpressureTest {

    @BeforeEach
    void resetDroppedCounter() {
        ServerMetrics.FRAMES_DROPPED_TOTAL.set(0);
    }

    @Test
    void testClientSessionBackpressureDropsEphemeral() {
        ClientSession session = new ClientSession();
        ByteBuffer dummy = ByteBuffer.allocate(4);
        dummy.putInt(1);
        dummy.flip();

        for (int i = 0; i < ClientSession.WRITE_QUEUE_CAPACITY; i++) {
            assertThat(session.enqueue(dummy, OutboundClass.CRITICAL))
                    .isEqualTo(EnqueueResult.ENQUEUED);
        }
        assertThat(session.writeQueue).hasSize(ClientSession.WRITE_QUEUE_CAPACITY);

        assertThat(session.enqueue(dummy, OutboundClass.EPHEMERAL))
                .isEqualTo(EnqueueResult.DROPPED);
        assertThat(ServerMetrics.FRAMES_DROPPED_TOTAL.get()).isEqualTo(1);
        assertThat(session.writeQueue).hasSize(ClientSession.WRITE_QUEUE_CAPACITY);

        assertThat(session.enqueue(dummy, OutboundClass.CRITICAL))
                .isEqualTo(EnqueueResult.OVERFLOW_DISCONNECT);
        assertThat(session.writeQueue).hasSize(ClientSession.WRITE_QUEUE_CAPACITY);
    }
}
