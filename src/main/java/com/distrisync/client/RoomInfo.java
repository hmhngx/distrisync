package com.distrisync.client;

import com.distrisync.protocol.MessageCodec;

import java.util.List;

/**
 * One row in the lobby room list: identifier and live occupancy from {@code LOBBY_STATE}.
 */
public record RoomInfo(String roomId, int userCount) {

    public static RoomInfo from(MessageCodec.LobbyRoomEntry e) {
        return new RoomInfo(e.roomId(), e.userCount());
    }

    public static List<RoomInfo> copyOf(List<MessageCodec.LobbyRoomEntry> entries) {
        return entries.stream().map(RoomInfo::from).toList();
    }
}
