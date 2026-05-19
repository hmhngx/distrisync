package com.distrisync.client;

import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.RoomPermissions;

import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
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

    private final IntegerProperty localPermissionsProperty =
            new SimpleIntegerProperty(RoomPermissions.SPECTATOR);

    private volatile String hostClientId = "";

    /**
     * Live list for UI binding (ListView, etc.). Mutate only via this manager on the FX thread.
     */
    public ObservableList<Participant> getParticipants() {
        return participants;
    }

    /**
     * Bitmask of capabilities for the local user in the current room.
     * Updated on join and {@code ROLE_UPDATE}; read on the FX thread.
     */
    public int getLocalPermissions() {
        return localPermissionsProperty.get();
    }

    public IntegerProperty localPermissionsProperty() {
        return localPermissionsProperty;
    }

    public String getHostClientId() {
        return hostClientId != null ? hostClientId : "";
    }

    /**
     * Updates the local user's permission mask (marshals to the FX application thread).
     */
    public void setLocalPermissions(int perms) {
        runOnFxThread(() -> localPermissionsProperty.set(perms));
    }

    public void setHostClientId(String clientId) {
        runOnFxThread(() -> hostClientId = clientId != null ? clientId : "");
    }

    public void updatePermissions(String clientId, int perms) {
        if (clientId == null || clientId.isBlank()) {
            return;
        }
        runOnFxThread(() -> {
            Participant participant = byClientId.get(clientId);
            if (participant == null) {
                participant = ensureParticipantOnFx(clientId, clientId);
            }
            participant.setPermissions(perms);
            if (RoomPermissions.canDeleteRoom(perms)) {
                hostClientId = clientId;
            }
        });
    }

    public void setCurrentBoardId(String clientId, String boardId) {
        if (clientId == null || clientId.isBlank()) {
            return;
        }
        runOnFxThread(() -> {
            Participant participant = ensureParticipantOnFx(clientId, clientId);
            participant.setCurrentBoardId(boardId);
        });
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
        runOnFxThread(() -> {
            Participant existing = byClientId.get(clientId);
            if (existing != null) {
                if (displayName != null && !displayName.isBlank() && !displayName.equals(clientId)) {
                    existing.setName(displayName);
                }
                return;
            }
            ensureParticipantOnFx(clientId, displayName);
        });
    }

    /**
     * Removes a peer from the roster (e.g. after server {@code LEAVE_ROOM} peer-depart).
     */
    public void remove(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return;
        }
        runOnFxThread(() -> {
            Participant removed = byClientId.remove(clientId);
            if (removed != null) {
                participants.remove(removed);
            }
        });
    }

    /**
     * Removes every participant from the roster (e.g. when returning to the lobby).
     */
    public void clear() {
        runOnFxThread(() -> {
            byClientId.clear();
            participants.clear();
            hostClientId = "";
            localPermissionsProperty.set(RoomPermissions.SPECTATOR);
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
            if (displayName != null && !displayName.isBlank()) {
                if (existing.getName().isBlank() || existing.getName().equals(clientId)) {
                    existing.setName(displayName);
                }
            }
            return existing;
        }
        Participant created = new Participant(clientId, displayName);
        if (clientId.equals(hostClientId)) {
            created.setPermissions(RoomPermissions.OWNER);
        }
        byClientId.put(clientId, created);
        if (!participants.contains(created)) {
            participants.add(created);
        }
        return created;
    }

    /**
     * Seeds a new peer with default member permissions and optional initial board id.
     */
    public void onPeerJoined(String clientId, String displayName, String initialBoardId) {
        if (clientId == null) {
            return;
        }
        runOnFxThread(() -> {
            Participant participant = ensureParticipantOnFx(clientId, displayName);
            participant.setPermissions(RoomPermissions.MEMBER);
            if (clientId.equals(hostClientId)) {
                participant.setPermissions(RoomPermissions.OWNER);
            }
            if (initialBoardId != null && !initialBoardId.isBlank()) {
                participant.setCurrentBoardId(initialBoardId);
            }
        });
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
