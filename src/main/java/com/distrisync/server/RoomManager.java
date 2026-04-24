package com.distrisync.server;

import com.distrisync.model.Shape;
import com.distrisync.protocol.Message;
import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.MessageCodec.LobbyRoomEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Multi-tenant room registry plus a global discovery lobby.
 *
 * <p>Maintains a {@link ConcurrentHashMap} of {@link RoomContext}s keyed by
 * room ID.  Room creation is atomic via
 * {@link ConcurrentHashMap#computeIfAbsent}, so concurrent clients racing to
 * join the same room for the first time always receive the same
 * {@link RoomContext} instance.
 *
 * <p>Clients that have completed {@code HANDSHAKE} but not yet {@code JOIN_ROOM}
 * are tracked in {@link #lobbyClients}.  Any change to lobby membership or to
 * per-room occupancy triggers a fresh {@link com.distrisync.protocol.MessageType#LOBBY_STATE}
 * fan-out via the {@link #setLobbyFanout} callback (installed by {@link NioServer}).
 *
 * <h2>WAL integration</h2>
 * An optional {@link WalManager} may be supplied at construction.  When present
 * each {@link #appendToWal} call persists the message before it is broadcast;
 * per-board state is replayed lazily when a board is first opened.
 * {@code LOBBY_STATE} also lists room ids discovered from {@code *.wal} on disk
 * (with {@code userCount = 0} when no in-memory room exists yet).
 *
 * <h2>Thread safety</h2>
 * All public methods are safe to call from multiple threads concurrently.
 */
public final class RoomManager {

    private static final Logger log = LoggerFactory.getLogger(RoomManager.class);

    private final ConcurrentHashMap<String, RoomContext> rooms = new ConcurrentHashMap<>();
    private final WalManager walManager;

    /** TCP keys waiting in the discovery lobby (post-handshake, pre-join). */
    private final Set<SelectionKey> lobbyClients = ConcurrentHashMap.newKeySet();

    /**
     * Delivers a fully encoded {@code LOBBY_STATE} frame to every lobby client.
     * Set by {@link NioServer} on the selector thread; may also be invoked from
     * the storage lifecycle daemon after GC.
     */
    private volatile Consumer<ByteBuffer> lobbyFanout;

    /**
     * Optional hooks installed by {@link NioServer} on the selector thread so
     * forced room teardown can revoke UDP admission and arm immediate TCP flushes
     * after {@code ROOM_DELETED} is enqueued.
     */
    private volatile Consumer<ClientSession> sessionEvictionHook;
    private volatile BiConsumer<ClientSession, SelectionKey> flushAfterRoomDeletedHook;

    /** Creates a room manager with no WAL persistence (rooms are ephemeral). */
    public RoomManager() {
        this.walManager = null;
    }

    /**
     * Creates a WAL-backed room manager.
     *
     * @param walManager the WAL engine used for append and recovery;
     *                   must not be {@code null}
     */
    public RoomManager(WalManager walManager) {
        if (walManager == null) throw new IllegalArgumentException("walManager must not be null");
        this.walManager = walManager;
    }

    /**
     * Installs the callback that broadcasts {@code LOBBY_STATE} to all current
     * lobby subscribers.  Typically invoked once from {@link NioServer#run()}.
     */
    public void setLobbyFanout(Consumer<ByteBuffer> fanout) {
        this.lobbyFanout = fanout;
    }

    /**
     * Installs optional per-session hooks used during {@link #deleteRoom(String, WalManager)}.
     * Either argument may be {@code null} (e.g. in unit tests that only assert queue contents).
     *
     * @param sessionEvictionHook      typically revokes UDP tokens (same as {@code LEAVE_ROOM})
     * @param flushAfterRoomDeletedHook typically flushes the TCP write queue / arms {@code OP_WRITE}
     */
    public void setRoomDeletionClientHooks(
            Consumer<ClientSession> sessionEvictionHook,
            BiConsumer<ClientSession, SelectionKey> flushAfterRoomDeletedHook) {
        this.sessionEvictionHook = sessionEvictionHook;
        this.flushAfterRoomDeletedHook = flushAfterRoomDeletedHook;
    }

    /**
     * Removes {@code roomId} from the routing table, notifies every connected client in that room
     * with {@link com.distrisync.protocol.MessageType#ROOM_DELETED}, clears their active room/board
     * fields (and optional UDP hooks), deletes durable {@code .wal} files via {@code walManager},
     * and pushes an updated {@code LOBBY_STATE} to lobby subscribers.
     *
     * @param roomId     non-blank room id to tear down
     * @param walManager WAL engine for {@link WalManager#deleteRoomFiles(String)}; {@code null} skips disk delete
     */
    public void deleteRoom(String roomId, WalManager walManager) {
        if (roomId == null || roomId.isBlank()) {
            throw new IllegalArgumentException("roomId must not be null or blank");
        }
        RoomContext ctx = rooms.remove(roomId);
        if (ctx != null) {
            ByteBuffer roomDeletedFrame = MessageCodec.encodeRoomDeleted();
            List<SelectionKey> keys = new ArrayList<>(ctx.activeKeysForSelectorIteration());
            for (SelectionKey key : keys) {
                if (key == null || !key.isValid()) {
                    continue;
                }
                Object att = key.attachment();
                if (att instanceof ClientSession session) {
                    session.enqueue(roomDeletedFrame);
                    Consumer<ClientSession> evict = sessionEvictionHook;
                    if (evict != null) {
                        evict.accept(session);
                    }
                    session.roomId = "";
                    session.currentBoardId = "";
                    lobbyClients.add(key);
                    BiConsumer<ClientSession, SelectionKey> flush = flushAfterRoomDeletedHook;
                    if (flush != null) {
                        flush.accept(session, key);
                    }
                }
                ctx.removeKey(key);
            }
        }
        if (walManager != null) {
            try {
                walManager.deleteRoomFiles(roomId);
            } catch (IOException e) {
                log.error("deleteRoomFiles failed  roomId='{}': {}", roomId, e.getMessage(), e);
            }
        }
    }

    /** @return {@code true} if {@code key} is registered in the discovery lobby */
    public boolean isInLobby(SelectionKey key) {
        return lobbyClients.contains(key);
    }

    /**
     * After a successful {@code HANDSHAKE}, registers the client in the lobby and
     * pushes an updated {@code LOBBY_STATE} to all lobby clients (including the new one).
     */
    public void registerHandshakeToLobby(SelectionKey key) {
        if (lobbyClients.add(key)) {
            notifyLobbySubscribers();
        }
    }

    /**
     * Removes a key from the lobby set without touching canvas rooms.  Used on disconnect.
     */
    public void removeFromLobby(SelectionKey key) {
        if (lobbyClients.remove(key)) {
            notifyLobbySubscribers();
        }
    }

    /**
     * Moves a client from the lobby (or from {@code previousRoomId} if non-blank)
     * into {@code newRoomId}, then broadcasts {@code LOBBY_STATE}.
     *
     * @param previousRoomId room the client was in, or blank if coming from lobby only
     * @return the {@link RoomContext} for {@code newRoomId}
     */
    public RoomContext assignClientToRoom(SelectionKey key, String newRoomId, String previousRoomId) {
        if (newRoomId == null || newRoomId.isBlank()) {
            throw new IllegalArgumentException("newRoomId must not be null or blank");
        }
        lobbyClients.remove(key);
        if (previousRoomId != null && !previousRoomId.isBlank()) {
            RoomContext prev = rooms.get(previousRoomId);
            if (prev != null) {
                prev.removeKey(key);
            }
        }
        RoomContext ctx = getOrCreateRoom(newRoomId);
        ctx.addKey(key);
        notifyLobbySubscribers();
        return ctx;
    }

    /**
     * Removes a client from their canvas room and places them back in the lobby.
     */
    public void returnClientToLobby(SelectionKey key, String currentRoomId) {
        if (currentRoomId == null || currentRoomId.isBlank()) {
            return;
        }
        RoomContext room = rooms.get(currentRoomId);
        if (room != null) {
            room.removeKey(key);
            if (room.getActiveClientCount() == 0) {
                rooms.remove(currentRoomId, room);
            }
        }
        lobbyClients.add(key);
        notifyLobbySubscribers();
    }

    // =========================================================================
    // Room lifecycle
    // =========================================================================

    /**
     * Returns the existing {@link RoomContext} for {@code roomId}, or creates
     * one if none exists.  The creation is atomic — concurrent callers always
     * receive the same instance.
     *
     * <p>When this manager was constructed with a {@link WalManager}, each board's
     * WAL is replayed the first time {@link RoomContext#getBoard} opens that board.
     *
     * @param roomId non-null, non-blank room identifier
     * @return the canonical {@link RoomContext} for this room
     * @throws IllegalArgumentException if {@code roomId} is {@code null} or blank
     */
    public RoomContext getOrCreateRoom(String roomId) {
        if (roomId == null || roomId.isBlank()) {
            throw new IllegalArgumentException("roomId must not be null or blank");
        }
        return rooms.computeIfAbsent(roomId, id -> {
            log.debug("Creating room  roomId='{}'", id);
            return new RoomContext(id, walManager);
        });
    }

    /**
     * Returns the {@link RoomContext} for {@code roomId}, or {@code null} if
     * no room with that ID exists.
     *
     * @param roomId the room to look up; {@code null} returns {@code null}
     */
    public RoomContext getRoom(String roomId) {
        if (roomId == null) return null;
        return rooms.get(roomId);
    }

    /**
     * Returns a point-in-time snapshot of the canvas for {@code roomId}.
     *
     * <p>Returns an empty, unmodifiable list if the room does not exist.
     *
     * @param roomId the room to query; may be {@code null}
     */
    public List<Shape> getRoomSnapshot(String roomId) {
        RoomContext room = getRoom(roomId);
        return room != null
                ? room.getBoard(MessageCodec.DEFAULT_INITIAL_BOARD_ID).snapshot()
                : List.of();
    }

    // =========================================================================
    // Client management
    // =========================================================================

    /**
     * Removes {@code key} from the active-key set of {@code roomId}.
     *
     * <p>A {@code null} or blank {@code roomId} is silently ignored —
     * sessions that disconnect before completing their HANDSHAKE have
     * {@code roomId == ""} and must not trigger an exception.
     *
     * @param roomId the room to remove from; null/blank → no-op
     * @param key    the client key to remove
     */
    public void removeClientFromRoom(String roomId, SelectionKey key) {
        if (roomId == null || roomId.isBlank()) return;
        RoomContext room = rooms.get(roomId);
        if (room != null && room.removeKey(key)) {
            notifyLobbySubscribers();
        }
    }

    /**
     * Returns an unmodifiable view of {@link SelectionKey}s currently registered
     * in {@code roomId}, or an empty set if the room does not exist.
     *
     * <p>Used by {@link NioServer} for room-scoped broadcast fan-out.
     *
     * @param roomId the room to query; {@code null} yields an empty set
     */
    public Set<SelectionKey> getActiveClientKeys(String roomId) {
        RoomContext ctx = getRoom(roomId);
        return ctx != null ? ctx.getActiveKeys() : Set.of();
    }

    /**
     * Snapshot of lobby keys for iteration on the NIO thread (avoids concurrent
     * modification while fanning out {@code LOBBY_STATE}).
     */
    List<SelectionKey> snapshotLobbyKeys() {
        return List.copyOf(lobbyClients);
    }

    /**
     * Pure merge used by {@link #buildLobbyRoomEntries()}: every in-memory room with its live
     * {@code userCount}, plus any persisted WAL room stem not already covered by
     * {@link WalManager#sanitize} of an active room id (those appear with {@code userCount = 0}).
     */
    static List<LobbyRoomEntry> mergePersistedWalStemsWithActiveRooms(
            Set<String> persistedStemsFromWal,
            Map<String, Integer> activeRoomIdToUserCount) {

        Set<String> persistedOnly = new HashSet<>(persistedStemsFromWal);
        for (String activeRoomId : activeRoomIdToUserCount.keySet()) {
            persistedOnly.remove(WalManager.sanitize(activeRoomId));
        }

        List<LobbyRoomEntry> list =
                new ArrayList<>(activeRoomIdToUserCount.size() + persistedOnly.size());
        for (var e : activeRoomIdToUserCount.entrySet()) {
            list.add(new LobbyRoomEntry(e.getKey(), e.getValue()));
        }
        for (String stem : persistedOnly) {
            list.add(new LobbyRoomEntry(stem, 0));
        }
        list.sort(Comparator.comparing(LobbyRoomEntry::roomId));
        return list;
    }

    private List<LobbyRoomEntry> buildLobbyRoomEntries() {
        Set<String> persistedStems = Set.of();
        if (walManager != null) {
            try {
                persistedStems = walManager.getPersistedRoomIds();
            } catch (IOException e) {
                log.warn("Failed to list persisted WAL room ids: {}", e.getMessage());
            }
        }
        Map<String, Integer> activeCounts = new HashMap<>(rooms.size());
        for (var e : rooms.entrySet()) {
            activeCounts.put(e.getKey(), e.getValue().getActiveClientCount());
        }
        return mergePersistedWalStemsWithActiveRooms(persistedStems, activeCounts);
    }

    /**
     * Point-in-time snapshot of merged active and persisted lobby rows — the same list
     * {@link #notifyLobbySubscribers()} encodes into {@code LOBBY_STATE} for lobby broadcasts.
     */
    public List<LobbyRoomEntry> snapshotLobbyRoomEntries() {
        return List.copyOf(buildLobbyRoomEntries());
    }

    private void notifyLobbySubscribers() {
        Consumer<ByteBuffer> fan = lobbyFanout;
        if (fan == null) {
            return;
        }
        ByteBuffer frame = MessageCodec.encodeLobbyState(snapshotLobbyRoomEntries());
        fan.accept(frame);
    }

    // =========================================================================
    // WAL delegation
    // =========================================================================

    /**
     * Appends {@code msg} to the WAL for {@code roomId} and {@code boardId}.
     *
     * <p>No-op if this manager was constructed without a {@link WalManager}.
     * I/O errors are logged but not re-thrown so that a WAL write failure
     * never disrupts the NIO event loop.
     */
    public void appendToWal(String roomId, String boardId, Message msg) {
        if (walManager == null) return;
        try {
            walManager.append(roomId, boardId, msg);
        } catch (IOException e) {
            log.error("WAL append failed  room='{}' board='{}': {}", roomId, boardId, e.getMessage(), e);
        }
    }

    // =========================================================================
    // Package-private hooks for StorageLifecycleManager
    // =========================================================================

    /**
     * Removes the room from the routing table.  Called by
     * {@link StorageLifecycleManager} when a room is GC-eligible.
     *
     * <p>Eviction is applied only if the routing entry still exists and still has
     * zero active clients, so a client that joins between the GC sweep and this
     * call cannot be dropped from the map.
     */
    void removeRoom(String roomId) {
        if (roomId == null || roomId.isBlank()) {
            return;
        }
        final boolean[] evicted = {false};
        rooms.compute(roomId, (id, ctx) -> {
            if (ctx == null) {
                return null;
            }
            if (ctx.getActiveClientCount() != 0) {
                return ctx;
            }
            log.info("Room evicted by GC  roomId='{}'", id);
            evicted[0] = true;
            return null;
        });
        if (evicted[0]) {
            notifyLobbySubscribers();
        }
    }

    /**
     * Exposes the room map for iteration by the lifecycle manager.
     * The caller must not modify the returned map.
     */
    ConcurrentHashMap<String, RoomContext> getRooms() {
        return rooms;
    }

    /** Current number of rooms in the routing table (including empty rooms). */
    public int getActiveRoomCount() {
        return rooms.size();
    }
}
