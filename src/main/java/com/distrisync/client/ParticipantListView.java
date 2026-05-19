package com.distrisync.client;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.SVGPath;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Top-right voice roster: avatar initials, speaking ring, and mute indicator per
 * {@link Participant} in a {@link ParticipantManager}.
 */
public final class ParticipantListView extends VBox {

    public static final String VIEW_ID = "participant-list-view";

    private static final String AVATAR_ID_PREFIX = "participant-avatar-";

    private final VBox rowsContainer = new VBox(6);
    private final Map<String, ParticipantRow> rowsByClientId = new HashMap<>();

    private ParticipantManager boundManager;
    private ListChangeListener<Participant> participantListListener;

    private String localClientId = "";
    private BiConsumer<String, String> kickHandler;
    private BiConsumer<String, String> revokeSpeakHandler;
    private boolean moderationButtonsVisible;

    public ParticipantListView() {
        setId(VIEW_ID);
        getStyleClass().addAll("participant-list-view", "floating-panel");
        setAlignment(Pos.TOP_RIGHT);
        setFillWidth(false);
        setPickOnBounds(false);
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        rowsContainer.setAlignment(Pos.CENTER_RIGHT);
        rowsContainer.setFillWidth(false);
        getChildren().add(rowsContainer);
    }

    /**
     * Binds to {@code manager}'s {@link ParticipantManager#getParticipants()} for dynamic rows.
     * Must be called on the JavaFX application thread.
     */
    public void bindTo(ParticipantManager manager) {
        if (boundManager == manager) {
            return;
        }
        unbindInternal();
        boundManager = manager;
        if (manager == null) {
            return;
        }

        participantListListener = change -> {
            while (change.next()) {
                if (change.wasRemoved()) {
                    for (Participant removed : change.getRemoved()) {
                        removeParticipantRow(removed.getClientId());
                    }
                }
                if (change.wasAdded()) {
                    for (Participant added : change.getAddedSubList()) {
                        addParticipantRow(added);
                    }
                }
            }
        };
        manager.getParticipants().addListener(participantListListener);
        for (Participant participant : manager.getParticipants()) {
            addParticipantRow(participant);
        }
    }

    /**
     * @param localClientId session id of this user; kick is hidden for that row
     */
    public void setLocalClientId(String localClientId) {
        this.localClientId = localClientId != null ? localClientId : "";
    }

    /**
     * @param handler invoked with {@code (targetClientId, displayName)} when kick is confirmed
     */
    public void setKickHandler(BiConsumer<String, String> handler) {
        this.kickHandler = handler;
    }

    /**
     * @param handler invoked with {@code (targetClientId, displayName)} when revoke-speak is pressed
     */
    public void setRevokeSpeakHandler(BiConsumer<String, String> handler) {
        this.revokeSpeakHandler = handler;
    }

    /**
     * Shows or hides kick / revoke-speak controls on all rows (call from {@code bindPermissionsToUI}).
     */
    public void setKickButtonsVisible(boolean visible) {
        moderationButtonsVisible = visible;
        for (ParticipantRow row : rowsByClientId.values()) {
            row.applyModerationVisibility();
        }
    }

    public void unbind() {
        if (Platform.isFxApplicationThread()) {
            unbindInternal();
        } else {
            Platform.runLater(this::unbindInternal);
        }
    }

    /** Stable node id for TestFX {@code lookup("#…")}. */
    public static String avatarNodeId(String clientId) {
        if (clientId == null) {
            return AVATAR_ID_PREFIX + "unknown";
        }
        return AVATAR_ID_PREFIX + clientId.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private void unbindInternal() {
        if (boundManager != null && participantListListener != null) {
            boundManager.getParticipants().removeListener(participantListListener);
        }
        participantListListener = null;
        boundManager = null;
        rowsByClientId.clear();
        rowsContainer.getChildren().clear();
    }

    private void addParticipantRow(Participant participant) {
        if (participant == null) {
            return;
        }
        String clientId = participant.getClientId();
        if (rowsByClientId.containsKey(clientId)) {
            return;
        }
        ParticipantRow row = new ParticipantRow(participant);
        rowsByClientId.put(clientId, row);
        rowsContainer.getChildren().add(row.root);
        row.syncFromParticipant();
    }

    private void removeParticipantRow(String clientId) {
        ParticipantRow row = rowsByClientId.remove(clientId);
        if (row != null) {
            row.dispose();
            rowsContainer.getChildren().remove(row.root);
        }
    }

    private static String initialsFor(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return "?";
        }
        String trimmed = displayName.trim();
        String[] parts = trimmed.split("\\s+");
        if (parts.length >= 2) {
            return ("" + parts[0].charAt(0) + parts[1].charAt(0)).toUpperCase();
        }
        return trimmed.substring(0, Math.min(2, trimmed.length())).toUpperCase();
    }

