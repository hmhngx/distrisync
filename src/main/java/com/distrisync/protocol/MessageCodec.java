package com.distrisync.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stateless utility that encodes/decodes DistriSync binary frames.
 *
 * <h2>Frame layout</h2>
 * <pre>
 * ┌───────────────┬──────────────────────────┬────────────────────────────┐
 * │  Byte 0       │  Bytes 1-4               │  Bytes 5 … (5 + length-1)  │
 * │  MessageType  │  PayloadLength (int32 BE) │  UTF-8 JSON payload       │
 * │  (1 byte)     │  (4 bytes, big-endian)    │  (variable)               │
 * └───────────────┴──────────────────────────┴────────────────────────────┘
 * </pre>
 *
 * <ul>
 *   <li>Total header is always {@value #HEADER_BYTES} bytes.</li>
 *   <li>PayloadLength is a signed 32-bit big-endian integer; a negative value
 *       is treated as a protocol error.</li>
 *   <li>All {@code decode} overloads are <em>non-destructive on partial input</em>:
 *       if the buffer does not hold a full frame the position is rewound to its
 *       original value before throwing {@link PartialMessageException}.</li>
 * </ul>
 *
 * <p>This class is thread-safe; the shared {@link Gson} instance is immutable
 * after construction.
 */
public final class MessageCodec {

    private static final Logger log = LoggerFactory.getLogger(MessageCodec.class);

    /** Fixed header size: 1 byte type + 4 bytes length. */
    public static final int HEADER_BYTES = 5;

    /**
     * Maximum accepted payload size (16 MiB). Protects against malformed frames
     * that declare an absurdly large length before we attempt an allocation.
     */
    public static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;

    /**
     * Historical {@link com.distrisync.client.NetworkClient} read accumulator size.
     * Frames with a UTF-8 payload larger than this could not be decoded until the
     * buffer was enlarged; we log a warning so logs point at the failure mode.
     */
    public static final int LEGACY_CLIENT_READ_BUFFER_BYTES = 64 * 1024;

    /**
     * Default workspace board when {@link MessageType#JOIN_ROOM} omits {@code initialBoardId}.
     */
    public static final String DEFAULT_INITIAL_BOARD_ID = "Board-1";

    private static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    private MessageCodec() { /* utility class */ }

    // -------------------------------------------------------------------------
    // Encode
    // -------------------------------------------------------------------------

    /**
     * Serializes a {@link Message} into a newly allocated, ready-to-read
     * {@link ByteBuffer} (position=0, limit=totalFrameSize).
     *
     * @param message the message to encode; must not be {@code null}
     * @return a flipped {@code ByteBuffer} ready for channel writes
     */
    public static ByteBuffer encode(Message message) {
        if (message == null) throw new IllegalArgumentException("message must not be null");

        byte[] payloadBytes = message.payload().getBytes(StandardCharsets.UTF_8);
        int payloadLength   = payloadBytes.length;

        if (payloadLength > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "Payload exceeds maximum allowed size (" + payloadLength + " > " + MAX_PAYLOAD_BYTES + ")");
        }

        // Allocate header + payload in one shot — avoids a copy later.
        ByteBuffer frame = ByteBuffer.allocate(HEADER_BYTES + payloadLength)
                .order(ByteOrder.BIG_ENDIAN);

        frame.put(message.type().wireCode()); // byte 0 : type discriminator
        frame.putInt(payloadLength);          // bytes 1-4 : payload length
        frame.put(payloadBytes);              // bytes 5+ : JSON body

        frame.flip(); // prepare for reading
        return frame;
    }

    /**
     * Convenience overload: serializes {@code object} to JSON via Gson, then
     * wraps it in a frame of the given {@code type}.
     *
     * @param type   the frame type discriminator
     * @param object the domain object to serialize; must be Gson-serializable
     * @param <T>    the domain object type
     * @return a flipped {@code ByteBuffer} ready for channel writes
     */
    public static <T> ByteBuffer encodeObject(MessageType type, T object) {
        if (type   == null) throw new IllegalArgumentException("type must not be null");
        if (object == null) throw new IllegalArgumentException("object must not be null");

        String json = GSON.toJson(object);
        return encode(new Message(type, json));
    }

    // -------------------------------------------------------------------------
    // Decode
    // -------------------------------------------------------------------------

    /**
     * Attempts to decode one complete frame from {@code buffer}.
     *
     * <p>The buffer's {@link ByteOrder} is temporarily treated as big-endian
     * during the read regardless of its configured order.
     *
     * <p><strong>Partial-read contract:</strong> if the buffer does not contain
     * a full frame the buffer's position is reset to where it was on entry and
     * a {@link PartialMessageException} is thrown. The caller must not discard
     * already-buffered bytes; instead it should append newly received bytes and
     * retry.
     *
     * @param buffer the incoming data buffer; position should be set to the
     *               start of the next frame to read
     * @return a fully decoded {@link Message}
     * @throws PartialMessageException  if {@code buffer} contains fewer bytes
     *                                  than required for a complete frame
     * @throws IllegalArgumentException if the frame contains an unknown type
     *                                  discriminator or an invalid payload length
     */
    public static Message decode(ByteBuffer buffer) {
        if (buffer == null) throw new IllegalArgumentException("buffer must not be null");

        // Snapshot position so we can rewind on partial read.
        int startPosition = buffer.position();

        try {
            // --- Guard: need at least the header --------------------------------
            if (buffer.remaining() < HEADER_BYTES) {
                throw new PartialMessageException(
                        "Incomplete header: need " + HEADER_BYTES
                        + " bytes, have " + buffer.remaining(),
                        HEADER_BYTES - buffer.remaining());
            }

            // --- Read header (big-endian) ---------------------------------------
            byte typeByte     = buffer.order(ByteOrder.BIG_ENDIAN).get();
            int  payloadLength = buffer.order(ByteOrder.BIG_ENDIAN).getInt();

            // --- Validate header fields ----------------------------------------
            if (payloadLength < 0) {
                throw new IllegalArgumentException(
                        "Frame declares negative payload length: " + payloadLength);
            }
            if (payloadLength > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException(
                        "Frame payload length " + payloadLength
                        + " exceeds maximum " + MAX_PAYLOAD_BYTES);
            }

            // --- Guard: need the full payload -----------------------------------
            if (buffer.remaining() < payloadLength) {
                int payloadBytesAvailable = buffer.remaining();
                int missing = payloadLength - payloadBytesAvailable;
                // Rewind before throwing so caller can retry after buffering more data.
                buffer.position(startPosition);
                throw new PartialMessageException(
                        "Incomplete payload: need " + payloadLength
                        + " bytes, have " + payloadBytesAvailable
                        + " (missing " + missing + ")",
                        missing);
            }

            // --- Read payload ---------------------------------------------------
            byte[] payloadBytes = new byte[payloadLength];
            buffer.get(payloadBytes);
            String payload = new String(payloadBytes, StandardCharsets.UTF_8);

            // --- Resolve type (after all reads so partial-read rewind stays valid)
            MessageType type = MessageType.fromWireCode(typeByte);

            if (payloadLength > LEGACY_CLIENT_READ_BUFFER_BYTES) {
                log.warn(
                        "Inbound frame payload {} bytes exceeds legacy {} KiB client read buffer — "
                                + "SNAPSHOT/join can stall on old clients (type={}, totalFrame≈{} bytes)",
                        payloadLength,
                        LEGACY_CLIENT_READ_BUFFER_BYTES / 1024,
                        type,
                        HEADER_BYTES + payloadLength);
            }

            return new Message(type, payload);

        } catch (BufferUnderflowException e) {
            // Should not happen given the guards above, but handled defensively.
            buffer.position(startPosition);
            throw new PartialMessageException(
                    "Unexpected buffer underflow while decoding frame: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Payload helpers
    // -------------------------------------------------------------------------

    /**
     * Deserializes the JSON payload of a {@link Message} into a domain object.
     *
     * @param message the decoded message
     * @param clazz   the target type
     * @param <T>     the target type parameter
     * @return the deserialized domain object
     */
    public static <T> T decodePayload(Message message, Class<T> clazz) {
        if (message == null) throw new IllegalArgumentException("message must not be null");
        if (clazz   == null) throw new IllegalArgumentException("clazz must not be null");
        return GSON.fromJson(message.payload(), clazz);
    }

    // -------------------------------------------------------------------------
    // HANDSHAKE helpers
    // -------------------------------------------------------------------------

    /**
     * JSON payload of a {@link MessageType#HANDSHAKE} frame: display name and
     * stable client id. Room membership is established with {@link MessageType#JOIN_ROOM}.
     *
     * @param clientId session-scoped stable identifier from the client
     */
    public record HandshakePayload(String clientId) {}

    /**
     * Encodes a {@code HANDSHAKE} frame with the given {@code clientId}.
     *
     * @param clientId may be {@code null} (stored as empty string)
     */
    public static ByteBuffer encodeHandshake(String clientId) {
        HandshakePayload payload = new HandshakePayload(
                clientId != null ? clientId : "");
        return encodeObject(MessageType.HANDSHAKE, payload);
    }

    /**
     * Parses a {@code HANDSHAKE} message payload into a {@link HandshakePayload}.
     * Malformed JSON falls back to {@code ""}. Legacy {@code authorName} and
     * {@code roomId} in JSON are ignored (identity is chosen via {@code JOIN_ROOM}).
     *
     * @param msg a decoded message whose type is {@link MessageType#HANDSHAKE}
     * @return normalized handshake fields
     * @throws IllegalArgumentException if {@code msg} is {@code null} or not a HANDSHAKE
     */
    public static HandshakePayload decodeHandshake(Message msg) {
        if (msg == null) throw new IllegalArgumentException("msg must not be null");
        if (msg.type() != MessageType.HANDSHAKE) {
            throw new IllegalArgumentException("expected HANDSHAKE, got " + msg.type());
        }
        try {
            JsonObject p = JsonParser.parseString(msg.payload()).getAsJsonObject();
            String cid = "";
            if (p.has("clientId") && !p.get("clientId").isJsonNull()) {
                cid = p.get("clientId").getAsString();
            }
            return new HandshakePayload(cid);
        } catch (Exception e) {
            return new HandshakePayload("");
        }
    }

    // -------------------------------------------------------------------------
    // LOBBY_STATE / JOIN_ROOM / LEAVE_ROOM
    // -------------------------------------------------------------------------

    /**
     * One row in a {@link MessageType#LOBBY_STATE} payload JSON array.
     *
     * @param roomId    non-null room identifier
     * @param userCount number of TCP clients currently in that room (not in lobby)
     */
    public record LobbyRoomEntry(String roomId, int userCount) {}

    private static final Type LOBBY_LIST_TYPE = new TypeToken<List<LobbyRoomEntry>>() {}.getType();

    /**
     * Encodes a {@code LOBBY_STATE} frame: JSON array of {@link LobbyRoomEntry}.
     */
    public static ByteBuffer encodeLobbyState(List<LobbyRoomEntry> rooms) {
        if (rooms == null) throw new IllegalArgumentException("rooms must not be null");
        String json = GSON.toJson(rooms);
        return encode(new Message(MessageType.LOBBY_STATE, json));
    }

    /**
     * Decodes the payload of a {@link MessageType#LOBBY_STATE} message.
     */
    public static List<LobbyRoomEntry> decodeLobbyState(Message msg) {
        if (msg == null) throw new IllegalArgumentException("msg must not be null");
        if (msg.type() != MessageType.LOBBY_STATE) {
            throw new IllegalArgumentException("expected LOBBY_STATE, got " + msg.type());
        }
        List<LobbyRoomEntry> list = GSON.fromJson(msg.payload(), LOBBY_LIST_TYPE);
        return list != null ? List.copyOf(list) : List.of();
    }

    /**
     * JSON body for {@link MessageType#JOIN_ROOM}: target room, optional initial board, and display name.
     *
     * @param roomId         non-blank room identifier
     * @param initialBoardId board to open; if {@code null} or blank, {@link #DEFAULT_INITIAL_BOARD_ID} is used
     * @param displayName    room-level display identity; never {@code null} after decode
     */
    public record JoinRoomPayload(String roomId, String initialBoardId, String displayName) {}

    /**
     * Encodes {@code JOIN_ROOM} with {@code displayName} (no {@code initialBoardId} field).
     * The server defaults the board to {@link #DEFAULT_INITIAL_BOARD_ID}.
     */
    public static ByteBuffer encodeJoinRoom(String roomId, String displayName) {
        if (roomId == null) throw new IllegalArgumentException("roomId must not be null");
        JsonObject o = new JsonObject();
        o.addProperty("roomId", roomId);
        o.addProperty("displayName", displayName != null ? displayName : "");
        return encode(new Message(MessageType.JOIN_ROOM, GSON.toJson(o)));
    }

    /**
     * Encodes {@code JOIN_ROOM} including {@code displayName} and {@code initialBoardId} when non-blank.
     */
    public static ByteBuffer encodeJoinRoom(String roomId, String displayName, String initialBoardId) {
        if (roomId == null) throw new IllegalArgumentException("roomId must not be null");
        JsonObject o = new JsonObject();
        o.addProperty("roomId", roomId);
        o.addProperty("displayName", displayName != null ? displayName : "");
        if (initialBoardId != null && !initialBoardId.isBlank()) {
            o.addProperty("initialBoardId", initialBoardId);
        }
        return encode(new Message(MessageType.JOIN_ROOM, GSON.toJson(o)));
    }

    /**
     * Parses {@code JOIN_ROOM} payload: JSON object {@code { roomId, displayName?, initialBoardId? }},
     * or a legacy JSON string room id. Missing or blank {@code initialBoardId} defaults to
     * {@link #DEFAULT_INITIAL_BOARD_ID}; missing {@code displayName} defaults to {@code ""}.
     */
    public static JoinRoomPayload decodeJoinRoom(Message msg) {
        if (msg == null) throw new IllegalArgumentException("msg must not be null");
        if (msg.type() != MessageType.JOIN_ROOM) {
            throw new IllegalArgumentException("expected JOIN_ROOM, got " + msg.type());
        }
        String raw = msg.payload();
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("JOIN_ROOM payload is blank");
        }
        try {
            var el = JsonParser.parseString(raw.strip());
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                String rid = el.getAsString().strip();
                if (rid.isBlank()) {
                    throw new IllegalArgumentException("JOIN_ROOM room id is blank");
                }
                return new JoinRoomPayload(rid, DEFAULT_INITIAL_BOARD_ID, "");
            }
            if (el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                if (!o.has("roomId") || o.get("roomId").isJsonNull()) {
                    throw new IllegalArgumentException("JOIN_ROOM missing roomId");
                }
                String rid = o.get("roomId").getAsString().strip();
                if (rid.isBlank()) {
                    throw new IllegalArgumentException("JOIN_ROOM room id is blank");
                }
                String board = DEFAULT_INITIAL_BOARD_ID;
                if (o.has("initialBoardId") && !o.get("initialBoardId").isJsonNull()) {
                    String ib = o.get("initialBoardId").getAsString().strip();
                    if (!ib.isBlank()) {
                        board = ib;
                    }
                }
                String name = "";
                if (o.has("displayName") && !o.get("displayName").isJsonNull()) {
                    name = o.get("displayName").getAsString();
                }
                return new JoinRoomPayload(rid, board, name);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed JOIN_ROOM: " + e.getMessage(), e);
        }
        throw new IllegalArgumentException("JOIN_ROOM payload must be a JSON string or object with roomId");
    }

    /**
     * Encodes {@code SWITCH_BOARD} with a JSON string literal payload (the target {@code boardId}).
     */
    public static ByteBuffer encodeSwitchBoard(String boardId) {
        if (boardId == null) throw new IllegalArgumentException("boardId must not be null");
        return encode(new Message(MessageType.SWITCH_BOARD, GSON.toJson(boardId)));
    }

    public static String decodeSwitchBoard(Message msg) {
        if (msg == null) throw new IllegalArgumentException("msg must not be null");
        if (msg.type() != MessageType.SWITCH_BOARD) {
            throw new IllegalArgumentException("expected SWITCH_BOARD, got " + msg.type());
        }
        return GSON.fromJson(msg.payload(), String.class);
    }

    /**
     * Encodes {@code DELETE_BOARD} with a JSON string literal payload (the target {@code boardId}).
     */
    public static ByteBuffer encodeDeleteBoard(String boardId) {
        if (boardId == null) throw new IllegalArgumentException("boardId must not be null");
        return encode(new Message(MessageType.DELETE_BOARD, GSON.toJson(boardId)));
    }

    public static String decodeDeleteBoard(Message msg) {
        if (msg == null) throw new IllegalArgumentException("msg must not be null");
        if (msg.type() != MessageType.DELETE_BOARD) {
            throw new IllegalArgumentException("expected DELETE_BOARD, got " + msg.type());
        }
        String raw = GSON.fromJson(msg.payload(), String.class);
        if (raw == null) {
            throw new IllegalArgumentException("DELETE_BOARD payload is null");
        }
        String bid = raw.strip();
        if (bid.isBlank()) {
            throw new IllegalArgumentException("DELETE_BOARD board id is blank");
        }
        return bid;
    }

    /**
     * Encodes {@code BOARD_DELETED} with a JSON string literal payload (the removed {@code boardId}).
     */
    public static ByteBuffer encodeBoardDeleted(String boardId) {
        if (boardId == null) throw new IllegalArgumentException("boardId must not be null");
        return encode(new Message(MessageType.BOARD_DELETED, GSON.toJson(boardId)));
    }

    public static String decodeBoardDeleted(Message msg) {
        if (msg == null) throw new IllegalArgumentException("msg must not be null");
        if (msg.type() != MessageType.BOARD_DELETED) {
            throw new IllegalArgumentException("expected BOARD_DELETED, got " + msg.type());
        }
        String raw = GSON.fromJson(msg.payload(), String.class);
        if (raw == null) {
            throw new IllegalArgumentException("BOARD_DELETED payload is null");
        }
        String bid = raw.strip();
        if (bid.isBlank()) {
            throw new IllegalArgumentException("BOARD_DELETED board id is blank");
        }
        return bid;
    }

    /** Encodes {@code LEAVE_ROOM} with an empty UTF-8 payload body. */
    public static ByteBuffer encodeLeaveRoom() {
        return encode(new Message(MessageType.LEAVE_ROOM, ""));
    }

    /**
     * Server→client {@code JOIN_ROOM}: notifies peers that a member entered the room.
     */
    public record RoomMemberJoinedPayload(String clientId, String authorName) {}

    /**
     * Encodes server→client {@code JOIN_ROOM} with {@code { clientId, authorName }}.
     */
    public static ByteBuffer encodeRoomMemberJoined(String clientId, String authorName) {
        if (clientId == null) throw new IllegalArgumentException("clientId must not be null");
        JsonObject o = new JsonObject();
        o.addProperty("clientId", clientId);
        o.addProperty("authorName", authorName != null ? authorName : "");
        return encode(new Message(MessageType.JOIN_ROOM, GSON.toJson(o)));
    }

    /**
     * Decodes server→client {@code JOIN_ROOM} peer-join notification.
     *
     * @throws IllegalArgumentException if payload is not a {@code { clientId, authorName }} object
     */
    public static RoomMemberJoinedPayload decodeRoomMemberJoined(Message msg) {
        if (msg == null) throw new IllegalArgumentException("msg must not be null");
        if (msg.type() != MessageType.JOIN_ROOM) {
            throw new IllegalArgumentException("expected JOIN_ROOM, got " + msg.type());
        }
        String raw = msg.payload();
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("JOIN_ROOM peer-join payload is blank");
        }
        try {
            var el = JsonParser.parseString(raw.strip());
            if (!el.isJsonObject()) {
                throw new IllegalArgumentException("JOIN_ROOM peer-join payload must be a JSON object");
            }
            JsonObject o = el.getAsJsonObject();
            if (o.has("roomId") && !o.get("roomId").isJsonNull()) {
                throw new IllegalArgumentException("JOIN_ROOM payload is a client join request, not peer-join");
            }
            if (!o.has("clientId") || o.get("clientId").isJsonNull()) {
                throw new IllegalArgumentException("JOIN_ROOM peer-join missing clientId");
            }
            String cid = o.get("clientId").getAsString().strip();
            if (cid.isBlank()) {
                throw new IllegalArgumentException("JOIN_ROOM peer-join clientId is blank");
            }
            String name = "";
            if (o.has("authorName") && !o.get("authorName").isJsonNull()) {
                name = o.get("authorName").getAsString();
            }
            return new RoomMemberJoinedPayload(cid, name);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed JOIN_ROOM peer-join: " + e.getMessage(), e);
        }
    }

    /**
     * Encodes server→client {@code LEAVE_ROOM} with a JSON string {@code clientId} payload.
     */
    public static ByteBuffer encodeRoomMemberLeft(String clientId) {
        if (clientId == null) throw new IllegalArgumentException("clientId must not be null");
        return encode(new Message(MessageType.LEAVE_ROOM, GSON.toJson(clientId)));
    }

    /**
     * Decodes server→client {@code LEAVE_ROOM} peer-depart notification.
     *
     * @throws IllegalArgumentException if payload is empty or not a JSON string clientId
     */
    public static String decodeRoomMemberLeft(Message msg) {
        if (msg == null) throw new IllegalArgumentException("msg must not be null");
        if (msg.type() != MessageType.LEAVE_ROOM) {
            throw new IllegalArgumentException("expected LEAVE_ROOM, got " + msg.type());
        }
        String raw = msg.payload();
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("LEAVE_ROOM peer-depart payload is blank");
        }
        String clientId = GSON.fromJson(raw.strip(), String.class);
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("LEAVE_ROOM peer-depart clientId is blank");
        }
        return clientId.strip();
    }

    /**
     * Encodes {@code FETCH_LOBBY} with the required empty JSON object body {@code {}}.
     */
    public static ByteBuffer encodeFetchLobby() {
        return encode(new Message(MessageType.FETCH_LOBBY, "{}"));
    }

    /**
     * Validates a {@code FETCH_LOBBY} frame: payload must be exactly {@code {}} (optional surrounding whitespace).
     *
     * @throws IllegalArgumentException if {@code msg} is wrong type or payload is not {@code {}}
     */
    public static void decodeFetchLobby(Message msg) {
        if (msg == null) throw new IllegalArgumentException("msg must not be null");
        if (msg.type() != MessageType.FETCH_LOBBY) {
            throw new IllegalArgumentException("expected FETCH_LOBBY, got " + msg.type());
        }
        String raw = msg.payload();
        if (raw == null) {
            throw new IllegalArgumentException("FETCH_LOBBY payload is null");
        }
        String p = raw.strip();
        if (!p.equals("{}")) {
            throw new IllegalArgumentException("FETCH_LOBBY payload must be empty JSON object {}");
        }
    }

    // -------------------------------------------------------------------------
    // DELETE_ROOM / ROOM_DELETED
    // -------------------------------------------------------------------------

    /**
     * JSON body for {@link MessageType#DELETE_ROOM}: room to remove from durable storage.
     *
     * @param roomId non-blank room identifier (same logical id as {@link #JOIN_ROOM})
     */
    public record DeleteRoomPayload(String roomId) {}

    /**
     * Encodes {@code DELETE_ROOM} as {@code {"roomId":"..."}}.
     */
    public static ByteBuffer encodeDeleteRoom(String roomId) {
        if (roomId == null) throw new IllegalArgumentException("roomId must not be null");
        JsonObject o = new JsonObject();
        o.addProperty("roomId", roomId);
        return encode(new Message(MessageType.DELETE_ROOM, GSON.toJson(o)));
    }

    /**
     * Parses {@code DELETE_ROOM} payload: JSON object {@code { "roomId": "..." }}.
     */
    public static DeleteRoomPayload decodeDeleteRoom(Message msg) {
        if (msg == null) throw new IllegalArgumentException("msg must not be null");
        if (msg.type() != MessageType.DELETE_ROOM) {
            throw new IllegalArgumentException("expected DELETE_ROOM, got " + msg.type());
        }
        String raw = msg.payload();
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("DELETE_ROOM payload is blank");
        }
        try {
            JsonObject o = JsonParser.parseString(raw.strip()).getAsJsonObject();
            if (!o.has("roomId") || o.get("roomId").isJsonNull()) {
                throw new IllegalArgumentException("DELETE_ROOM missing roomId");
            }
            String rid = o.get("roomId").getAsString().strip();
            if (rid.isBlank()) {
                throw new IllegalArgumentException("DELETE_ROOM room id is blank");
            }
            return new DeleteRoomPayload(rid);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed DELETE_ROOM: " + e.getMessage(), e);
        }
    }

    /** Encodes {@code ROOM_DELETED} with an empty UTF-8 payload (server notifies clients the room is gone). */
    public static ByteBuffer encodeRoomDeleted() {
        return encode(new Message(MessageType.ROOM_DELETED, ""));
    }

    // -------------------------------------------------------------------------
    // BOARD_LIST_UPDATE (server → client)
    // -------------------------------------------------------------------------

    private static final Type BOARD_ID_LIST_TYPE = new TypeToken<List<String>>() {}.getType();

    /**
     * Encodes {@code BOARD_LIST_UPDATE}: a JSON array of board id strings, e.g.
     * {@code ["Default","Diagrams","Math"]}.
     */
    public static ByteBuffer encodeBoardListUpdate(List<String> boardIds) {
        if (boardIds == null) throw new IllegalArgumentException("boardIds must not be null");
        String json = GSON.toJson(boardIds);
        return encode(new Message(MessageType.BOARD_LIST_UPDATE, json));
    }

    /**
     * Decodes the payload of a {@link MessageType#BOARD_LIST_UPDATE} message.
     */
    public static List<String> decodeBoardListUpdate(Message msg) {
        if (msg == null) throw new IllegalArgumentException("msg must not be null");
        if (msg.type() != MessageType.BOARD_LIST_UPDATE) {
            throw new IllegalArgumentException("expected BOARD_LIST_UPDATE, got " + msg.type());
        }
        List<String> list = GSON.fromJson(msg.payload(), BOARD_ID_LIST_TYPE);
        return list != null ? List.copyOf(list) : List.of();
    }

    // -------------------------------------------------------------------------
    // UDP_ADMISSION (server → client)
    // -------------------------------------------------------------------------

    /**
     * JSON body for {@link MessageType#UDP_ADMISSION}: opaque token the client sends on the UDP
     * data plane for registration and audio relay.
     */
    public record UdpAdmissionPayload(String udpToken) {}

    public static ByteBuffer encodeUdpAdmission(String udpToken) {
        if (udpToken == null || udpToken.isBlank()) {
            throw new IllegalArgumentException("udpToken must not be null or blank");
        }
        return encodeObject(MessageType.UDP_ADMISSION, new UdpAdmissionPayload(udpToken));
    }

    public static String decodeUdpAdmission(Message msg) {
        if (msg == null) throw new IllegalArgumentException("msg must not be null");
        if (msg.type() != MessageType.UDP_ADMISSION) {
            throw new IllegalArgumentException("expected UDP_ADMISSION, got " + msg.type());
        }
        UdpAdmissionPayload p = GSON.fromJson(msg.payload(), UdpAdmissionPayload.class);
        if (p == null || p.udpToken() == null || p.udpToken().isBlank()) {
            throw new IllegalArgumentException("UDP_ADMISSION missing udpToken");
        }
        return p.udpToken();
    }

    // -------------------------------------------------------------------------
    // PING / PONG (telemetry RTT)
    // -------------------------------------------------------------------------

    /**
     * JSON body for {@link MessageType#PING} and {@link MessageType#PONG}: milliseconds since
     * Unix epoch at the ping origin (client clock when the PING was sent; echoed unchanged in PONG).
     */
    public record PingPongPayload(long t) {}

    public static ByteBuffer encodePing(long originMillis) {
        return encodeObject(MessageType.PING, new PingPongPayload(originMillis));
    }

    public static ByteBuffer encodePong(long originMillis) {
        return encodeObject(MessageType.PONG, new PingPongPayload(originMillis));
    }

    /**
     * Reads the {@code t} field from a {@code PING} or {@code PONG} payload.
     */
    public static long decodePingPongOrigin(Message msg) {
        if (msg == null) throw new IllegalArgumentException("msg must not be null");
        if (msg.type() != MessageType.PING && msg.type() != MessageType.PONG) {
            throw new IllegalArgumentException("expected PING or PONG, got " + msg.type());
        }
        PingPongPayload p = GSON.fromJson(msg.payload(), PingPongPayload.class);
        if (p == null) {
            throw new IllegalArgumentException("missing PING/PONG payload");
        }
        return p.t();
    }

    // -------------------------------------------------------------------------
    // CLEAR_USER_SHAPES helpers
    // -------------------------------------------------------------------------

    /**
     * Encodes a {@code CLEAR_USER_SHAPES} frame whose payload is the JSON
     * representation of {@code clientId} (a JSON string literal, e.g.
     * {@code "\"user-42\""}).
     *
     * @param clientId the session-scoped identifier of the user requesting the
     *                 scoped clear; must not be {@code null}
     * @return a flipped {@code ByteBuffer} ready for channel writes
     */
    public static ByteBuffer encodeClearUserShapes(String clientId) {
        if (clientId == null) throw new IllegalArgumentException("clientId must not be null");
        return encode(new Message(MessageType.CLEAR_USER_SHAPES, GSON.toJson(clientId)));
    }

    /**
     * Extracts the {@code clientId} from the payload of a
     * {@code CLEAR_USER_SHAPES} message produced by
     * {@link #encodeClearUserShapes}.
     *
     * @param msg a decoded {@code CLEAR_USER_SHAPES} message; must not be
     *            {@code null}
     * @return the clientId string embedded in the payload
     */
    public static String decodeClearUserShapes(Message msg) {
        if (msg == null) throw new IllegalArgumentException("msg must not be null");
        return GSON.fromJson(msg.payload(), String.class);
    }

    // -------------------------------------------------------------------------
    // TEXT_UPDATE helpers
    // -------------------------------------------------------------------------

    /**
     * Immutable value object that represents the payload of a
     * {@link MessageType#TEXT_UPDATE} frame.
     *
     * <p>All fields are intentionally package-accessible via the record accessor
     * methods; no defensive copies are needed because {@code String} and
     * {@code double} are already immutable / value types.
     *
     * @param objectId    stable UUID that identifies the text node being edited
     * @param clientId    session-scoped identifier of the typing client
     * @param authorName  human-readable display name of the typing peer; never {@code null}
     * @param x           X anchor coordinate of the text node on the canvas
     * @param y           Y anchor coordinate of the text node on the canvas
     * @param currentText the in-progress (uncommitted) text content
     */
    public record TextUpdatePayload(
            String objectId,
            String clientId,
            String authorName,
            double x,
            double y,
            String currentText) {}

    /**
     * Encodes a {@code TEXT_UPDATE} frame from its constituent fields.
     *
     * @param objectId    the UUID of the text node being edited
     * @param clientId    session-scoped identifier of the originating client
     * @param authorName  human-readable display name of the originating client; may be empty
     * @param x           X anchor coordinate on the canvas
     * @param y           Y anchor coordinate on the canvas
     * @param currentText the transient (uncommitted) text content; must not be {@code null}
     * @return a flipped {@code ByteBuffer} ready for channel writes
     */
    public static ByteBuffer encodeTextUpdate(UUID objectId, String clientId, String authorName,
                                              double x, double y, String currentText) {
        if (objectId    == null) throw new IllegalArgumentException("objectId must not be null");
        if (clientId    == null) throw new IllegalArgumentException("clientId must not be null");
        if (currentText == null) throw new IllegalArgumentException("currentText must not be null");
        TextUpdatePayload payload = new TextUpdatePayload(
                objectId.toString(), clientId,
                authorName != null ? authorName : "",
                x, y, currentText);
        return encodeObject(MessageType.TEXT_UPDATE, payload);
    }

    /**
     * Decodes the payload of a {@link MessageType#TEXT_UPDATE} message.
     *
     * @param msg a decoded message whose {@link Message#type()} is
     *            {@link MessageType#TEXT_UPDATE}; must not be {@code null}
     * @return the deserialized {@link TextUpdatePayload}
     * @throws IllegalArgumentException if {@code msg} is {@code null} or the
     *         payload is malformed
     */
    public static TextUpdatePayload decodeTextUpdate(Message msg) {
        if (msg == null) throw new IllegalArgumentException("msg must not be null");
        return GSON.fromJson(msg.payload(), TextUpdatePayload.class);
    }

    // -------------------------------------------------------------------------
    // VOICE_STATE helpers
    // -------------------------------------------------------------------------

    /**
     * JSON body for {@link MessageType#VOICE_STATE}: hardware microphone mute state for a peer.
     */
    public record VoiceStatePayload(String clientId, boolean isMuted) {}

    public static ByteBuffer encodeVoiceState(String clientId, boolean isMuted) {
        if (clientId == null) throw new IllegalArgumentException("clientId must not be null");
        return encodeObject(MessageType.VOICE_STATE, new VoiceStatePayload(clientId, isMuted));
    }

    public static VoiceStatePayload decodeVoiceState(Message msg) {
        if (msg == null) throw new IllegalArgumentException("msg must not be null");
        if (msg.type() != MessageType.VOICE_STATE) {
            throw new IllegalArgumentException("expected VOICE_STATE, got " + msg.type());
        }
        VoiceStatePayload p = GSON.fromJson(msg.payload(), VoiceStatePayload.class);
        if (p == null || p.clientId() == null) {
            throw new IllegalArgumentException("VOICE_STATE missing clientId");
        }
        return p;
    }

    // -------------------------------------------------------------------------
    // CURSOR_SYNC helpers
    // -------------------------------------------------------------------------

    /**
     * JSON body for {@link MessageType#CURSOR_SYNC}: ephemeral multiplayer cursor position.
     */
    public record CursorSyncPayload(String clientId, String authorName, double x, double y) {}

    public static ByteBuffer encodeCursorSync(String clientId, String authorName, double x, double y) {
        if (clientId == null) throw new IllegalArgumentException("clientId must not be null");
        return encodeObject(MessageType.CURSOR_SYNC,
                new CursorSyncPayload(clientId, authorName != null ? authorName : "", x, y));
    }

    public static CursorSyncPayload decodeCursorSync(Message msg) {
        if (msg == null) throw new IllegalArgumentException("msg must not be null");
        if (msg.type() != MessageType.CURSOR_SYNC) {
            throw new IllegalArgumentException("expected CURSOR_SYNC, got " + msg.type());
        }
        CursorSyncPayload p = GSON.fromJson(msg.payload(), CursorSyncPayload.class);
        if (p == null || p.clientId() == null) {
            throw new IllegalArgumentException("CURSOR_SYNC missing clientId");
        }
        return p;
    }

    // -------------------------------------------------------------------------
    // MODERATION_ACTION / SESSION_REVOKED
    // -------------------------------------------------------------------------

    /**
     * JSON body for {@link MessageType#MODERATION_ACTION}: cluster-wide user moderation command.
     */
    public record ModerationActionPayload(String actionType, String targetClientId, String reason) {}

    public static ByteBuffer encodeModerationAction(String actionType, String targetClientId, String reason) {
        if (actionType == null) throw new IllegalArgumentException("actionType must not be null");
        if (targetClientId == null) throw new IllegalArgumentException("targetClientId must not be null");
        JsonObject o = new JsonObject();
        o.addProperty("actionType", actionType);
        o.addProperty("targetClientId", targetClientId);
        o.addProperty("reason", reason != null ? reason : "");
        return encode(new Message(MessageType.MODERATION_ACTION, GSON.toJson(o)));
    }

    public static ModerationActionPayload decodeModerationAction(Message msg) {
        if (msg == null) throw new IllegalArgumentException("msg must not be null");
        if (msg.type() != MessageType.MODERATION_ACTION) {
            throw new IllegalArgumentException("expected MODERATION_ACTION, got " + msg.type());
        }
        String raw = msg.payload();
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("MODERATION_ACTION payload is blank");
        }
        try {
            JsonObject o = JsonParser.parseString(raw.strip()).getAsJsonObject();
            if (!o.has("actionType") || o.get("actionType").isJsonNull()) {
                throw new IllegalArgumentException("MODERATION_ACTION missing actionType");
            }
            if (!o.has("targetClientId") || o.get("targetClientId").isJsonNull()) {
                throw new IllegalArgumentException("MODERATION_ACTION missing targetClientId");
            }
            String actionType = o.get("actionType").getAsString().strip();
            String targetClientId = o.get("targetClientId").getAsString().strip();
            if (actionType.isBlank()) {
                throw new IllegalArgumentException("MODERATION_ACTION actionType is blank");
            }
            if (targetClientId.isBlank()) {
                throw new IllegalArgumentException("MODERATION_ACTION targetClientId is blank");
            }
            String reason = o.has("reason") && !o.get("reason").isJsonNull()
                    ? o.get("reason").getAsString()
                    : "";
            return new ModerationActionPayload(actionType, targetClientId, reason);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed MODERATION_ACTION: " + e.getMessage(), e);
        }
    }

    public static ByteBuffer encodeSessionRevoked(String reason) {
        JsonObject o = new JsonObject();
        o.addProperty("reason", reason != null ? reason : "");
        return encode(new Message(MessageType.SESSION_REVOKED, GSON.toJson(o)));
    }

    public static String decodeSessionRevoked(Message msg) {
        if (msg == null) throw new IllegalArgumentException("msg must not be null");
        if (msg.type() != MessageType.SESSION_REVOKED) {
            throw new IllegalArgumentException("expected SESSION_REVOKED, got " + msg.type());
        }
        String raw = msg.payload();
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            JsonObject o = JsonParser.parseString(raw.strip()).getAsJsonObject();
            if (!o.has("reason") || o.get("reason").isJsonNull()) {
                return "";
            }
            return o.get("reason").getAsString();
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed SESSION_REVOKED: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // ROLE_UPDATE helpers
    // -------------------------------------------------------------------------

    /**
     * JSON body for {@link MessageType#ROLE_UPDATE}: permission change for {@code newHostClientId}
     * (affected client). {@code roomHostClientId} is set on join sync; omitted for host migration
     * and moderation when Gson decodes legacy frames.
     */
    public record RoleUpdatePayload(String newHostClientId, int newPermissions, String roomHostClientId) {}

    public static ByteBuffer encodeRoleUpdate(String affectedClientId, int permissions) {
        return encodeRoleUpdate(affectedClientId, permissions, null);
    }

    public static ByteBuffer encodeRoleUpdate(String affectedClientId, int permissions, String roomHostClientId) {
        if (affectedClientId == null) {
            throw new IllegalArgumentException("affectedClientId must not be null");
        }
        return encodeObject(MessageType.ROLE_UPDATE,
                new RoleUpdatePayload(affectedClientId, permissions, roomHostClientId));
    }

    public static RoleUpdatePayload decodeRoleUpdate(Message msg) {
        if (msg == null) throw new IllegalArgumentException("msg must not be null");
        if (msg.type() != MessageType.ROLE_UPDATE) {
            throw new IllegalArgumentException("expected ROLE_UPDATE, got " + msg.type());
        }
        RoleUpdatePayload p = GSON.fromJson(msg.payload(), RoleUpdatePayload.class);
        if (p == null || p.newHostClientId() == null || p.newHostClientId().isBlank()) {
            throw new IllegalArgumentException("ROLE_UPDATE missing newHostClientId");
        }
        return p;
    }

    // -------------------------------------------------------------------------
    // BOARD_SWITCH helpers
    // -------------------------------------------------------------------------

    public record BoardSwitchPayload(String clientId, String newBoardId) {}

    public static ByteBuffer encodeBoardSwitch(String clientId, String newBoardId) {
        if (clientId == null) throw new IllegalArgumentException("clientId must not be null");
        if (newBoardId == null) throw new IllegalArgumentException("newBoardId must not be null");
        return encodeObject(MessageType.BOARD_SWITCH, new BoardSwitchPayload(clientId, newBoardId));
    }

    public static BoardSwitchPayload decodeBoardSwitch(Message msg) {
        if (msg == null) throw new IllegalArgumentException("msg must not be null");
        if (msg.type() != MessageType.BOARD_SWITCH) {
            throw new IllegalArgumentException("expected BOARD_SWITCH, got " + msg.type());
        }
        BoardSwitchPayload p = GSON.fromJson(msg.payload(), BoardSwitchPayload.class);
        if (p == null || p.clientId() == null || p.clientId().isBlank()) {
            throw new IllegalArgumentException("BOARD_SWITCH missing clientId");
        }
        if (p.newBoardId() == null || p.newBoardId().isBlank()) {
            throw new IllegalArgumentException("BOARD_SWITCH missing newBoardId");
        }
        return p;
    }

    // -------------------------------------------------------------------------
    // TOGGLE_BOARD_LOCK helpers
    // -------------------------------------------------------------------------

    public record BoardLockPayload(boolean locked) {}

    /** Client→server: set room board-creation lock to {@code locked}. */
    public static ByteBuffer encodeBoardLockCommand(boolean locked) {
        return encodeObject(MessageType.TOGGLE_BOARD_LOCK, new BoardLockPayload(locked));
    }

    public static BoardLockPayload decodeBoardLockCommand(Message msg) {
        return decodeBoardLockPayload(msg);
    }

    /** Server→room: broadcast current lock state. */
    public static ByteBuffer encodeBoardLockState(boolean locked) {
        return encodeBoardLockCommand(locked);
    }

    public static BoardLockPayload decodeBoardLockState(Message msg) {
        return decodeBoardLockPayload(msg);
    }

    private static BoardLockPayload decodeBoardLockPayload(Message msg) {
        if (msg == null) throw new IllegalArgumentException("msg must not be null");
        if (msg.type() != MessageType.TOGGLE_BOARD_LOCK) {
            throw new IllegalArgumentException("expected TOGGLE_BOARD_LOCK, got " + msg.type());
        }
        BoardLockPayload p = GSON.fromJson(msg.payload(), BoardLockPayload.class);
        if (p == null) {
            throw new IllegalArgumentException("TOGGLE_BOARD_LOCK missing payload");
        }
        return p;
    }

    // -------------------------------------------------------------------------
    // MEDIA_CONTROL / MEDIA_STATE_UPDATE helpers
    // -------------------------------------------------------------------------

    /**
     * Client→server media command. {@code action}: PLAY, PAUSE, SEEK, LOAD, STOP.
     * {@code targetId} is the video id for LOAD; empty string otherwise.
     */
    public record MediaControlPayload(String action, double requestedTime, String targetId) {}

    /**
     * Server→room authoritative media snapshot.
     *
     * <p>Playback position at client time {@code t}:
     * <ul>
     *   <li>{@code PLAYING}: {@code mediaTimeSeconds + (t - serverEpochMs) / 1000.0}</li>
     *   <li>{@code PAUSED} (and after LOAD): {@code mediaTimeSeconds}</li>
     * </ul>
     */
    public record MediaStatePayload(String state, double mediaTimeSeconds, long serverEpochMs, String videoId) {}

    public static ByteBuffer encodeMediaControl(MediaControlPayload payload) {
        if (payload == null) throw new IllegalArgumentException("payload must not be null");
        return encodeObject(MessageType.MEDIA_CONTROL, payload);
    }

    public static MediaControlPayload decodeMediaControl(Message msg) {
        if (msg == null) throw new IllegalArgumentException("msg must not be null");
        if (msg.type() != MessageType.MEDIA_CONTROL) {
            throw new IllegalArgumentException("expected MEDIA_CONTROL, got " + msg.type());
        }
        MediaControlPayload p = GSON.fromJson(msg.payload(), MediaControlPayload.class);
        if (p == null || p.action() == null || p.action().isBlank()) {
            throw new IllegalArgumentException("MEDIA_CONTROL missing action");
        }
        return p;
    }

    public static ByteBuffer encodeMediaState(MediaStatePayload payload) {
        if (payload == null) throw new IllegalArgumentException("payload must not be null");
        return encodeObject(MessageType.MEDIA_STATE_UPDATE, payload);
    }

    public static MediaStatePayload decodeMediaState(Message msg) {
        if (msg == null) throw new IllegalArgumentException("msg must not be null");
        if (msg.type() != MessageType.MEDIA_STATE_UPDATE) {
            throw new IllegalArgumentException("expected MEDIA_STATE_UPDATE, got " + msg.type());
        }
        MediaStatePayload p = GSON.fromJson(msg.payload(), MediaStatePayload.class);
        if (p == null || p.state() == null || p.state().isBlank()) {
            throw new IllegalArgumentException("MEDIA_STATE_UPDATE missing state");
        }
        if (p.videoId() == null) {
            throw new IllegalArgumentException("MEDIA_STATE_UPDATE missing videoId");
        }
        return p;
    }

    /**
     * Applies a {@link MessageType#MEDIA_CONTROL} command to produce the next authoritative
     * {@link MediaStatePayload} with {@code serverEpochMs} as the anchor wall clock.
     */
    public static MediaStatePayload deriveMediaState(
            MediaControlPayload control,
            MediaStatePayload prior,
            long serverEpochMs) {
        if (control == null) throw new IllegalArgumentException("control must not be null");
        String action = control.action().strip().toUpperCase();
        String priorVideo = prior != null ? prior.videoId() : "";
        return switch (action) {
            case "PLAY" -> new MediaStatePayload(
                    "PLAYING",
                    control.requestedTime(),
                    serverEpochMs,
                    priorVideo);
            case "PAUSE" -> new MediaStatePayload(
                    "PAUSED",
                    control.requestedTime(),
                    serverEpochMs,
                    priorVideo);
            case "SEEK" -> new MediaStatePayload(
                    prior != null ? prior.state() : "PAUSED",
                    control.requestedTime(),
                    serverEpochMs,
                    priorVideo);
            case "LOAD" -> new MediaStatePayload(
                    "PAUSED",
                    control.requestedTime(),
                    serverEpochMs,
                    control.targetId() != null ? control.targetId() : "");
            case "STOP" -> new MediaStatePayload("STOP", 0, serverEpochMs, "");
            default -> throw new IllegalArgumentException("unsupported MEDIA_CONTROL action: " + action);
        };
    }

    /**
     * Exposes the shared {@link Gson} instance for callers that need custom
     * serialization (e.g. registering type adapters for {@code UUID} or
     * {@code sealed} hierarchies).
     */
    public static Gson gson() {
        return GSON;
    }
}
