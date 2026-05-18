package com.distrisync.server.backplane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Idempotency guard for Redis backplane {@code eventId} values.
 *
 * <p>Thread-safe for concurrent {@link #tryRecord} from the NIO selector thread and
 * pre-registration on the publish path. Uses TTL expiry and a hard cap to bound memory.
 */
public final class BackplaneEventDedup {

    private static final Logger log = LoggerFactory.getLogger(BackplaneEventDedup.class);

    static final long DEFAULT_TTL_MS = 10L * 60L * 1000L;
    static final int DEFAULT_MAX_ENTRIES = 50_000;
    private static final int EVICT_SWEEP_INTERVAL = 256;

    private final ConcurrentHashMap<String, Long> seen = new ConcurrentHashMap<>();
    private final long ttlMs;
    private final int maxEntries;
    private final LongSupplier clock;
    private int insertsSinceSweep;

    public BackplaneEventDedup() {
        this(DEFAULT_TTL_MS, DEFAULT_MAX_ENTRIES, System::currentTimeMillis);
    }

    BackplaneEventDedup(long ttlMs, int maxEntries, LongSupplier clock) {
        if (ttlMs <= 0) {
            throw new IllegalArgumentException("ttlMs must be positive");
        }
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.ttlMs = ttlMs;
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    /**
     * Records {@code eventId} if not already present and not expired.
     *
     * @return {@code true} if this is the first time the id is recorded within TTL;
     *         {@code false} if duplicate or blank
     */
    public boolean tryRecord(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            log.warn("Backplane eventId is blank — dropping envelope");
            return false;
        }

        long now = clock.getAsLong();
        maybeSweep(now);

        Long existingExpiry = seen.get(eventId);
        if (existingExpiry != null && existingExpiry > now) {
            return false;
        }

        long expiresAt = now + ttlMs;
        Long prior = seen.put(eventId, expiresAt);
        if (prior != null && prior > now) {
            // Lost race to another thread — treat as duplicate.
            seen.put(eventId, prior);
            return false;
        }

        insertsSinceSweep++;
        enforceMaxEntries(now);
        return true;
    }

    /** @return whether {@code eventId} is currently recorded and not expired */
    public boolean contains(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return false;
        }
        long now = clock.getAsLong();
        Long expiresAt = seen.get(eventId);
        return expiresAt != null && expiresAt > now;
    }

    private void maybeSweep(long now) {
        if (insertsSinceSweep < EVICT_SWEEP_INTERVAL) {
            return;
        }
        insertsSinceSweep = 0;
        evictExpired(now);
    }

    private void evictExpired(long now) {
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (e.getValue() <= now) {
                it.remove();
            }
        }
    }

    private void enforceMaxEntries(long now) {
        if (seen.size() <= maxEntries) {
            return;
        }
        evictExpired(now);
        if (seen.size() <= maxEntries) {
            return;
        }
        int toRemove = seen.size() - maxEntries;
        Iterator<String> keys = seen.keySet().iterator();
        while (toRemove > 0 && keys.hasNext()) {
            keys.next();
            keys.remove();
            toRemove--;
        }
        log.debug("BackplaneEventDedup evicted entries to cap size at {}", maxEntries);
    }
}
