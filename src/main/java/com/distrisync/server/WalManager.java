package com.distrisync.server;

import com.distrisync.model.Shape;
import com.distrisync.protocol.Message;
import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.MessageType;
import com.distrisync.protocol.PartialMessageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Append-only Write-Ahead Log engine for per-room, per-board canvas durability.
 *
 * <h2>File layout</h2>
 * Each room/board pair produces {@code {sanitisedRoomId}_{sanitisedBoardId}.wal} under
 * {@code dataDir}.  The binary frame format matches {@link MessageCodec}.
 *
 * <h2>Compaction</h2>
 * {@link #compactWal} rewrites the WAL to minimal chunked {@code MUTATION_BATCH} frames via a
 * {@code .wal.tmp} side-file and atomic rename.
 *
 * <h2>Recovery</h2>
 * {@link #replay} streams frames from disk through a {@link FileChannel} without loading the
 * entire file into heap. {@link #recover} collects frames into a list for tests.
 *
 * <h2>Thread safety</h2>
 * Concurrent use is safe; each composite WAL file has a lazily opened {@link FileChannel}
 * in a {@link ConcurrentHashMap}.
 */
final class WalManager implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(WalManager.class);

    private static final int REPLAY_BUFFER_INITIAL_BYTES = 8 * 1024;

    private final Path dataDir;
    /** Key: {@code sanitize(roomId) + '_' + sanitize(boardId)} — matches {@code *.wal} basename (no suffix). */
    private final ConcurrentHashMap<String, FileChannel> channels = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    WalManager(Path dataDir) throws IOException {
        if (dataDir == null) throw new IllegalArgumentException("dataDir must not be null");
        Files.createDirectories(dataDir);
        this.dataDir = dataDir;
        log.info("WalManager initialised  dataDir='{}'", dataDir);
    }

    /**
     * Appends {@code msg} to the WAL for {@code roomId} and {@code boardId}.
     */
    void append(String roomId, String boardId, Message msg) throws IOException {
        validateNonBlank(roomId, "roomId");
        validateNonBlank(boardId, "boardId");
        if (msg == null) throw new IllegalArgumentException("msg must not be null");

        String key = walMapKey(roomId, boardId);
        FileChannel ch = channels.computeIfAbsent(key, k -> openAppendChannel(k + ".wal"));

        ByteBuffer frame = MessageCodec.encode(msg);
        while (frame.hasRemaining()) {
            ch.write(frame);
        }
        log.debug("WAL append  room='{}' board='{}' type={} frameBytes={}",
                roomId, boardId, msg.type(), frame.capacity());
    }

    /**
     * Streams all complete frames from the WAL for {@code roomId} and {@code boardId} to
     * {@code frameHandler} without loading the entire file into memory.
     */
    void replay(String roomId, String boardId, Consumer<Message> frameHandler) throws IOException {
        validateNonBlank(roomId, "roomId");
        validateNonBlank(boardId, "boardId");
        if (frameHandler == null) throw new IllegalArgumentException("frameHandler must not be null");

        Path path = walPath(roomId, boardId);
        if (!Files.exists(path) || Files.size(path) == 0) {
            return;
        }

        int frameCount = 0;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(REPLAY_BUFFER_INITIAL_BYTES);

            while (true) {
                int read = channel.read(buf);
                boolean eof = (read == -1);

                buf.flip();

                boolean needMoreData = false;
                while (buf.hasRemaining()) {
                    int frameStart = buf.position();
                    try {
                        frameHandler.accept(MessageCodec.decode(buf));
                        frameCount++;
                    } catch (PartialMessageException e) {
                        buf.position(frameStart);
                        if (eof) {
                            log.warn("WAL truncated tail  room='{}' board='{}' offset={} — discarding {} partial byte(s)",
                                    roomId, boardId, frameStart, buf.remaining());
                            log.debug("WAL replayed  room='{}' board='{}' messages={}", roomId, boardId, frameCount);
                            return;
                        }
                        needMoreData = true;
                        break;
                    } catch (IllegalArgumentException e) {
                        log.warn("WAL corrupt frame  room='{}' board='{}' offset={} cause='{}' — discarding tail",
                                roomId, boardId, frameStart, e.getMessage());
                        log.debug("WAL replayed  room='{}' board='{}' messages={}", roomId, boardId, frameCount);
                        return;
                    }
                }

                if (needMoreData) {
                    buf.compact();
                    if (buf.remaining() == buf.capacity()) {
                        buf = growBuffer(buf);
                    }
                    continue;
                }

                buf.clear();
                if (eof) {
                    break;
                }
            }
        }

        log.debug("WAL replayed  room='{}' board='{}' messages={}", roomId, boardId, frameCount);
    }

    /**
     * Reads all complete frames from the WAL for {@code roomId} and {@code boardId}.
     */
    List<Message> recover(String roomId, String boardId) throws IOException {
        List<Message> messages = new ArrayList<>();
        replay(roomId, boardId, messages::add);
        log.debug("WAL recovered  room='{}' board='{}' messages={}", roomId, boardId, messages.size());
        return messages;
    }

    /**
     * Lists every room id that has at least one persisted {@code .wal} file under {@link #dataDir}.
     *
     * <p>Filenames follow {@code {sanitisedRoomId}_{sanitisedBoardId}.wal}. The returned id is the
     * sanitised room prefix (everything before the last {@code '_'} in the basename), so it matches
     * the stem used by {@link #walPath} and may differ from the original room string when the latter
     * contained filename-unsafe characters.
     */
    public Set<String> getPersistedRoomIds() throws IOException {
        Set<String> ids = new HashSet<>();
        try (Stream<Path> stream = Files.list(dataDir)) {
            stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".wal"))
                    .map(WalManager::roomStemFromWalFilename)
                    .filter(Objects::nonNull)
                    .forEach(ids::add);
        }
        return Set.copyOf(ids);
    }

    /**
     * Parses {@code sanitisedRoom_sanitisedBoard.wal} → sanitised room stem (substring before last
     * {@code '_'}), or {@code null} if the name does not match the expected pattern.
     */
    private static String roomStemFromWalFilename(String fileName) {
        if (fileName.length() <= 4) {
            return null;
        }
        String base = fileName.substring(0, fileName.length() - ".wal".length());
        int last = base.lastIndexOf('_');
        if (last <= 0 || last >= base.length() - 1) {
            return null;
        }
        return base.substring(0, last);
    }

    void compactWal(String roomId, String boardId, List<Shape> snapshot) throws IOException {
        validateNonBlank(roomId, "roomId");
        validateNonBlank(boardId, "boardId");
        if (snapshot == null) throw new IllegalArgumentException("snapshot must not be null");

        String baseName = walMapKey(roomId, boardId);
        Path   walFile = dataDir.resolve(baseName + ".wal");
        Path   tmpFile = dataDir.resolve(baseName + ".wal.tmp");

        List<String> batchPayloads = ShapeCodec.chunkMutationBatchPayloads(snapshot);

        try (FileChannel tmp = FileChannel.open(tmpFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            for (String payload : batchPayloads) {
                Message msg = new Message(MessageType.MUTATION_BATCH, payload);
                ByteBuffer frame = MessageCodec.encode(msg);
                while (frame.hasRemaining()) {
                    tmp.write(frame);
                }
            }
            tmp.force(true);
        }

        FileChannel live = channels.remove(baseName);
        if (live != null) {
            try { live.close(); } catch (IOException ignored) {}
        }

        try {
            Files.move(tmpFile, walFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            log.warn("ATOMIC_MOVE unavailable, falling back to non-atomic replace  room='{}' board='{}'",
                    roomId, boardId);
            Files.move(tmpFile, walFile, StandardCopyOption.REPLACE_EXISTING);
        }

        log.info("WAL compacted  room='{}' board='{}' shapes={} batches={}",
                roomId, boardId, snapshot.size(), batchPayloads.size());
    }

    long walFileSize(String roomId, String boardId) throws IOException {
        validateNonBlank(roomId, "roomId");
        validateNonBlank(boardId, "boardId");

        String baseName = walMapKey(roomId, boardId);
        FileChannel ch = channels.get(baseName);
        if (ch != null && ch.isOpen()) {
            return ch.size();
        }

        Path path = dataDir.resolve(baseName + ".wal");
        return Files.exists(path) ? Files.size(path) : 0L;
    }

    /**
     * Releases the WAL {@link FileChannel} for this room/board pair and deletes exactly
     * {@code walMapKey(roomId, boardId) + ".wal"} and {@code ... + ".wal.tmp"} under {@link #dataDir}.
     * Does not scan prefixes; other boards in the same room are untouched.
     */
    public void deleteBoardFiles(String roomId, String boardId) throws IOException {
        validateNonBlank(roomId, "roomId");
        validateNonBlank(boardId, "boardId");
        String baseName = walMapKey(roomId, boardId);

        FileChannel live = channels.remove(baseName);
        if (live != null) {
            try {
                live.close();
            } catch (IOException e) {
                log.warn("Error closing WAL channel during board delete  key='{}': {}", baseName, e.getMessage());
            }
        }

        Path walFile = dataDir.resolve(baseName + ".wal");
        Path tmpFile = dataDir.resolve(baseName + ".wal.tmp");
        Files.deleteIfExists(walFile);
        Files.deleteIfExists(tmpFile);

        log.info("WAL board files removed  roomId='{}' boardId='{}' baseName='{}' dataDir='{}'",
                roomId, boardId, baseName, dataDir);
    }

    /**
     * Releases WAL {@link FileChannel}s for this room and deletes matching {@code .wal} files under
     * {@link #dataDir}. Channels must be closed before file deletion so the OS lock is dropped.
     *
     * <p>Keys and filenames use {@link #sanitize(String)} for the room segment, matching
     * {@link #walMapKey(String, String)} — the effective prefix is {@code sanitize(roomId) + '_'}.
     */
    public void deleteRoomFiles(String roomId) throws IOException {
        validateNonBlank(roomId, "roomId");
        String sanitizedRoomId = sanitize(roomId);
        String prefix = sanitizedRoomId + "_";
        for (String key : new ArrayList<>(channels.keySet())) {
            if (key.startsWith(prefix)) {
                FileChannel ch = channels.get(key);
                if (ch != null) {
                    try {
                        ch.close();
                    } catch (IOException e) {
                        log.warn("Error closing WAL channel during room delete  key='{}': {}", key, e.getMessage());
                    }
                }
                channels.remove(key);
            }
        }

        int deletePasses = 0;
        boolean roomRemoved = false;
        while (deletePasses < 2 && !roomRemoved) {
            deletePasses++;
            try (Stream<Path> stream = Files.list(dataDir)) {
                List<Path> walPaths = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> {
                            String name = p.getFileName().toString();
                            return name.endsWith(".wal") && name.startsWith(prefix);
                        })
                        .toList();
                for (Path p : walPaths) {
                    Files.deleteIfExists(p);
                }
            }

            boolean filesRemain;
            try (Stream<Path> stream = Files.list(dataDir)) {
                filesRemain = stream
                        .filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString())
                        .anyMatch(name -> name.endsWith(".wal") && name.startsWith(prefix));
            }

            Set<String> persistedRoomIds = getPersistedRoomIds();
            boolean roomStillPersisted = persistedRoomIds.contains(roomId) || persistedRoomIds.contains(sanitizedRoomId);
            roomRemoved = !filesRemain && !roomStillPersisted;

            if (!roomRemoved) {
                log.error("Synchronous WAL delete barrier not met  roomId='{}' sanitizedRoomId='{}' prefix='{}' filesRemain={} persistedRoomIds={}",
                        roomId, sanitizedRoomId, prefix, filesRemain, persistedRoomIds);
            }
        }

        if (!roomRemoved) {
            throw new IOException("deleteRoomFiles() failed synchronous barrier for roomId='" + roomId + "'");
        }

        log.info("WAL room files removed  roomId='{}' prefix='{}' dataDir='{}'", roomId, prefix, dataDir);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (Map.Entry<String, FileChannel> entry : channels.entrySet()) {
            try {
                entry.getValue().close();
            } catch (IOException e) {
                log.warn("Error closing WAL channel  key='{}': {}", entry.getKey(), e.getMessage());
            }
        }
        channels.clear();
        log.info("WalManager closed  dataDir='{}'", dataDir);
    }

    /** Same mapping as WAL basenames; package-private for lobby / discovery alignment. */
    static String sanitize(String id) {
        return id.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String walMapKey(String roomId, String boardId) {
        return sanitize(roomId) + "_" + sanitize(boardId);
    }

    private Path walPath(String roomId, String boardId) {
        return dataDir.resolve(walMapKey(roomId, boardId) + ".wal");
    }

    private static void validateNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be null or blank");
        }
    }

    private static ByteBuffer growBuffer(ByteBuffer buf) {
        int newCapacity = Math.max(buf.capacity() * 2,
                MessageCodec.MAX_PAYLOAD_BYTES + MessageCodec.HEADER_BYTES);
        ByteBuffer grown = ByteBuffer.allocate(newCapacity);
        buf.flip();
        grown.put(buf);
        return grown;
    }

    private FileChannel openAppendChannel(String fileName) {
        try {
            return FileChannel.open(
                    dataDir.resolve(fileName),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException("Failed to open WAL channel for '" + fileName + "'", e);
        }
    }
}
