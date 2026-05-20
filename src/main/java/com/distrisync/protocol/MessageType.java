package com.distrisync.protocol;

import java.util.HashMap;
import java.util.Map;

/**
 * One-byte discriminator that occupies the first byte of every DistriSync
 * binary frame.
 *
 * <pre>
 * Wire value  Meaning
 * ----------  -------
 * 0x01        HANDSHAKE    – initial client→server greeting (authorName, clientId); room via JOIN_ROOM
 * 0x02        SNAPSHOT     – full board state sent by server on join
 * 0x03        MUTATION     – incremental shape add / update
 * 0x04        UDP_POINTER  – ephemeral cursor-position broadcast (fire-and-forget)
 * 0x05        SHAPE_START  – peer begins drawing a new shape (tool, color, origin)
 * 0x06        SHAPE_UPDATE – incremental coordinate update for an in-progress shape
 * 0x07        SHAPE_COMMIT – peer finished drawing; peers should flush their transient view
 * 0x08        CLEAR_USER_SHAPES – erase all shapes owned by the requesting clientId; server broadcasts to all peers
 * 0x09        UNDO_REQUEST  – client requests deletion of one shape by UUID (payload: shapeId)
 * 0x0A        SHAPE_DELETE  – server confirms deletion; broadcast to all peers (payload: shapeId)
 * 0x0B        TEXT_UPDATE   – ephemeral live-typing event; relayed to all peers without persistence
 *                             payload: { objectId, clientId, x, y, currentText }
 * 0x0C        LOBBY_STATE   – server→client: JSON list of { roomId, userCount } for discovery
 * 0x0D        JOIN_ROOM     – client→server: JSON object { roomId, initialBoardId? }; legacy JSON string roomId accepted
 *                             server→client: JSON object { clientId, authorName } — peer entered the room
 * 0x0E        LEAVE_ROOM    – client→server: return to lobby (empty payload)
 *                             server→client: JSON string clientId — peer left or disconnected
 * 0x0F        SWITCH_BOARD      – client→server: JSON string target boardId (e.g. "Board-1")
 * 0x10        BOARD_LIST_UPDATE – server→client: JSON array of board id strings active in the room
 * 0x11        UDP_ADMISSION     – server→client: JSON object { udpToken } for joining the UDP audio data plane
 * 0x12        PING              – client→server: JSON object { t } — origin {@code System.currentTimeMillis()}
 * 0x13        PONG              – server→client: JSON object { t } — echoes the ping origin timestamp for RTT
 * 0x14        DELETE_ROOM       – client→server: JSON object { roomId } — request durable room removal
 * 0x15        ROOM_DELETED      – server→client: empty payload — room was destroyed; clients should return to lobby
 * 0x16        FETCH_LOBBY       – client→server: empty JSON object {} — pull current LOBBY_STATE for this connection only
 * 0x17        VOICE_STATE       – client→server→peers: JSON object { clientId, isMuted } — hardware mute toggle (not speaking activity)
 * 0x18        STATE_REQUEST     – backplane only: cold node requests room state hydration (payload: {})
 * 0x19        STATE_SNAPSHOT    – backplane only: hot node bulk board state (payload: same JSON array as SNAPSHOT)
 * 0x1A        CURSOR_SYNC       – ephemeral multiplayer cursor position (payload: clientId, authorName, x, y)
 * 0x1B        MODERATION_ACTION – client→server / backplane control: { actionType, targetClientId, reason }
 * 0x1C        SESSION_REVOKED   – server→client: session ended by moderation (payload: { reason })
 * 0x1D        ROLE_UPDATE       – server→client: host migration (payload: { newHostClientId, newPermissions })
 * 0x1E        BOARD_SWITCH      – server→room: peer active board (payload: { clientId, newBoardId })
 * 0x1F        TOGGLE_BOARD_LOCK – client→server: set { locked }; server→room: broadcast { locked }
 * 0x20        DELETE_BOARD      – client→server: JSON string boardId — room manager removes board (non-default)
 * 0x21        BOARD_DELETED     – server→client: JSON string boardId — board was removed; update UI
 * </pre>
 */
public enum MessageType {

    HANDSHAKE   ((byte) 0x01),
    SNAPSHOT    ((byte) 0x02),
    MUTATION    ((byte) 0x03),
    UDP_POINTER ((byte) 0x04),
    SHAPE_START ((byte) 0x05),
    SHAPE_UPDATE((byte) 0x06),
    SHAPE_COMMIT((byte) 0x07),
    CLEAR_USER_SHAPES((byte) 0x08),
    UNDO_REQUEST((byte) 0x09),
    SHAPE_DELETE((byte) 0x0A),
    TEXT_UPDATE ((byte) 0x0B),
    LOBBY_STATE ((byte) 0x0C),
    JOIN_ROOM   ((byte) 0x0D),
    LEAVE_ROOM  ((byte) 0x0E),
    SWITCH_BOARD((byte) 0x0F),
    BOARD_LIST_UPDATE((byte) 0x10),
    UDP_ADMISSION    ((byte) 0x11),
    PING             ((byte) 0x12),
    PONG             ((byte) 0x13),
    DELETE_ROOM      ((byte) 0x14),
    ROOM_DELETED     ((byte) 0x15),
    FETCH_LOBBY      ((byte) 0x16),
    VOICE_STATE      ((byte) 0x17),
    STATE_REQUEST    ((byte) 0x18),
    STATE_SNAPSHOT   ((byte) 0x19),
    CURSOR_SYNC      ((byte) 0x1A),
    MODERATION_ACTION((byte) 0x1B),
    SESSION_REVOKED  ((byte) 0x1C),
    ROLE_UPDATE      ((byte) 0x1D),
    BOARD_SWITCH     ((byte) 0x1E),
    TOGGLE_BOARD_LOCK((byte) 0x1F),
    DELETE_BOARD     ((byte) 0x20),
    BOARD_DELETED    ((byte) 0x21);

    private final byte wireCode;

    private static final Map<Byte, MessageType> BY_CODE;

    static {
        BY_CODE = new HashMap<>();
        for (MessageType t : values()) {
            BY_CODE.put(t.wireCode, t);
        }
    }

    MessageType(byte wireCode) {
        this.wireCode = wireCode;
    }

    /** The single byte written to (or read from) the wire. */
    public byte wireCode() {
        return wireCode;
    }

    /**
     * Reverse-lookup by wire byte.
     *
     * @throws IllegalArgumentException for unknown codes, so the codec can
     *         surface a clean error rather than a silent {@code null}.
     */
    public static MessageType fromWireCode(byte code) {
        MessageType type = BY_CODE.get(code);
        if (type == null) {
            throw new IllegalArgumentException(
                    String.format("Unknown MessageType wire code: 0x%02X", code));
        }
        return type;
    }
}
