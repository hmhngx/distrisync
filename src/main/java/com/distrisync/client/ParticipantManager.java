package com.distrisync.client;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Central store of room participants and their audio UI state. Inbound {@code VOICE_STATE}
 * frames update {@link Participant#isMutedProperty()} on the FX thread; speaking activity
 * remains on the UDP path and is updated separately when wired.
 *
 * <p>Typically one instance per {@link NetworkClient} session ({@link NetworkClient#getParticipantManager()}).
 */
public final class ParticipantManager implements VoiceStateListener {

    private final ConcurrentHashMap<String, Participant> byClientId = new ConcurrentHashMap<>();
    private final ObservableList<Participant> participants = FXCollections.observableArrayList();

    /**
     * Live list for UI binding (ListView, etc.). Mutate only via this manager on the FX thread.
     */
    public ObservableList<Participant> getParticipants() {
        return participants;
    }

    /**
     * @return the participant for {@code clientId}, or {@code null} if not in the roster
     */
    public Participant get(String clientId) {
        if (clientId == null) {
            return null;
        }
        return byClientId.get(clientId);
    }

    /**
     * Adds or updates a participant display name on the FX thread.
     */
    public void putParticipant(String clientId, String displayName) {
        if (clientId == null) {
            throw new IllegalArgumentException("clientId must not be null");
        }
        runOnFxThread(() -> ensureParticipantOnFx(clientId, displayName));
    }

    /**
     * Removes every participant from the roster (e.g. when returning to the lobby).
     */
    public void clear() {
        runOnFxThread(() -> {
            byClientId.clear();
            participants.clear();
        });
    }

    @Override
    public void onVoiceState(String clientId, boolean isMuted) {
        if (clientId == null || clientId.isBlank()) {
            return;
        }
        runOnFxThread(() -> applyVoiceStateOnFx(clientId, isMuted));
    }

    private void applyVoiceStateOnFx(String clientId, boolean isMuted) {
        Participant participant = ensureParticipantOnFx(clientId, clientId);
        participant.setMuted(isMuted);
    }

    /**
     * Updates peer voice-activity for UI binding. May be called from the UDP receive thread;
     * marshals to the FX application thread.
     */
    public void setSpeaking(String clientId, boolean speaking) {
        if (clientId == null || clientId.isBlank()) {
            return;
        }
        runOnFxThread(() -> {
            Participant participant = ensureParticipantOnFx(clientId, clientId);
            participant.setSpeaking(speaking);
        });
    }

    private Participant ensureParticipantOnFx(String clientId, String displayName) {
        Participant existing = byClientId.get(clientId);
        if (existing != null) {
            if (displayName != null && !displayName.isBlank() && existing.getName().isBlank()) {
                existing.setName(displayName);
            }
            return existing;
        }
        Participant created = new Participant(clientId, displayName);
        byClientId.put(clientId, created);
        if (!participants.contains(created)) {
            participants.add(created);
        }
        return created;
    }

    private static void runOnFxThread(Runnable action) {
        try {
            if (Platform.isFxApplicationThread()) {
                action.run();
            } else {
                Platform.runLater(action);
            }
        } catch (IllegalStateException toolkitNotStarted) {
            action.run();
        }
    }
}
