package com.distrisync.client;

import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.RoomPermissions;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * Room member view model for UI binding. All properties must be read and written on the
 * JavaFX application thread (see {@link ParticipantManager}).
 */
public final class Participant {

    private final String clientId;
    private final StringProperty name = new SimpleStringProperty("");
    private final BooleanProperty isMuted = new SimpleBooleanProperty(true);
    private final BooleanProperty isSpeaking = new SimpleBooleanProperty(false);
    private final StringProperty currentBoardId =
            new SimpleStringProperty(MessageCodec.DEFAULT_INITIAL_BOARD_ID);
    private final IntegerProperty permissions = new SimpleIntegerProperty(RoomPermissions.MEMBER);

    public Participant(String clientId, String displayName) {
        if (clientId == null) {
            throw new IllegalArgumentException("clientId must not be null");
        }
        this.clientId = clientId;
        name.set(displayName != null ? displayName : "");
    }

    public String getClientId() {
        return clientId;
    }

    public StringProperty nameProperty() {
        return name;
    }

    public String getName() {
        return name.get();
    }

    public void setName(String value) {
        name.set(value != null ? value : "");
    }

    public BooleanProperty isMutedProperty() {
        return isMuted;
    }

    public boolean isMuted() {
        return isMuted.get();
    }

    public void setMuted(boolean muted) {
        isMuted.set(muted);
    }

    public BooleanProperty isSpeakingProperty() {
        return isSpeaking;
    }

    public boolean isSpeaking() {
        return isSpeaking.get();
    }

    public void setSpeaking(boolean speaking) {
        isSpeaking.set(speaking);
    }

    public StringProperty currentBoardIdProperty() {
        return currentBoardId;
    }

    public String getCurrentBoardId() {
        return currentBoardId.get();
    }

    public void setCurrentBoardId(String boardId) {
        currentBoardId.set(boardId != null && !boardId.isBlank()
                ? boardId
                : MessageCodec.DEFAULT_INITIAL_BOARD_ID);
    }

    public IntegerProperty permissionsProperty() {
        return permissions;
    }

    public int getPermissions() {
        return permissions.get();
    }

    public void setPermissions(int perms) {
        permissions.set(perms);
    }
}
