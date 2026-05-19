package com.distrisync.client;

/**
 * Receives {@code BOARD_SWITCH} when a peer changes their active workspace board.
 *
 * <p>Callbacks run on the {@code distrisync-read} thread. UI code should use
 * {@link javafx.application.Platform#runLater}.
 */
@FunctionalInterface
public interface BoardPresenceListener {

    void onPeerBoardSwitch(String clientId, String newBoardId);
}
