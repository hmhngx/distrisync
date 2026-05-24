# DistriSync — Distributed Collaborative Whiteboard

**Engineered by:** Harrison Nguyen

![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)
![JavaFX](https://img.shields.io/badge/JavaFX-21.0.4-blue?logo=java&logoColor=white)
![Maven](https://img.shields.io/badge/Maven-3.x-C71A36?logo=apachemaven&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-green)

---

## Project Overview

DistriSync is a peer-class distributed collaborative whiteboard built entirely on a custom Java NIO server — no third-party real-time framework. The server runs a single-threaded `java.nio.channels.Selector` event loop that accepts connections, multiplexes reads and writes, and fans out shape mutations to all connected clients with zero blocking I/O on the hot path. Accepted `SocketChannel` instances are configured with `TCP_NODELAY = true` and 64 KiB socket buffers to minimise Nagle-induced latency, enabling sub-frame delivery of stroke data across a local network. A length-prefixed binary frame (1-byte type tag + 4-byte big-endian payload length + UTF-8 JSON body) with a 16 MiB hard ceiling governs every exchange between server and clients, providing deterministic parse complexity regardless of payload size.

The client UI is built on JavaFX 21 and styled with a Tailwind-inspired `styles.css` design system — a flat, neutral-toned control panel with uniform spacing and hover states. A layered canvas architecture (base paint layer → remote transient layer → local transient layer → cursor overlay → control pane) ensures that in-progress strokes from remote peers are rendered live without flickering or compositing artefacts. A parallel UDP multicast channel (`239.255.42.42:9292`) carries ephemeral pointer positions at 20 Hz, giving all peers real-time cursor presence without touching the TCP state machine. Shared canvas authority is implemented via last-writer-wins using Lamport-ish per-shape timestamps enforced atomically inside a `ConcurrentHashMap`, so concurrent edits converge without a central lock.

**Workspace Boards:** Rooms now support multiple isolated boards within a single shared space. Each board maintains its own independent `CanvasStateManager` and Write-Ahead Log (`{roomId}_{boardId}.wal`), enabling zero-overhead board creation and persistent recovery. Clients receive a `BOARD_LIST_UPDATE` on join and whenever a new board is created or removed; the `SWITCH_BOARD` message allows in-room navigation between boards. Hosts with `PERM_MANAGE_ROOM` may delete non-default boards via `DELETE_BOARD` (`0x20`); the server tombstones the board, deletes its WAL files, and broadcasts `BOARD_DELETED` (`0x21`) so all clients refresh the Task View switcher. Shape mutations are scoped to the active board and broadcast only to connected peers viewing the same board, preventing cross-board interference. A Figma-style board switcher with live thumbnails and per-card delete controls facilitates rapid board discovery and administration.

**Horizontal scaling:** Multiple `NioServer` JVMs can share room state through an optional **Redis Pub/Sub backplane** (Lettuce). After a local WAL commit, mutations are published as `BackplaneEnvelope` records on `distrisync:room:{roomId}`; peer nodes apply them idempotently via `BackplaneEventDedup` and fan out to local TCP clients. Ephemeral cursor positions use `CURSOR_SYNC` on TCP and a dedicated Redis presence channel (`distrisync:room:{roomId}:presence`) for cross-node relay. Room-level moderation and board-presence events use a third **control** channel (`distrisync:room:{roomId}:control`). Cold nodes request hydration with `STATE_REQUEST` / `STATE_SNAPSHOT` backplane events. Configure via `REDIS_HOST` + `REDIS_PORT` or `DISTRISYNC_REDIS_URI`, and `NODE_ID` / `DISTRISYNC_NODE_ID`.

**Room governance:** Each room has a **host** (`RoomContext.hostClientId`) with full `RoomPermissions.OWNER` capabilities. Permissions are a zero-allocation `int` bitmask (`PERM_DRAW`, `PERM_SPEAK`, `PERM_MANAGE_USERS`, `PERM_DELETE_ROOM`, `PERM_MANAGE_ROOM`, `PERM_MANAGE_MEDIA`). The server enforces RBAC on the NIO hot path (drawing, UDP audio relay, moderation, board-lock toggles, board deletion, room-global media control). When the host disconnects, `selectHostCandidate` elects the oldest connected session (lexicographic `clientId` tie-break) and broadcasts `ROLE_UPDATE` to all peers. Moderators with `PERM_MANAGE_USERS` can **KICK**, **REVOKE_SPEAK**, or **GRANT_SPEAK** via `MODERATION_ACTION`; kicked clients receive `SESSION_REVOKED` and return to the lobby.

**Watch Party (room-global YouTube sync):** Rooms can run a synchronized YouTube session independent of the active whiteboard. The server holds one authoritative `MediaStatePayload` per room (`RoomContext.currentMediaState`) and fans out `MEDIA_STATE_UPDATE` (`0x22`) after each `MEDIA_CONTROL` (`0x23`). Playback position uses a server-anchored clock: while `state` is `PLAYING`, expected time is `mediaTimeSeconds + (now - serverEpochMs) / 1000`. Clients with `PERM_MANAGE_MEDIA` drive **LOAD**, **PLAY**, **PAUSE**, **SEEK**, and **STOP**; everyone else follows the snapshot. `YoutubePlayerNode` (JavaFX `WebView` + IFrame API, `javafx-web`) renders video with drift correction; `WhiteboardApp` mounts it on a draggable spatial overlay with a **Start Watch Party** toolbar control.

---

## Core Features (Implemented)

- **Custom Java NIO Server** — single-threaded `Selector` loop; non-blocking `SocketChannel` per client; write queue with `OP_WRITE` only when the send buffer saturates; `TCP_NODELAY` + 64 KiB `SO_SNDBUF`/`SO_RCVBUF` for low-latency streaming state.

- **Binary Length-Prefixed Protocol** — `MessageCodec` frames every message as `[type: 1B][length: 4B big-endian][UTF-8 JSON payload]`; partial-read detection via `PartialMessageException` with buffer compaction; max payload 16 MiB.

- **Sealed Shape Hierarchy** — `Shape` is a Java 21 sealed interface permitting `Line`, `Circle`, `RectangleNode`, `EllipseNode`, `ArrowNode`, `TextNode`, and `EraserPath` (legacy render-only). Each record carries `objectId` (UUID), Lamport `timestamp`, `color`, `strokeWidth`, `authorName`, and `clientId`. Server-side `ShapeCodec` and a mirrored client `ClientShapeCodec` deserialise via a `"_type"` discriminator field with Gson. The tool dock exposes **LINE**, **CIRCLE**, **RECTANGLE**, **ELLIPSE**, **ARROW**, **FREEHAND**, **ERASER**, and **TEXT**.

- **Live Collaborative Drawing (`SHAPE_START` / `SHAPE_UPDATE` / `SHAPE_COMMIT`)** — streaming stroke events are relayed to all peers by the server without persistence; the receiving client renders a `TransientShapeEntry` on the remote transient canvas layer, providing smooth live-ink preview before commit. On relay, the server injects `clientId` from the authenticated TCP session into `SHAPE_START` payloads so peers can resolve attribution from roster state.

- **MS Paint–Style Text Tool** — clicking the canvas in `TEXT` mode opens a floating `TextField` directly on the control pane. Keystrokes are throttled and transmitted as `TEXT_UPDATE` frames (~50 ms interval); peers render a ghost `VBox` with a live caret (`▏`) on their cursor pane. Pressing Enter commits a `TextNode` via `MUTATION` + `SHAPE_COMMIT`, which dismisses all ghost previews.

- **Object Eraser with Spatial Hit-Testing** — the **ERASER** tool deletes committed shapes by geometry, not white-stroke masking. `EraserSpatialIntersection` queries a client-side `SpatialHashGrid` (200 px uniform cells, keyed from each shape's AABB) so only shapes in the brush cell are considered; `ShapeSpatialQuery` then applies precise intersection for `CIRCLE` or `SQUARE` brush geometry from `EraserType`. The topmost non-`EraserPath` shape by Lamport `timestamp` wins and is removed locally via `UNDO_REQUEST` → server `SHAPE_DELETE`. The grid is kept in sync on snapshot, mutation, and delete paths in `WhiteboardApp`. `EraserCursorFactory` supplies a brush-sized custom cursor; circle/square mode is toggled through `GlobalCanvasContext.activeEraserTypeProperty()`.

- **Freehand `MUTATION_BATCH` & Chunked Snapshots** — **FREEHAND** commits stroke segments as one or few `MUTATION_BATCH` (`0x24`) frames (max 20 shapes / 48 KiB UTF-8 payload per frame) instead of per-segment `MUTATION` storms. On join and board switch, the server sends `SNAPSHOT` `[]`, streams board state as `MUTATION_BATCH` frames, then `SNAPSHOT_END` (`0x25`); `NetworkClient` buffers batches until `SNAPSHOT_END` before delivering a single snapshot to the UI. WAL replay, compaction, and Redis backplane fan-out use the same batch format.

- **Incremental Base-Canvas Painting** — after hydration, committed shapes are appended with `paintShapeOnBaseCanvas` (per-shape draw) instead of full-layer clears on every `MUTATION` / `MUTATION_BATCH`, keeping large boards responsive. Full `redrawBaseCanvas` still runs on scoped clears, deletes, and board switches.

- **UDP Audio Relay Rate Limiting** — each `ClientSession` owns a token bucket (burst 50, refill 1 token / 20 ms ≈ 50 pkt/s) checked in `NioServer.handleUdpRead` before room fan-out; excess push-to-talk packets are dropped silently to bound amplification.

- **Scoped `CLEAR_USER_SHAPES`** — the "Clear Board" button sends a `CLEAR_USER_SHAPES` frame carrying only the issuing client's `clientId`. The server calls `CanvasStateManager.clearUserShapes(clientId)` and broadcasts the scoped clear to peers, who purge only that owner's shapes. Members may clear only their own shapes; moderators with `PERM_MANAGE_USERS` may clear any participant's work.

- **Undo (`UNDO_REQUEST` / `SHAPE_DELETE`)** — the client sends an `UNDO_REQUEST`; the server verifies the stored shape's `clientId` matches the session (or the sender has `PERM_MANAGE_USERS`), then calls `deleteShape` and broadcasts `SHAPE_DELETE` so all peers remove the shape atomically. Same ownership gate applies to client-originated `SHAPE_DELETE`.

- **Server-Side Object Authority (`ShapeAuthority`)** — on `MUTATION` / `MUTATION_BATCH`, the server overwrites `Shape.clientId` with the authenticated session id before apply, WAL append, and fan-out (unless the sender has `PERM_MANAGE_USERS`). Prevents forged attribution and cross-user delete/clear IDOR. `CanvasStateManager.getShape` supports ownership checks on delete paths.

- **Dead-Session Read Short-Circuit** — `ClientSession.severed` is set on KICK / `SESSION_REVOKED` / `closeKey`; `NioServer.handleRead` stops decoding further frames in the same TCP read batch so ghost frames cannot mutate state after teardown.

- **Figma-Style Live Attribution** — in-progress remote strokes display a floating label at the stroke tip from `TransientShapeEntry.clientId`, resolved via `ParticipantManager` (`resolveDisplayName`). Committed shapes show the same roster name in a hover tooltip from `findShapeAt` + `ownerTooltip`. Live text ghosts and `CURSOR_SYNC` cursors use the same resolver.

- **UDP Multicast Cursor Presence** — `UdpPointerTracker` joins multicast group `239.255.42.42:9292`; pointer positions are transmitted at 20 Hz (only on mouse move). Each peer is represented by a deterministically coloured dot + name badge on a dedicated `cursorPane` with fade-out removal on timeout.

- **Pointer State Management** — `PointerStateManager` maintains a `ConcurrentHashMap<String, PointerState>` of active remote cursors with a clock-injectable eviction model. `PointerState` is an immutable record (`clientId`, `x`, `y`, `lastUpdatedAt`). Entries older than 500 ms are removed by `evictStalePointers()`, which is driven by the JavaFX `AnimationTimer` render loop, keeping cursor overlays consistent with actual presence.

- **Session Multiplexing (Rooms & Boards)** — `RoomManager` maintains a `ConcurrentHashMap<String, RoomContext>` of isolated rooms; each `RoomContext` hosts a `ConcurrentHashMap<String, CanvasStateManager>` of independent boards (created lazily per board ID on first access). Rooms are created on first client join and persist even when empty, allowing clients to rejoin and find their original canvas state. Within each room, clients switch boards via `SWITCH_BOARD`, triggering a fresh `SNAPSHOT` and optional `BOARD_LIST_UPDATE`. `NioServer` routes all mutations, live strokes, and relayed frames to peers on the same board (not just the room), providing true board-level isolation.

- **Write-Ahead Log (WAL) with Crash Recovery** — `WalManager` appends every accepted state-mutating frame (`MUTATION`, `MUTATION_BATCH`, `SHAPE_DELETE`, `CLEAR_USER_SHAPES`) to a per-room, per-board `{roomId}_{boardId}.wal` file under `distrisync-data/` using `FileChannel` in `APPEND` mode. Boards are replayed lazily when first opened via `RoomContext.getBoard(boardId)`: frames are decoded sequentially from a heap `ByteBuffer`; a truncated tail frame — the typical artefact of a mid-write crash — is silently tolerated and recovery stops at the corrupt offset, returning the clean prefix. Room and board identifiers are sanitised (`[^a-zA-Z0-9_\-]` → `_`) before use as filenames to prevent path traversal.

- **Last-Writer-Wins Convergence** — `CanvasStateManager` uses `ConcurrentHashMap.compute` with a strictly-greater-timestamp guard, so concurrent mutations from multiple peers converge without server-side locking or operational transforms.

- **Reconnect with Back-off** — `NetworkClient` reconnects automatically after EOF or I/O errors using a synchronised `reconnect()` cycle that re-executes the full `HANDSHAKE` → `SNAPSHOT` flow to restore canvas state. Moderation **KICK** is different: `SESSION_REVOKED` calls `suppressAutoReconnect()`, sets `running = false`, closes the TCP channel, and resets workspace state to the lobby without re-joining the evicted room. `resumeAfterSessionRevoked()` re-enables auto-reconnect, opens a fresh TCP session, restarts read/write daemons if they exited, sends `FETCH_LOBBY`, and leaves the client in the lobby ready to pick another room (`NetworkClientStateTest`).

- **Lobby Discovery, Pull Refresh, and Durable Room Deletion (`LOBBY_STATE` / `FETCH_LOBBY` / `DELETE_ROOM` / `ROOM_DELETED`)** — lobby discovery now supports both push (`LOBBY_STATE` fan-out) and pull (`FETCH_LOBBY`) refresh flows so clients can force an immediate room list update. `LOBBY_STATE` merges in-memory active rooms with persisted WAL room stems (showing `userCount = 0` when no users are connected), so durable rooms remain discoverable after restart. Admin-style room teardown is now first-class: `DELETE_ROOM` removes the room from routing, deletes all matching WAL files, emits `ROOM_DELETED` to connected occupants (who are moved back to lobby), and then broadcasts a fresh lobby snapshot.

- **Push-to-Talk Voice Chat (`AudioEngine` / `UDP_ADMISSION`)** — `AudioEngine` implements a UDP audio data plane using `javax.sound.sampled` at 8 kHz / 16-bit signed PCM / mono / big-endian (`AUDIO_FORMAT`). Each 10 ms capture frame produces 160 bytes of PCM (`PAYLOAD_SIZE`). Wire datagrams are 196 bytes: a 36-byte null-padded UTF-8 identity token followed by the 160-byte PCM payload. Before audio can flow, the server sends a `UDP_ADMISSION` frame on the TCP channel carrying a `udpToken`; `AudioEngine.onUdpAdmission()` opens a connected `DatagramSocket`, sends a 36-byte registration punch packet, and starts the receive daemon. A dedicated capture thread (`distrisync-audio-capture`) runs at `MAX_PRIORITY`; a permanent receive daemon (`distrisync-audio-recv`) plays back incoming PCM via a lazily opened `SourceDataLine` with a 1 600-byte hardware buffer. The `UserSpeakingListener` functional interface fires on each received packet so the UI can highlight the active speaker.

- **PING/PONG RTT Telemetry** — after `HANDSHAKE` completion, `NetworkClient` starts a daemon thread (`distrisync-ping`) that fires a `PING` frame every 5 000 ms (`HEARTBEAT_PING_INTERVAL_MS`). Each `PING` payload is `{ "t": <originMillis> }` encoded via `MessageCodec.PingPongPayload`. The server echoes the origin timestamp unchanged in a `PONG` response. On receipt, `applyPingRtt(originTimestamp)` records each sample and updates `SimpleLongProperty ping` on the JavaFX Application Thread with the rolling average of the last 10 RTT measurements (initial value `-1` before first measurement). `ingestPongForTelemetryTest(Message)` is a package-private test hook that drives the full RTT path without a live TCP connection.

- **Server-Side Traffic Metrics Heartbeat** — `NioServer` maintains two lock-free counters: `AtomicLong bytesRouted` accumulates the total octets delivered across all TCP board fan-out writes and UDP audio relay sends (one increment per recipient per frame); `AtomicInteger activeTcpSockets` is incremented on each `OP_ACCEPT` and decremented on each channel close, providing a live socket gauge. A `ScheduledExecutorService` on the `distrisync-traffic-metrics` thread emits a structured `[METRICS]` log line every 10 seconds via `emitTrafficHeartbeat()`, recording `bytesRouted`, `roomManager.getActiveRoomCount()`, and `activeTcpSockets`. No external metrics library is required; Logback with the Jansi ANSI console appender provides coloured, human-readable output.

- **Performance HUD** — bottom-right overlay (CSS `.telemetry-hud` + `.telemetry-pill`) shows `Ping: Nms | FPS: N`, refreshed every second. Ping uses `NetworkClient.pingProperty()` (rolling average of the last 10 PONGs). FPS is sampled by a JavaFX `AnimationTimer` on the canvas scene; timers start when the workspace is shown and stop on `leaveCanvasRoom`.

- **Collapsible Tools Drawer (`ToolsDrawerToggleModel`)** — drawer open/close state is extracted from `WhiteboardApp` into a pure `ToolsDrawerToggleModel`, making animation geometry (slide target X, chevron labels, panel translate X) fully unit-testable without a JavaFX runtime. The `ToggleRestSnapshot` record captures the complete settled UI state after a toggle for deterministic assertions in `ToolsDrawerToggleModelTest`.

- **Idle Room Eviction & Storage Lifecycle** — `StorageLifecycleManager` is a background daemon that sweeps every 60 seconds, evicting rooms with zero active clients that have been idle longer than 5 minutes. Evicted rooms are removed from the in-memory `RoomManager` registry to reclaim heap; all per-board WAL files are preserved on disk for manual recovery. Connected clients are never interrupted regardless of inactivity, and new boards are created on-demand with no eviction overhead.

- **Global Canvas Context & Shape Factory** — `GlobalCanvasContext` centralises active stroke colour (`ObjectProperty<Color>`), stroke width (`DoubleProperty`), and eraser brush geometry (`EraserType` circle vs square). `GlobalCanvasShapeFactory` commits `Line`, `Circle`, `RectangleNode`, `EllipseNode`, `ArrowNode`, and `TextNode` instances using the current context, keeping the properties bar and network payloads consistent.

- **Room RBAC (`RoomPermissions`)** — bitmask permissions on `ClientSession` and `Participant`. `OWNER` grants draw, speak, manage users, delete room, manage room settings, and manage media; `MEMBER` grants draw + speak; `SPECTATOR` is lobby-only. Server rejects `MUTATION` / `MUTATION_BATCH` / live strokes / `TEXT_UPDATE` without `PERM_DRAW`, UDP audio without `PERM_SPEAK`, `MODERATION_ACTION` without `PERM_MANAGE_USERS`, `TOGGLE_BOARD_LOCK` / `DELETE_BOARD` without `PERM_MANAGE_ROOM`, and `MEDIA_CONTROL` without `PERM_MANAGE_MEDIA`. Invalid `MEDIA_CONTROL` actions are logged and dropped without fan-out.

- **Host Election & `ROLE_UPDATE`** — first joiner becomes host with `OWNER` permissions. On host disconnect, `NioServer.selectHostCandidate` promotes the longest-connected peer and fans out `ROLE_UPDATE` (`0x1D`, `{ newHostClientId, newPermissions, roomHostClientId? }`) so every client updates crowns and local capability flags.

- **Moderation (`MODERATION_ACTION` / `SESSION_REVOKED`)** — hosts and moderators send `MODERATION_ACTION` (`0x1B`) with `actionType` `KICK`, `REVOKE_SPEAK`, or `GRANT_SPEAK`. Commands publish on the Redis control channel in cluster mode. **KICK** sets `ClientSession.severed`, sends `SESSION_REVOKED` (`0x1C`, `{ reason }`), then disconnects and broadcasts peer `LEAVE_ROOM`; the victim client stops I/O threads, clears room/board state, and shows a lobby toast. **REVOKE_SPEAK** / **GRANT_SPEAK** toggle `PERM_SPEAK` and push `ROLE_UPDATE` to the affected client; `AudioEngine` hard-stops capture when speak is revoked.

- **Collaboration Roster (`CollaborationRoster`)** — slide-out panel grouped by active board (`Participant.currentBoardId`), host crown indicators, hardware-mute slash icon, and a blue **speaker** SVG when `Participant.isSpeaking` (replacing the prior avatar ring). Context menu (Kick / Revoke Speak / Grant Speak) when `PERM_MANAGE_USERS` is held. Integrates `ParticipantManager`, `RoomState`, and `ToastNotification` for moderation feedback. `ParticipantListView` remains for compact HUD rows; `wireCollaborationRoster()` is the primary in-room presence UI.

- **Board Presence, Lock & Deletion** — when a peer switches boards, the server broadcasts `BOARD_SWITCH` (`0x1E`, `{ clientId, newBoardId }`) room-wide so the roster regroups without polling. Hosts toggle `RoomContext.boardCreationLocked` via `TOGGLE_BOARD_LOCK` (`0x1F`, `{ locked }`); members cannot create new boards while locked and fall back to an existing board on join. **`DELETE_BOARD` / `BOARD_DELETED` (`0x20` / `0x21`):** room managers send a JSON string `boardId` (not `Board-1`); `RoomContext` tombstones the id, `WalManager.deleteBoardFiles` removes `{roomId}_{boardId}.wal`, occupants on the deleted board are moved to `Board-1` with a fresh `SNAPSHOT`, and all clients receive `BOARD_DELETED` to prune thumbnails and show a toast. The Task View switcher exposes a trash control when `PERM_MANAGE_ROOM` is held.

- **Shared UI Effects (`UiEffects`)** — `toolbarDropShadow()` centralises JavaFX `DropShadow` elevation for the tool dock, properties bar, and `CollaborationRoster` (replacing CSS box-shadow on those nodes for consistent depth).

- **Watch Party & YouTube Sync (`YoutubePlayerNode` / `MEDIA_CONTROL`)** — room-global media state in `RoomContext.currentMediaState`. Hosts send `MEDIA_CONTROL` (`PLAY`, `PAUSE`, `SEEK`, `LOAD`, `STOP`); `MessageCodec.deriveMediaState` computes the next snapshot with `serverEpochMs`. `MEDIA_STATE_UPDATE` is broadcast room-wide (and on the Redis **control** channel in cluster mode); joiners receive the current snapshot after `JOIN_ROOM`. `YoutubePlayerNode` hosts the YouTube IFrame API in a local `HttpServer`-served page (avoids `file://` restrictions), buffers early `MEDIA_STATE_UPDATE` frames until the `WebView` load succeeds, then applies server state with drift sync (green / amber / orange indicator). Transport UI is gated to `PERM_MANAGE_MEDIA`; `dispose()` stops local playback, loads `about:blank`, and clears pending state before overlay removal. Members can hide the player locally without stopping the session; **STOP** clears room media for everyone. `MediaStateListener` bridges `NetworkClient` to `WhiteboardApp`.

- **Per-Room Display Names (`HANDSHAKE` + `JOIN_ROOM`)** — `HANDSHAKE` carries only `{ clientId }` (stable session id into the lobby). Room identity is declared on `JOIN_ROOM` as `{ roomId, displayName, initialBoardId? }`. The server sanitizes names (strip, max **20** chars, fallback to an 8-char `clientId` prefix when blank) and deduplicates collisions in-room by appending ` #XXXX`. The joiner receives a self `JOIN_ROOM` `{ clientId, authorName }` with the **confirmed** name; `NetworkClient` stores it in `lockedRoomName` for reconnect and outbound `SHAPE_START` / `TEXT_UPDATE` attribution. Legacy `authorName` in `HANDSHAKE` JSON is ignored.

- **Lobby Display-Name UX** — the login screen only connects TCP (`Join Network`); the lobby modal has a validated **Display name** field (`lobby-display-name-field`) with green/red border feedback. Create/join room actions stay disabled until a non-blank name is entered (`WhiteboardAppLobbyDisplayNameTest`).

- **Bidirectional Room Membership (`JOIN_ROOM` / `LEAVE_ROOM`)** — client→server `JOIN_ROOM` carries `{ roomId, displayName, initialBoardId? }`; server→peer `JOIN_ROOM` notifies `{ clientId, authorName }` (server-confirmed display name). Client→server `LEAVE_ROOM` is empty and clears `lockedRoomName`; server→peer `LEAVE_ROOM` carries the departing `clientId` string (including moderation kicks and disconnects).

- **Canvas Viewport Resize Coalescing** — `CanvasViewportResizeHandler` listens to the canvas container's width/height properties and coalesces invalidations into a single `Platform.runLater` redraw, avoiding redundant full-canvas repaints when JavaFX resizes the `Canvas` surface (which would otherwise clear pixel buffers at 0×0 during layout).

- **Properties Bar & Floating Tool Chrome** — `styles.css` defines a Tailwind-inspired properties bar (colour picker, stroke slider, eraser-type toggles), floating tool-dock layout, and board-switcher card/trash styles (`.board-switcher-trash-btn`). `UiEffects.toolbarDropShadow()` applies shared elevation in code. Extensive `WhiteboardApp*` TestFX/Mockito tests (`WhiteboardAppPropertiesBarTest`, `WhiteboardAppToolDockTest`, `WhiteboardAppFloatingLayoutTest`, etc.) lock layout IDs and interaction contracts without a manual QA pass.

- **Redis Backplane (Multi-Node Fan-Out)** — when `ServerEnvironment.resolveRedisUri()` is set, `WhiteboardServer` starts `RedisBackplanePublisher` (async worker pool, never blocks the selector) and `RedisBackplaneSubscriber` (mailbox + `selector.wakeup()`). Each envelope carries `eventId`, `originNodeId`, `roomId`, `boardId`, and a full wire frame. Three channels per room: mutations (`distrisync:room:{roomId}`), presence (`:presence` for `CURSOR_SYNC`), and control (`:control` for `MODERATION_ACTION`, `BOARD_SWITCH`, `TOGGLE_BOARD_LOCK`, `MEDIA_STATE_UPDATE`). `BackplaneEventDedup` (10-minute TTL, 50k cap) prevents double-apply on the originating node and on redelivery. `RoomManager` subscribes all channels when a room is first created locally.

- **TCP Multiplayer Cursors (`CURSOR_SYNC` / `RemoteCursorManager`)** — clients publish cursor position over TCP at ~15 Hz (`CURSOR_SYNC_MIN_INTERVAL_MS = 66`). `RemoteCursorManager` renders peer cursors on the FX thread with LERP (`LERP_FACTOR = 0.3`), 2 s stale timeout, and 250 ms fade-out. `CursorSyncListener` bridges `NetworkClient` to the manager; cross-node peers receive the same frames via the Redis presence channel.

- **Per-Session Write Backpressure** — `ClientSession.enqueue(frame, OutboundClass)` caps the outbound queue at **1024** frames. `OutboundClass.EPHEMERAL` frames (PONG, lobby fan-out, live stroke updates, `CURSOR_SYNC`) are **dropped** when full; `OutboundClass.CRITICAL` triggers `OVERFLOW_DISCONNECT` to protect server memory. Drops increment `ServerMetrics.FRAMES_DROPPED_TOTAL`.

- **Prometheus Metrics Endpoint** — `NioServer` exposes `GET /metrics` on `METRICS_PORT` (default **8080**, bind retry on port conflict) returning `distrisync_frames_sent_total`, `distrisync_frames_dropped_total`, `distrisync_redis_messages_published`, and `distrisync_redis_messages_received`. Complements the existing 10 s `[METRICS]` Logback heartbeat.

- **Load & Chaos Tooling (`com.distrisync.tools`)** — `BotSwarm` is a headless TCP load generator for horizontally scaled nodes (`serverIp port botCount roomId`). `SlowConsumerChaosTest` exercises slow-client backpressure behaviour against a live server.

---

## Project Structure

```
distrisync/
├── src/
│   ├── main/
│   │   ├── java/com/distrisync/
│   │   │   ├── client/              # JavaFX UI, TCP client, audio, roster, watch party
│   │   │   │   ├── WhiteboardApp.java
│   │   │   │   ├── NetworkClient.java
│   │   │   │   ├── WhiteboardClient.java
│   │   │   │   ├── UdpPointerTracker.java
│   │   │   │   ├── CanvasUpdateListener.java
│   │   │   │   ├── LobbyUpdateListener.java
│   │   │   │   ├── RoomInfo.java
│   │   │   │   ├── PointerState.java
│   │   │   │   ├── PointerStateManager.java
│   │   │   │   ├── AudioEngine.java
│   │   │   │   ├── UserSpeakingListener.java
│   │   │   │   ├── VoiceStateListener.java
│   │   │   │   ├── Participant.java
│   │   │   │   ├── ParticipantManager.java
│   │   │   │   ├── ParticipantListView.java
│   │   │   │   ├── CollaborationRoster.java
│   │   │   │   ├── RoomState.java
│   │   │   │   ├── ToastNotification.java
│   │   │   │   ├── RoomMembershipListener.java
│   │   │   │   ├── RoleUpdateListener.java
│   │   │   │   ├── SessionRevokedListener.java
│   │   │   │   ├── BoardPresenceListener.java
│   │   │   │   ├── BoardDeletedListener.java
│   │   │   │   ├── MediaStateListener.java
│   │   │   │   ├── YoutubePlayerNode.java
│   │   │   │   ├── UiEffects.java
│   │   │   │   ├── GlobalCanvasContext.java
│   │   │   │   ├── GlobalCanvasShapeFactory.java
│   │   │   │   ├── EraserType.java
│   │   │   │   ├── EraserSpatialIntersection.java
│   │   │   │   ├── SpatialHashGrid.java
│   │   │   │   ├── EraserCursorFactory.java
│   │   │   │   ├── ShapeSpatialQuery.java
│   │   │   │   ├── ShapeMathUtils.java
│   │   │   │   ├── CanvasViewportResizeHandler.java
│   │   │   │   ├── RemoteCursorManager.java
│   │   │   │   ├── CursorSyncListener.java
│   │   │   │   └── ToolsDrawerToggleModel.java
│   │   │   ├── server/              # NIO server, room routing, WAL, metrics, backplane
│   │   │   │   ├── WhiteboardServer.java
│   │   │   │   ├── NioServer.java
│   │   │   │   ├── ServerEnvironment.java
│   │   │   │   ├── ServerMetrics.java
│   │   │   │   ├── ClientSession.java
│   │   │   │   ├── EnqueueResult.java
│   │   │   │   ├── OutboundClass.java
│   │   │   │   ├── OutboundFrameHook.java
│   │   │   │   ├── RoomManager.java
│   │   │   │   ├── RoomContext.java
│   │   │   │   ├── StorageLifecycleManager.java
│   │   │   │   ├── WalManager.java
│   │   │   │   ├── CanvasStateManager.java
│   │   │   │   ├── ShapeCodec.java
│   │   │   │   ├── ShapeAuthority.java
│   │   │   │   └── backplane/
│   │   │   │       ├── BackplaneEnvelope.java
│   │   │   │       ├── BackplaneEnvelopeCodec.java
│   │   │   │       ├── BackplaneEventDedup.java
│   │   │   │       ├── RedisBackplanePublisher.java
│   │   │   │       └── RedisBackplaneSubscriber.java
│   │   │   ├── tools/               # Headless load / chaos utilities
│   │   │   │   ├── BotSwarm.java
│   │   │   │   └── SlowConsumerChaosTest.java
│   │   │   ├── protocol/            # Binary framing, message types, RBAC bitmasks
│   │   │   │   ├── MessageCodec.java
│   │   │   │   ├── MessageType.java
│   │   │   │   ├── RoomPermissions.java
│   │   │   │   ├── Message.java
│   │   │   │   └── PartialMessageException.java
│   │   │   └── model/               # Sealed shape hierarchy
│   │   │       ├── Shape.java
│   │   │       ├── Line.java
│   │   │       ├── Circle.java
│   │   │       ├── RectangleNode.java
│   │   │       ├── EllipseNode.java
│   │   │       ├── ArrowNode.java
│   │   │       ├── TextNode.java
│   │   │       └── EraserPath.java
│   │   └── resources/
│   │       ├── styles.css
│   │       └── logback.xml
│   └── test/
│       └── java/com/distrisync/
│           ├── client/
│           │   ├── AudioEngineTest.java
│           │   ├── CanvasViewportResizeHandlerTest.java
│           │   ├── EraserSpatialIntersectionTest.java
│           │   ├── SpatialHashGridTest.java
│           │   ├── NetworkClientTelemetryTest.java
│           │   ├── NetworkClientStateTest.java
│           │   ├── WhiteboardAppLobbyDisplayNameTest.java
│           │   ├── ParticipantManagerTest.java
│           │   ├── CollaborationRosterModerationTest.java
│           │   ├── PointerStateTrackerTest.java
│           │   ├── RemoteCursorManagerTest.java
│           │   ├── YoutubePlayerNodeTest.java
│           │   ├── ShapeMathUtilsTest.java
│           │   ├── ToolsDrawerToggleModelTest.java
│           │   ├── WhiteboardAppTestFxSupport.java
│           │   └── WhiteboardApp*Test.java   # layout, tool dock, properties bar, PTT, HUD, …
│           ├── integration/
│           │   ├── ClientServerIntegrationTest.java
│           │   └── VoiceStateBroadcastTest.java
│           ├── protocol/
│           │   ├── MessageCodecTest.java
│           │   ├── MessageCodecModerationTest.java
│           │   └── RoomPermissionsTest.java
│           ├── resources/mockito-extensions/
│           └── server/
│               ├── backplane/          # Redis publisher/subscriber tests
│               ├── CanvasStateManagerTest.java
│               ├── ClientSessionBackpressureTest.java
│               ├── ClientSessionUdpRateLimitTest.java
│               ├── MetricsHttpServerTest.java
│               ├── NioServerChunkedSnapshotTest.java
│               ├── NioServerDeadSessionReadTest.java
│               ├── NioServerObjectAuthorityTest.java
│               ├── NioServerDistributedHydrationTest.java
│               ├── NioServerRemoteAuthorityTest.java
│               ├── NioServerRemoteMailboxTest.java
│               ├── NioServerRbacTest.java
│               ├── NioServerModerationKickTest.java
│               ├── NioServerModerationRevokeSpeakTest.java
│               ├── NioServerModerationGrantSpeakTest.java
│               ├── NioServerHostMigrationTest.java
│               ├── NioServerJoinRoleUpdateTest.java
│               ├── NioServerRoomMembershipBroadcastTest.java
│               ├── NioServerBoardPresenceTest.java
│               ├── NioServerBoardLockTest.java
│               ├── NioServerMediaStateTest.java
│               ├── HostElectionTest.java
│               ├── RoomContextClientIndexTest.java
│               ├── NioServerTest.java
│               ├── NioServerLifecycleTest.java
│               ├── NioServerUdpRoutingBufferTest.java
│               ├── RoomContextTest.java
│               ├── RoomManagerTest.java
│               ├── ServerEnvironmentTest.java
│               ├── ServerMetricsFlushTest.java
│               ├── ServerMetricsTest.java
│               ├── ShapeCodecNewShapesTest.java
│               ├── ShapeCodecMutationBatchTest.java
│               ├── ShapeAuthorityTest.java
│               └── WalManagerTest.java
├── docs/
│   └── Architecture.md
├── Dockerfile                 # Multi-stage backend image (Maven → Temurin 21 JRE)
├── docker-compose.yml         # Single-node: TCP+UDP 9090, WAL volume
├── docker-compose.cluster.yml # Redis + node-a (9090) + node-b (9091)
└── pom.xml
```

---

## Wire Protocol Reference

| `MessageType` | Byte | Direction | Persisted to WAL | Description |
|---|---|---|---|---|
| `HANDSHAKE` | `0x01` | C → S | No | Session identification (`clientId` only); places client in lobby. Legacy `authorName` in JSON is ignored |
| `SNAPSHOT` | `0x02` | S → C | No | Join/board-switch hydration header (`[]`) or legacy full state; chunked joins stream shapes via `MUTATION_BATCH` until `SNAPSHOT_END` |
| `MUTATION` | `0x03` | C ↔ S | **Yes** | Committed shape add/update; broadcast to board peers |
| `UDP_POINTER` | `0x04` | UDP only | No | Ephemeral cursor position (multicast, not TCP) |
| `SHAPE_START` | `0x05` | C ↔ S | No | Begin live stroke; server relays with session `clientId` injected; not persisted |
| `SHAPE_UPDATE` | `0x06` | C ↔ S | No | Incremental stroke points; relayed to board peers, not persisted |
| `SHAPE_COMMIT` | `0x07` | C ↔ S | No | Finalise live stroke; peer dismisses transient layer |
| `CLEAR_USER_SHAPES` | `0x08` | C ↔ S | **Yes** | Remove shapes for `targetClientId`; members may only clear self unless `PERM_MANAGE_USERS` |
| `UNDO_REQUEST` | `0x09` | C → S | No | Delete shape by UUID; allowed only for own shapes unless `PERM_MANAGE_USERS` |
| `SHAPE_DELETE` | `0x0A` | S → C | **Yes** | Broadcast shape removal after authorized undo/delete |
| `TEXT_UPDATE` | `0x0B` | C ↔ S | No | Live text ghost preview; requires `PERM_DRAW`; relayed to board peers, not persisted |
| `LOBBY_STATE` | `0x0C` | S → C | No | JSON array of `{ roomId, userCount }` for room discovery |
| `JOIN_ROOM` | `0x0D` | C ↔ S | No | **C→S:** `{ roomId, displayName, initialBoardId? }` (legacy string roomId accepted). **S→joiner + peers:** `{ clientId, authorName }` with server-sanitized, deduplicated display name |
| `LEAVE_ROOM` | `0x0E` | C ↔ S | No | **C→S:** return to lobby (empty). **S→peers:** JSON string `clientId` when a member leaves or is kicked |
| `SWITCH_BOARD` | `0x0F` | C → S | No | JSON string target board id; server responds with `SNAPSHOT` |
| `BOARD_LIST_UPDATE` | `0x10` | S → C | No | JSON array of board id strings actively in use within the room |
| `UDP_ADMISSION` | `0x11` | S → C | No | JSON object `{ udpToken }` granting access to the UDP audio data plane; client calls `AudioEngine.onUdpAdmission()` on receipt |
| `PING` | `0x12` | C → S | No | JSON object `{ "t": <originMillis> }` sent every 5 000 ms by `distrisync-ping` thread; server must be post-handshake |
| `PONG` | `0x13` | S → C | No | JSON object `{ "t": <originMillis> }` — server echoes the origin timestamp unchanged; client computes `RTT = now - t` |
| `DELETE_ROOM` | `0x14` | C → S | No | JSON object `{ roomId }` requesting durable room teardown; valid from lobby or the same active room |
| `ROOM_DELETED` | `0x15` | S → C | No | Empty payload notifying occupants that their room was deleted; clients clear room/board state and return to lobby |
| `FETCH_LOBBY` | `0x16` | C → S | No | Empty JSON object `{}` requesting an immediate `LOBBY_STATE` response for this connection |
| `VOICE_STATE` | `0x17` | C ↔ S | No | JSON object `{ clientId, isMuted }` — hardware microphone mute toggle; server validates `clientId` and relays to all room peers (not speaking activity) |
| `STATE_REQUEST` | `0x18` | Backplane | No | Cold node requests room state hydration (`{}`); published on Redis room channel |
| `STATE_SNAPSHOT` | `0x19` | Backplane | No | Hot node bulk board payload (same JSON array shape as `SNAPSHOT`); backplane hydration only |
| `CURSOR_SYNC` | `0x1A` | C ↔ S | No | Ephemeral multiplayer cursor: `{ clientId, authorName, x, y }`; relayed in-room on TCP and via Redis `:presence` channel cross-node |
| `MODERATION_ACTION` | `0x1B` | C → S / Backplane | No | `{ actionType, targetClientId, reason }` — `KICK`, `REVOKE_SPEAK`, or `GRANT_SPEAK`; requires `PERM_MANAGE_USERS` |
| `SESSION_REVOKED` | `0x1C` | S → C | No | `{ reason }` — moderation kick; client suppresses auto-reconnect, tears down TCP, resets to lobby; use `resumeAfterSessionRevoked()` to reconnect to server lobby only |
| `ROLE_UPDATE` | `0x1D` | S → C | No | `{ newHostClientId, newPermissions, roomHostClientId? }` — permission sync / host migration |
| `BOARD_SWITCH` | `0x1E` | S → room | No | `{ clientId, newBoardId }` — peer active board for roster grouping |
| `TOGGLE_BOARD_LOCK` | `0x1F` | C ↔ S | No | **C→S:** `{ locked }` (host). **S→room:** broadcast current lock; gates new board creation for members |
| `DELETE_BOARD` | `0x20` | C → S | No | JSON string `boardId` — remove workspace board (requires `PERM_MANAGE_ROOM`; `Board-1` cannot be deleted) |
| `BOARD_DELETED` | `0x21` | S → room | No | JSON string `boardId` — board was removed; clients update switcher and drop cached thumbnails |
| `MEDIA_STATE_UPDATE` | `0x22` | S → room | No | `{ state, mediaTimeSeconds, serverEpochMs, videoId }` — authoritative room-global media snapshot (server-anchored clock) |
| `MEDIA_CONTROL` | `0x23` | C → S | No | `{ action, requestedTime, targetId }` — `PLAY` / `PAUSE` / `SEEK` / `LOAD` / `STOP`; requires `PERM_MANAGE_MEDIA` |
| `MUTATION_BATCH` | `0x24` | C ↔ S | **Yes** | Batched committed shapes (e.g. freehand segments). Payload: JSON array of shape envelopes (same as `SNAPSHOT`); one WAL entry and one peer broadcast per frame |
| `SNAPSHOT_END` | `0x25` | S → C | No | End of chunked snapshot hydration (empty payload); client applies buffered join state |

---

## Prerequisites

| Requirement | Minimum Version |
|---|---|
| JDK | 21 (for local builds and the JavaFX client; not required on the host to *run* the server in Docker) |
| JavaFX | 21.0.4 — includes **`javafx-web`** for the YouTube `WebView` player |
| Apache Maven | 3.8+ (or use the repository `mvnw` / `mvnw.cmd` wrapper) |
| Network (client) | Outbound HTTPS to `www.youtube.com` / `www.googleapis.com` for IFrame API and video streams when using Watch Party |
| Docker Desktop (optional) | Recent **Docker Compose** v2 for containerised server |
| Redis (optional) | **7+** when running multi-node cluster (`docker-compose.cluster.yml`) |
| Network | Server and clients on the same subnet (UDP multicast for pointer presence) |

> **Note:** For a local (non-Docker) server or client, ensure `JAVA_HOME` points to a JDK 21 installation. From the repository root, prefer the Maven wrapper: **`.\mvnw.cmd`** on Windows, **`./mvnw`** on macOS/Linux (instead of requiring a global `mvn` on `PATH`).

---

## Build & Execution

### 1. Build the Project

Run once from the repository root to compile sources and execute all unit tests:

```powershell
.\mvnw.cmd clean install
```

---

### 2. Start the Server

The server listens on **TCP 9090** for the framed control protocol. The container image also **exposes UDP 9090** so host port mappings can carry server-routed UDP traffic (for example push-to-talk relay) on the same port number alongside TCP.

#### Option A — Docker Compose (backend only)

The root **`Dockerfile`** is a multi-stage build: **`maven:3.9.6-eclipse-temurin-21`** copies **`pom.xml`** first and runs **`mvn -B dependency:go-offline -DskipTests`** so dependency layers cache separately; then **`src/`** is copied and the project is built with **`mvn -B package -DskipTests`**, runtime JARs are copied to **`target/dependency`**, and the main artifact is renamed to **`server.jar`**. The runtime stage uses **`eclipse-temurin:21-jre-jammy`** with **`WORKDIR /app`**, copies **`server.jar`** plus **`lib/`**, and starts **`com.distrisync.server.WhiteboardServer`** on port **9090** with WAL data under **`/app/distrisync-data`** (bind-mounted from the host).

From the repository root:

```powershell
docker compose up --build
```

The Compose file defines a **`distrisync-server`** service that **builds** the local `Dockerfile`, publishes **both protocols** on the host (`9090/tcp` and `9090/udp`), mounts **`./distrisync-data:/app/distrisync-data`** so WAL files survive container recreation, and sets **`restart: unless-stopped`**.

You do not need a local JDK or Maven run to produce the server binary when using this path; the build runs entirely inside the image.

To build the image without starting a long-running container:

```powershell
docker compose build
```

#### Option A2 — Docker Compose (two-node cluster + Redis)

`docker-compose.cluster.yml` starts **Redis**, **node-a** (host `9090`), and **node-b** (host `9091`). Each node has its own WAL volume and `NODE_ID`; both point at the same `REDIS_HOST`.

```powershell
docker compose -f docker-compose.cluster.yml up --build
```

Connect clients to either `localhost:9090` or `localhost:9091` (same room id on both nodes once joined). Scrape Prometheus from each node at `http://localhost:8080/metrics` (inside the container; map `METRICS_PORT` in Compose if exposing to the host).

| Service | Host ports | Environment |
|---|---|---|
| `redis` | `6379` | — |
| `node-a` | `9090/tcp`, `9090/udp` | `NODE_ID=node-a`, `REDIS_HOST=redis` |
| `node-b` | `9091/tcp`, `9091/udp` | `NODE_ID=node-b`, `REDIS_HOST=redis` |

#### Option B — Maven or JAR on the host

The server binds on TCP port **9090** by default. Optional positional arguments override **port** and **WAL directory** (see `WhiteboardServer` usage in source). For local runs, WAL files are typically under **`distrisync-data/`** in the working directory and are created on first use.

**Default port (9090), single-node (no Redis):**

```powershell
.\mvnw.cmd exec:java "-Dexec.mainClass=com.distrisync.server.WhiteboardServer"
```

**With Redis backplane (local Redis on 6379):**

```powershell
$env:REDIS_HOST = "127.0.0.1"
$env:NODE_ID = "local-dev"
.\mvnw.cmd exec:java "-Dexec.mainClass=com.distrisync.server.WhiteboardServer"
```

**Custom port (e.g., 8080):**

```powershell
.\mvnw.cmd exec:java "-Dexec.mainClass=com.distrisync.server.WhiteboardServer" "-Dexec.args=8080"
```

**Alternatively, run the assembled JAR with runtime dependencies on the classpath** (after `.\mvnw.cmd package`, dependencies are under `target/dependency/`):

```powershell
java -cp "target\distrisync-0.1.0-SNAPSHOT.jar;target\dependency\*" com.distrisync.server.WhiteboardServer
```

Expected console output on successful bind:

```
INFO  NioServer      - Server listening on port 9090
INFO  WalManager     - WalManager initialised  dataDir='...\distrisync-data'
```

On restart with an existing WAL:

```
INFO  RoomContext     - Replaying 42 WAL record(s) for roomId='default'
INFO  RoomContext     - WAL replay complete  roomId='default' applied=42 total=42 shapesAfterReplay=38
```

Traffic metrics heartbeat (every 10 seconds):

```
INFO  NioServer      - [METRICS] Traffic routed: 1048576 bytes | Active Rooms: 3 | Active Sockets: 12.
```

---

### 3. Start a Client

Each client instance is an independent JavaFX process. Launch as many as needed; all instances connecting to the same server address will share the canvas in real time. If the backend is running via **Docker Compose**, use the default **localhost** and **9090** (both TCP and UDP must be reachable from the client host for full voice and relay behaviour).

**Connect to localhost (default host/port):**

```powershell
.\mvnw.cmd javafx:run
```

**Connect to a specific host and port:**

```powershell
.\mvnw.cmd javafx:run "-Djavafx.args=192.168.1.100 9090"
```

> Open multiple terminals and run `.\mvnw.cmd javafx:run` in each to simulate multiple collaborating peers locally.

---

### 4. Run Tests Only

```powershell
.\mvnw.cmd test
```

---

## License

This project is released under the MIT License.