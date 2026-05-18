package com.distrisync.server.backplane;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class BackplaneEventDedupTest {

    @Test
    void tryRecord_acceptsFirstRejectsDuplicate() {
        BackplaneEventDedup dedup = new BackplaneEventDedup(60_000L, 100, () -> 1_000L);

        assertThat(dedup.tryRecord("evt-1")).isTrue();
        assertThat(dedup.contains("evt-1")).isTrue();
        assertThat(dedup.tryRecord("evt-1")).isFalse();
    }

    @Test
    void tryRecord_rejectsBlankEventId() {
        BackplaneEventDedup dedup = new BackplaneEventDedup();

        assertThat(dedup.tryRecord("")).isFalse();
        assertThat(dedup.tryRecord("   ")).isFalse();
        assertThat(dedup.tryRecord(null)).isFalse();
    }

    @Test
    void tryRecord_allowsReuseAfterTtlExpires() {
        AtomicLong now = new AtomicLong(0L);
        BackplaneEventDedup dedup = new BackplaneEventDedup(1_000L, 100, now::get);

        assertThat(dedup.tryRecord("evt-ttl")).isTrue();
        assertThat(dedup.tryRecord("evt-ttl")).isFalse();

        now.set(2_000L);
        assertThat(dedup.contains("evt-ttl")).isFalse();
        assertThat(dedup.tryRecord("evt-ttl")).isTrue();
    }
}
