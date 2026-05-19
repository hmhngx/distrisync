package com.distrisync.client;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

/**
 * Room-level UI state synchronized from the server (e.g. board-creation lock).
 * Owned by {@link NetworkClient}; read and bound on the JavaFX application thread.
 */
public final class RoomState {

    private final BooleanProperty boardCreationLocked = new SimpleBooleanProperty(true);

    public BooleanProperty boardCreationLockedProperty() {
        return boardCreationLocked;
    }

    public boolean isBoardCreationLocked() {
        return boardCreationLocked.get();
    }

    void setBoardCreationLocked(boolean locked) {
        boardCreationLocked.set(locked);
    }

    void reset() {
        boardCreationLocked.set(true);
    }
}
