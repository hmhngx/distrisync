package com.distrisync.server;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class ClientSessionUdpRateLimitTest {

    @Test
    void burstAllowsFiftyThenRejects() {
        ClientSession session = new ClientSession();

        for (int i = 0; i < ClientSession.UDP_MAX_TOKENS; i++) {
            if (!session.consumeUdpToken()) {
                fail("expected burst token at index %d/%d", i, ClientSession.UDP_MAX_TOKENS);
            }
        }
        assertThat(session.consumeUdpToken())
                .as("immediate next consume must be rejected before refill")
                .isFalse();
    }

    @Test
    void refillsAfterElapsedInterval() throws InterruptedException {
        ClientSession session = new ClientSession();

        while (session.consumeUdpToken()) {
            // drain
        }
        assertThat(session.consumeUdpToken()).isFalse();

        Thread.sleep(ClientSession.UDP_REFILL_RATE_MS + 5L);

        assertThat(session.consumeUdpToken())
                .as("one token should refill after UDP_REFILL_RATE_MS")
                .isTrue();
    }

    @Test
    void longIdleDoesNotExceedMaxBurst() throws InterruptedException {
        ClientSession session = new ClientSession();

        Thread.sleep(ClientSession.UDP_REFILL_RATE_MS * ClientSession.UDP_MAX_TOKENS * 2L);

        int granted = 0;
        while (session.consumeUdpToken()) {
            granted++;
        }

        assertThat(granted)
                .as("refill must cap at UDP_MAX_TOKENS even after long idle")
                .isEqualTo(ClientSession.UDP_MAX_TOKENS);
    }
}
