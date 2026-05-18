package com.distrisync.server;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Global Prometheus counters for production observability.
 */
public final class ServerMetrics {

    public static final AtomicLong FRAMES_DROPPED_TOTAL = new AtomicLong();
    public static final AtomicLong FRAMES_SENT_TOTAL = new AtomicLong();
    public static final AtomicLong REDIS_MESSAGES_PUBLISHED = new AtomicLong();
    public static final AtomicLong REDIS_MESSAGES_RECEIVED = new AtomicLong();

    public static final String PROMETHEUS_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private ServerMetrics() {}

    public static String formatPrometheusText() {
        return """
                # TYPE distrisync_frames_dropped_total counter
                distrisync_frames_dropped_total %d
                # TYPE distrisync_frames_sent_total counter
                distrisync_frames_sent_total %d
                # TYPE distrisync_redis_messages_published counter
                distrisync_redis_messages_published %d
                # TYPE distrisync_redis_messages_received counter
                distrisync_redis_messages_received %d
                """.formatted(
                FRAMES_DROPPED_TOTAL.get(),
                FRAMES_SENT_TOTAL.get(),
                REDIS_MESSAGES_PUBLISHED.get(),
                REDIS_MESSAGES_RECEIVED.get());
    }
}
