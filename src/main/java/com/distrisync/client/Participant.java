package com.distrisync.client;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
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
}
