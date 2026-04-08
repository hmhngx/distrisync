package com.distrisync.client;

import java.util.List;

/**
 * Receives lobby discovery updates from {@link NetworkClient}.
 *
 * <p>Callbacks are invoked on the {@code distrisync-read} thread. UI code should
 * use {@link javafx.application.Platform#runLater} before touching scene graph state.
 */
@FunctionalInterface
public interface LobbyUpdateListener {

    /**
     * @param rooms current snapshot of active rooms and user counts; never {@code null}
     */
    void onLobbyUpdated(List<RoomInfo> rooms);
}