    private static Node createMutedMicIcon() {
        SVGPath mic = new SVGPath();
        mic.setContent("M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm-9-3h2c0 3.31 2.69 6 6 6s6-2.69 6-6h2c0 4.42-3.58 8-8 8s-8-3.58-8-8z");
        mic.setFill(Color.web("#EF4444"));
        mic.setScaleX(0.45);
        mic.setScaleY(0.45);

        Line slash = new Line(1, 1, 15, 15);
        slash.setStroke(Color.web("#EF4444"));
        slash.setStrokeWidth(2);

        StackPane icon = new StackPane(mic, slash);
        icon.getStyleClass().add("participant-mute-icon");
        icon.setMinSize(16, 16);
        icon.setPrefSize(16, 16);
        icon.setMaxSize(16, 16);
        icon.setVisible(false);
        icon.setManaged(false);
        return icon;
    }

    private final class ParticipantRow {
        private final Participant participant;
        private final HBox root;
        private final StackPane avatar;
        private final Label initialsLabel;
        private final Label nameLabel;
        private final Node muteIcon;
        private final Button revokeSpeakBtn;
        private final Button kickBtn;

        private final ChangeListener<String> nameListener;
        private final ChangeListener<Boolean> mutedListener;
        private final ChangeListener<Boolean> speakingListener;

        ParticipantRow(Participant participant) {
            this.participant = participant;

            avatar = new StackPane();
            avatar.setId(avatarNodeId(participant.getClientId()));
            avatar.getStyleClass().add("participant-avatar");
            avatar.setMinSize(36, 36);
            avatar.setPrefSize(36, 36);
            avatar.setMaxSize(36, 36);

            initialsLabel = new Label(initialsFor(participant.getName()));
            initialsLabel.getStyleClass().add("participant-avatar-initials");
            avatar.getChildren().add(initialsLabel);

            nameLabel = new Label(participant.getName());
            nameLabel.getStyleClass().add("participant-name");

            muteIcon = createMutedMicIcon();

            revokeSpeakBtn = new Button("\uD83D\uDD07");
            revokeSpeakBtn.getStyleClass().addAll("participant-revoke-speak-btn");
            revokeSpeakBtn.setFocusTraversable(false);
            revokeSpeakBtn.setMnemonicParsing(false);
            revokeSpeakBtn.setTooltip(new javafx.scene.control.Tooltip("Revoke microphone"));
            revokeSpeakBtn.setOnAction(e -> {
                if (revokeSpeakHandler != null) {
                    revokeSpeakHandler.accept(participant.getClientId(), participant.getName());
                }
            });

            kickBtn = new Button("✕");
            kickBtn.getStyleClass().addAll("danger-btn", "participant-kick-btn");
            kickBtn.setFocusTraversable(false);
            kickBtn.setMnemonicParsing(false);
            kickBtn.setTooltip(new javafx.scene.control.Tooltip("Remove from room"));
            kickBtn.setOnAction(e -> {
                if (kickHandler != null) {
                    kickHandler.accept(participant.getClientId(), participant.getName());
                }
            });
            applyModerationVisibility();

            root = new HBox(8, avatar, nameLabel, muteIcon, revokeSpeakBtn, kickBtn);
            root.setAlignment(Pos.CENTER_LEFT);
            root.getStyleClass().add("participant-row");

            nameListener = (obs, was, now) -> {
                nameLabel.setText(now != null ? now : "");
                initialsLabel.setText(initialsFor(now));
            };
            mutedListener = (obs, was, muted) -> applyMutedVisual(Boolean.TRUE.equals(muted));
            speakingListener = (obs, was, speaking) -> applySpeakingRing(Boolean.TRUE.equals(speaking));

            participant.nameProperty().addListener(nameListener);
            participant.isMutedProperty().addListener(mutedListener);
            participant.isSpeakingProperty().addListener(speakingListener);
        }

        void syncFromParticipant() {
            nameLabel.setText(participant.getName());
            initialsLabel.setText(initialsFor(participant.getName()));
            applyMutedVisual(participant.isMuted());
            applySpeakingRing(participant.isSpeaking());
            applyModerationVisibility();
        }

        void dispose() {
            participant.nameProperty().removeListener(nameListener);
            participant.isMutedProperty().removeListener(mutedListener);
            participant.isSpeakingProperty().removeListener(speakingListener);
        }

        private void applyMutedVisual(boolean muted) {
            muteIcon.setVisible(muted);
            muteIcon.setManaged(muted);
            avatar.setOpacity(muted ? 0.5 : 1.0);
        }

        private void applySpeakingRing(boolean speaking) {
            if (speaking) {
                if (!avatar.getStyleClass().contains("speaking-ring")) {
                    avatar.getStyleClass().add("speaking-ring");
                }
            } else {
                avatar.getStyleClass().remove("speaking-ring");
            }
        }

        private void applyModerationVisibility() {
            boolean isLocal = participant.getClientId().equals(localClientId);
            boolean show = moderationButtonsVisible && !isLocal;
            revokeSpeakBtn.setVisible(show);
            revokeSpeakBtn.setManaged(show);
            kickBtn.setVisible(show);
            kickBtn.setManaged(show);
        }
    }
}
