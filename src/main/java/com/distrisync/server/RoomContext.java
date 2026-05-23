package com.distrisync.server;

import com.distrisync.model.Shape;
import com.distrisync.protocol.Message;
import com.distrisync.protocol.MessageCodec;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-room runtime context: workspace boards (each with authoritative canvas state) + active-key table.
 *
 * <p>Board state is created lazily via {@link #getBoard(String)}; each new board replays
 * {@link WalManager#replay(String, String, java.util.function.Consumer)} for that room/board pair when a {@link WalManager} is configured.
 * Boards that were removed via {@link #deleteBoard(String, WalManager)} stay tombstoned: {@code getBoard}
 * returns {@code null} for those ids so late packets cannot resurrect state.
 *
 * <p>{@link #lastActivityTimestamp} is refreshed on {@link #addKey} and {@link #touchActivity()}.
 */
final class RoomContext {

    private static final Logger log = LoggerFactory.getLogger(RoomContext.class);

    private static final int WAL_COMPACTION_THRESHOLD = 1000;

    final String roomId;

    /**
     * {@link ClientSession#clientId} of the first client that created this room (size == 1 on join).
     */
    volatile String hostClientId = "";

    /** When true, only clients with {@link com.distrisync.protocol.RoomPermissions#PERM_MANAGE_ROOM} may create boards. */
    volatile boolean isBoardCreationLocked = true;

    /** Authoritative room-global media snapshot; {@code null} until first {@link com.distrisync.protocol.MessageType#MEDIA_CONTROL}. */
    volatile MessageCodec.MediaStatePayload currentMediaState;

    private final WalManager wal;

    private final ConcurrentHashMap<String, CanvasStateManager> boards = new ConcurrentHashMap<>();

    private final Set<String> deletedBoardIds = ConcurrentHashMap.newKeySet();

    private final Set<SelectionKey> activeKeys = ConcurrentHashMap.newKeySet();

    /** Reverse index: {@link ClientSession#clientId} → local TCP {@link SelectionKey}. */
    private final ConcurrentHashMap<String, SelectionKey> clientIndex = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, AtomicInteger> walOperationCounts = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Object> walCompactionLocks = new ConcurrentHashMap<>();

    private volatile long lastActivityTimestamp = System.currentTimeMillis();

    RoomContext(String roomId, WalManager wal) {
        if (roomId == null) throw new IllegalArgumentException("roomId must not be null");
        this.roomId = roomId;
        this.wal   = wal;
    }

    public boolean isBoardDeleted(String boardId) {
        return boardId != null && deletedBoardIds.contains(boardId);
    }

    /**
     * Tombstones {@code boardId}, drops in-memory state, and deletes durable WAL files for this room/board.
     */
    public void deleteBoard(String boardId, WalManager walManager) {
        if (boardId == null || boardId.isBlank()) {
            throw new IllegalArgumentException("boardId must not be null or blank");
        }
        deletedBoardIds.add(boardId);
        boards.remove(boardId);
        walOperationCounts.remove(boardId);
        walCompactionLocks.remove(boardId);
        if (walManager != null) {
            try {
                walManager.deleteBoardFiles(roomId, boardId);
            } catch (IOException e) {
                log.error("deleteBoardFiles failed  roomId='{}' boardId='{}': {}", roomId, boardId, e.getMessage(), e);
            }
        }
    }

    /**
     * Returns the {@link CanvasStateManager} for {@code boardId}, creating it if needed and
     * replaying the WAL for this room/board when {@link WalManager} is non-null.
     *
     * @return {@code null} if {@code boardId} was {@linkplain #deleteBoard(String, WalManager) deleted}
     *         (tombstone); otherwise never {@code null} for valid non-blank ids
     */
    CanvasStateManager getBoard(String boardId) {
        if (boardId == null || boardId.isBlank()) {
            throw new IllegalArgumentException("boardId must not be null or blank");
        }
        if (isBoardDeleted(boardId)) {
            return null;
        }
        return boards.compute(boardId, (id, existing) -> {
            if (isBoardDeleted(id)) {
                return null;
            }
            if (existing != null) {
                return existing;
            }
            CanvasStateManager csm = new CanvasStateManager();
            replayWalForBoard(id, csm);
            if (isBoardDeleted(id)) {
                return null;
            }
            return csm;
        });
    }

    /**
     * Snapshot of workspace board ids that currently have a {@link CanvasStateManager}
     * (including empty boards created via {@link #getBoard(String)}).
     */
    Set<String> getActiveBoardIds() {
        return Set.copyOf(boards.keySet());
    }

    void addKey(SelectionKey key) {
        activeKeys.add(key);
        if (key.attachment() instanceof ClientSession session) {
            String clientId = session.clientId;
            if (clientId != null && !clientId.isBlank()) {
                clientIndex.put(clientId, key);
            }
        }
        touchActivity();
    }

    boolean removeKey(SelectionKey key) {
        if (key.attachment() instanceof ClientSession session) {
            String clientId = session.clientId;
            if (clientId != null && !clientId.isBlank()) {
                clientIndex.remove(clientId, key);
            }
        }
        return activeKeys.remove(key);
    }

    /**
     * Returns the local {@link SelectionKey} for {@code clientId}, or {@code null} if this node
     * does not host that client in this room.
     */
    SelectionKey lookupClientKey(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return null;
        }
        return clientIndex.get(clientId);
    }

    Set<SelectionKey> getActiveKeys() {
        return Collections.unmodifiableSet(activeKeys);
    }

    /**
     * Live backing set for selector-thread read-only iteration (no per-call unmodifiable wrapper).
     * Do not mutate membership except via {@link #addKey} / {@link #removeKey}.
     */
    Set<SelectionKey> activeKeysForSelectorIteration() {
        return activeKeys;
    }

    int getActiveClientCount() {
        return activeKeys.size();
    }

    void touchActivity() {
        lastActivityTimestamp = System.currentTimeMillis();
    }

    long getLastActivityTimestamp() {
        return lastActivityTimestamp;
    }

    /**
     * Called after each successful WAL append for {@code boardId}. Triggers compaction when the
     * per-board operation count exceeds {@link #WAL_COMPACTION_THRESHOLD}.
     */
    void onWalAppended(String boardId) {
        if (wal == null || boardId == null || boardId.isBlank()) {
            return;
        }

        int count = walOperationCounts
                .computeIfAbsent(boardId, k -> new AtomicInteger(0))
                .incrementAndGet();
        if (count <= WAL_COMPACTION_THRESHOLD) {
            return;
        }

        Object lock = walCompactionLocks.computeIfAbsent(boardId, k -> new Object());
        synchronized (lock) {
            AtomicInteger counter = walOperationCounts.get(boardId);
            if (counter == null || counter.get() <= WAL_COMPACTION_THRESHOLD) {
                return;
            }

            if (isBoardDeleted(boardId)) {
                return;
            }

            CanvasStateManager board = boards.get(boardId);
            if (board == null) {
                return;
            }

            try {
                wal.compactWal(roomId, boardId, board.snapshot());
                counter.set(0);
            } catch (IOException e) {
                log.error("WAL compaction failed  room='{}' board='{}': {}", roomId, boardId, e.getMessage(), e);
            }
        }
    }

    private void replayWalForBoard(String boardId, CanvasStateManager stateManager) {
        if (wal == null) return;

        long t0 = System.nanoTime();
        int[] frameCount = {0};
        int[] applied = {0};

        try {
            wal.replay(roomId, boardId, msg -> {
                frameCount[0]++;
                applyWalFrame(boardId, stateManager, msg, applied);
            });
        } catch (IOException e) {
            log.error("WAL replay failed  room='{}' board='{}': {}", roomId, boardId, e.getMessage(), e);
            return;
        }

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("[WAL] Replayed {} frames for {}_{} in {}ms. Canvas restored.",
                frameCount[0], roomId, boardId, elapsedMs);
    }

    private void applyWalFrame(String boardId, CanvasStateManager stateManager, Message msg, int[] applied) {
        switch (msg.type()) {
            case MUTATION -> {
                try {
                    if (stateManager.applyMutation(ShapeCodec.decodeMutation(msg.payload()))) {
                        applied[0]++;
                    }
                } catch (Exception e) {
                    log.warn("WAL replay: skipping malformed MUTATION  room='{}' board='{}': {}",
                            roomId, boardId, e.getMessage());
                }
            }
            case MUTATION_BATCH -> {
                try {
                    for (Shape shape : ShapeCodec.decodeMutationBatch(msg.payload())) {
                        if (stateManager.applyMutation(shape)) {
                            applied[0]++;
                        }
                    }
                } catch (Exception e) {
                    log.warn("WAL replay: skipping malformed MUTATION_BATCH  room='{}' board='{}': {}",
                            roomId, boardId, e.getMessage());
                }
            }
            case SHAPE_DELETE -> {
                try {
                    JsonObject p = JsonParser.parseString(msg.payload()).getAsJsonObject();
                    UUID shapeId = UUID.fromString(p.get("shapeId").getAsString());
                    if (stateManager.deleteShape(shapeId)) {
                        applied[0]++;
                    }
                } catch (Exception e) {
                    log.warn("WAL replay: skipping malformed SHAPE_DELETE  room='{}' board='{}': {}",
                            roomId, boardId, e.getMessage());
                }
            }
            case CLEAR_USER_SHAPES -> {
                try {
                    String clientId = MessageCodec.decodeClearUserShapes(msg);
                    stateManager.clearUserShapes(clientId);
                    applied[0]++;
                } catch (Exception e) {
                    log.warn("WAL replay: skipping malformed CLEAR_USER_SHAPES  room='{}' board='{}': {}",
                            roomId, boardId, e.getMessage());
                }
            }
            default -> { /* non-durable */ }
        }
    }
}
