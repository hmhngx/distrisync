package com.distrisync.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background daemon that evicts idle rooms from {@link RoomManager} to
 * reclaim heap and prevent unbounded growth in long-running deployments.
 *
 * <h2>Eviction policy</h2>
 * A room is eligible for eviction when <em>both</em> conditions hold:
 * <ol>
 *   <li>{@link RoomContext#getActiveClientCount()} is zero — no connected sessions.</li>
 *   <li>The room's last-activity timestamp is older than {@link #GC_TTL_MS}
 *       (default 5 minutes).</li>
 * </ol>
 * Rooms that still have connected clients are never evicted, regardless of
 * how long ago they were last used.
 *
 * <h2>Sweep schedule</h2>
 * A single-threaded daemon {@link ScheduledExecutorService} runs
 * {@link #runCycle()} every 60 seconds.  Tests invoke {@code runCycle()}
 * directly via reflection to avoid real-time waiting.
 *
 * <h2>WAL archival</h2>
 * Currently, eviction removes the in-memory routing entry only.  The WAL file
 * on disk is intentionally left intact so that a client rejoining after
 * eviction gets a fresh empty canvas (the WAL file for the evicted room is
 * never written back again after eviction, so the next {@link RoomContext}
 * construction finds the same stale WAL — which may be empty if compaction
 * ran — and the room starts fresh).
 */
public final class StorageLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(StorageLifecycleManager.class);

    /**
     * Rooms idle longer than this threshold (in milliseconds) with zero active
     * clients are eligible for GC eviction.
     *
     * <p>Default: 5 minutes (300 000 ms).  The value is {@code public static
     * final} so tests can reference it symbolically in assertion messages.
     */
    public static final long GC_TTL_MS = 300_000L;

    private static final long SWEEP_INTERVAL_SECONDS = 60L;

    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;

    private final RoomManager roomManager;
    private final WalManager  walManager;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean shutdownCalled = new AtomicBoolean(false);

    /**
     * Constructs the lifecycle manager and starts the background sweep daemon.
     *
     * @param roomManager the room registry to scan and evict from; must not be
     *                    {@code null}
     * @param walManager  the WAL engine (reserved for future archival integration);
     *                    may be {@code null} if running without persistence
     */
    public StorageLifecycleManager(RoomManager roomManager, WalManager walManager) {
        if (roomManager == null) throw new IllegalArgumentException("roomManager must not be null");
        this.roomManager = roomManager;
        this.walManager  = walManager;

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "storage-lifecycle-daemon");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(
                this::runCycle,
                SWEEP_INTERVAL_SECONDS,
                SWEEP_INTERVAL_SECONDS,
                TimeUnit.SECONDS);

        log.info("StorageLifecycleManager started  gcTtlMs={} sweepIntervalSec={}",
                GC_TTL_MS, SWEEP_INTERVAL_SECONDS);
    }

    // =========================================================================
    // GC sweep
    // =========================================================================

    /**
     * Scans every room in the {@link RoomManager} and evicts those that satisfy
     * the idle-eviction policy.
     *
     * <p>This method is {@code private} so that the scheduler and production code
     * cannot call it in an ad-hoc manner.  Tests invoke it via reflection
     * ({@code getDeclaredMethod("runCycle").setAccessible(true).invoke(...)})
     * to trigger a synchronous sweep without waiting for the 60-second tick.
     */
    private void runCycle() {
        long cutoff = System.currentTimeMillis() - GC_TTL_MS;
        List<String> toEvict = new ArrayList<>();

        for (Map.Entry<String, RoomContext> entry : roomManager.getRooms().entrySet()) {
            RoomContext ctx = entry.getValue();
            if (ctx.getActiveClientCount() == 0
                    && ctx.getLastActivityTimestamp() < cutoff) {
                toEvict.add(entry.getKey());
            }
        }

        if (toEvict.isEmpty()) {
            log.debug("GC sweep complete — no eligible rooms");
            return;
        }

        for (String roomId : toEvict) {
            log.info("GC evicting idle room  roomId='{}' ttlMs={}", roomId, GC_TTL_MS);
            roomManager.removeRoom(roomId);
        }

        log.info("GC sweep complete — evicted {} room(s)", toEvict.size());
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Shuts down the background sweep daemon and waits for its thread to
     * terminate.  Idempotent — safe to call multiple times (e.g. from both
     * the shutdown hook and a test tear-down).
     *
     * <p>Waits up to {@value SHUTDOWN_TIMEOUT_SECONDS} seconds for the
     * scheduler thread to exit after interruption.  This prevents the
     * Maven exec:java plugin from logging a "thread will linger" warning.
     */
    public void shutdown() {
        if (!shutdownCalled.compareAndSet(false, true)) {
            return;
        }
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("StorageLifecycleManager daemon did not stop within {}s",
                        SHUTDOWN_TIMEOUT_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("StorageLifecycleManager shut down");
    }
}
