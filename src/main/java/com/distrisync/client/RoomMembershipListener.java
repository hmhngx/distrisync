package com.distrisync.client;

/**
 * Receives server→client room membership notifications ({@code JOIN_ROOM} peer-join,
 * {@code LEAVE_ROOM} peer-depart).
 *
 * <p>Callbacks run on the {@code distrisync-read} thread. UI code should use
 * {@link javafx.application.Platform#runLater}.
 */
public interface RoomMembershipListener {

    void onPeerJoined(String clientId, String authorName);

    void onPeerLeft(String clientId);
}
