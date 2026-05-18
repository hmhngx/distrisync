package com.distrisync.server.backplane;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Cross-node mutation event published to the Redis room channel after WAL commit.
 *
 * @param eventId            unique id for idempotent apply on subscribers
 * @param originNodeId       server instance id generated once at JVM startup
 * @param roomId             canvas room
 * @param boardId            active board within the room
 * @param serializedPayload  full DistriSync wire frame (5-byte header + JSON)
 */
public record BackplaneEnvelope(
        String eventId,
        String originNodeId,
        String roomId,
        String boardId,
        ByteBuffer serializedPayload
) {
    public BackplaneEnvelope {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(originNodeId, "originNodeId must not be null");
        Objects.requireNonNull(roomId, "roomId must not be null");
        Objects.requireNonNull(boardId, "boardId must not be null");
        Objects.requireNonNull(serializedPayload, "serializedPayload must not be null");
        if (eventId.isBlank()) throw new IllegalArgumentException("eventId must not be blank");
        if (originNodeId.isBlank()) throw new IllegalArgumentException("originNodeId must not be blank");
        if (roomId.isBlank()) throw new IllegalArgumentException("roomId must not be blank");
        if (boardId.isBlank()) throw new IllegalArgumentException("boardId must not be blank");
        serializedPayload = copyBuffer(serializedPayload);
    }

    private static ByteBuffer copyBuffer(ByteBuffer src) {
        ByteBuffer dup = src.duplicate();
        ByteBuffer copy = ByteBuffer.allocate(dup.remaining());
        copy.put(dup);
        copy.flip();
        return copy;
    }
}
