package com.distrisync.client;

/**
 * Receives {@code BOARD_DELETED} when a workspace board was removed on the server.
 *
 * <p>Callbacks run on the {@code distrisync-read} thread. UI code should use
 * {@link javafx.application.Platform#runLater}.
 */
@FunctionalInterface
public interface BoardDeletedListener {

    void onBoardDeleted(String boardId);
}
