package com.distrisync.server.backplane;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.ByteBuffer;
import java.util.Base64;

/**
 * Serializes {@link BackplaneEnvelope} instances for Redis {@code PUBLISH} payloads.
 */
public final class BackplaneEnvelopeCodec {

    private static final Gson GSON = new GsonBuilder().create();

    private BackplaneEnvelopeCodec() {}

    public static String roomChannel(String roomId) {
        if (roomId == null || roomId.isBlank()) {
            throw new IllegalArgumentException("roomId must not be blank");
        }
        return "distrisync:room:" + roomId;
    }

    /** Ephemeral presence channel for high-frequency cursor fanout (no WAL / dedup). */
    public static String presenceChannel(String roomId) {
        if (roomId == null || roomId.isBlank()) {
            throw new IllegalArgumentException("roomId must not be blank");
        }
        return "distrisync:room:" + roomId + ":presence";
    }

    /** Room control channel for cross-node moderation (no WAL). */
    public static String controlChannel(String roomId) {
        if (roomId == null || roomId.isBlank()) {
            throw new IllegalArgumentException("roomId must not be blank");
        }
        return "distrisync:room:" + roomId + ":control";
    }

    public static byte[] encode(BackplaneEnvelope envelope) {
        if (envelope == null) throw new IllegalArgumentException("envelope must not be null");

        ByteBuffer payload = envelope.serializedPayload().duplicate();
        byte[] frameBytes = new byte[payload.remaining()];
        payload.get(frameBytes);

        JsonObject root = new JsonObject();
        root.addProperty("eventId", envelope.eventId());
        root.addProperty("originNodeId", envelope.originNodeId());
        root.addProperty("roomId", envelope.roomId());
        root.addProperty("boardId", envelope.boardId());
        root.addProperty("serializedPayload", Base64.getEncoder().encodeToString(frameBytes));

        return GSON.toJson(root).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static BackplaneEnvelope decode(byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("bytes must not be null");

        JsonObject root = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonObject();

        String eventId = root.get("eventId").getAsString();
        String originNodeId = root.get("originNodeId").getAsString();
        String roomId = root.get("roomId").getAsString();
        String boardId = root.get("boardId").getAsString();
        byte[] frameBytes = Base64.getDecoder().decode(root.get("serializedPayload").getAsString());

        ByteBuffer frame = ByteBuffer.wrap(frameBytes);
        return new BackplaneEnvelope(eventId, originNodeId, roomId, boardId, frame);
    }
}
