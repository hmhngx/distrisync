package com.distrisync.client;

import com.distrisync.protocol.RoomPermissions;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.TranslateTransition;
import javafx.scene.CacheHint;
import javafx.scene.control.OverrunStyle;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Right-aligned slide-out collaboration roster: participants grouped by active board,
 * role indicators, and moderation context menu.
 */
public final class CollaborationRoster extends HBox {

    public static final String VIEW_ID = "collaboration-roster";

    private static final String AVATAR_ID_PREFIX = "participant-avatar-";

    private static final double PANEL_WIDTH = 250;
    private static final double MAX_PANEL_HEIGHT = 380;
    private static final double SCROLL_VIEWPORT_HEIGHT = 220;
    private static final Duration SLIDE_MS = Duration.millis(250);
    private static final Duration BOARD_SECTION_ANIM_MS = Duration.millis(200);

    private final HBox slideRoot = new HBox(0);
    private final VBox panel = new VBox(0);
    private final VBox sectionsContainer = new VBox(8);
    private final Button toggleBtn = new Button(">");
    private final ToggleButton boardLockToggle = new ToggleButton("\uD83D\uDD12");
    private final ScrollPane scroll = new ScrollPane();

    private ParticipantManager boundManager;
    private ListChangeListener<Participant> participantListListener;
    private final Map<String, ParticipantRow> rowsByClientId = new HashMap<>();
    /** Per-board section expanded state survives {@link #rebuildSections()}. */
    private final Map<String, Boolean> boardSectionExpanded = new HashMap<>();

    private String localClientId = "";
    private boolean moderationEnabled;
    private boolean isExpanded = false;
    private TranslateTransition slideTransition;

    private BiConsumer<String, String> kickHandler;
    private BiConsumer<String, String> revokeSpeakHandler;
    private Consumer<Participant> grantSpeakHandler;
    private Consumer<Boolean> boardLockToggleHandler;
    private boolean boardLockSyncInProgress;

    public CollaborationRoster() {
        setId(VIEW_ID);
        getStyleClass().add("collaboration-roster-chrome");
        setAlignment(Pos.CENTER_RIGHT);
        setFillHeight(false);
        setMaxHeight(MAX_PANEL_HEIGHT);
        setPickOnBounds(false);

        toggleBtn.setId("roster-sidebar-toggle");
        toggleBtn.getStyleClass().add("roster-toggle-btn");
        toggleBtn.setFocusTraversable(false);
        toggleBtn.setMnemonicParsing(false);
        toggleBtn.setTooltip(new Tooltip("Toggle Roster"));
        toggleBtn.setOnAction(e -> toggleSlide());

        panel.getStyleClass().add("collaboration-roster");
        panel.setMinWidth(PANEL_WIDTH);
        panel.setPrefWidth(PANEL_WIDTH);
        panel.setMaxWidth(PANEL_WIDTH);
        panel.setMaxHeight(MAX_PANEL_HEIGHT);
        panel.setFillWidth(true);
        panel.setCache(true);
        panel.setCacheHint(CacheHint.SPEED);
        panel.setTranslateX(PANEL_WIDTH);

        Label title = new Label("Collaboration");
        title.getStyleClass().add("roster-title");

        boardLockToggle.getStyleClass().add("roster-board-lock-toggle");
        boardLockToggle.setVisible(false);
        boardLockToggle.setManaged(false);
        boardLockToggle.setFocusTraversable(false);
        boardLockToggle.setMnemonicParsing(false);
        boardLockToggle.setTooltip(new Tooltip("Lock room — only admins can create new boards"));
        boardLockToggle.setOnAction(e -> {
            if (!boardLockSyncInProgress && boardLockToggleHandler != null) {
                boardLockToggleHandler.accept(boardLockToggle.isSelected());
            }
        });

        HBox header = new HBox(8, title, boardLockToggle);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("roster-header");
        HBox.setHgrow(title, Priority.ALWAYS);

        sectionsContainer.getStyleClass().add("roster-sections");
        scroll.setContent(sectionsContainer);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(SCROLL_VIEWPORT_HEIGHT);
        scroll.setMaxHeight(SCROLL_VIEWPORT_HEIGHT);
        scroll.getStyleClass().add("roster-scroll");
        VBox.setVgrow(scroll, Priority.SOMETIMES);

        panel.getChildren().addAll(header, scroll);
        panel.setPadding(new Insets(10, 12, 10, 12));

        slideRoot.setAlignment(Pos.CENTER_RIGHT);
        slideRoot.getChildren().addAll(toggleBtn, panel);
        HBox.setHgrow(panel, Priority.NEVER);
        getChildren().setAll(slideRoot);
    }

    public void bindTo(ParticipantManager manager) {
        if (Platform.isFxApplicationThread()) {
            bindToInternal(manager);
        } else {
            Platform.runLater(() -> bindToInternal(manager));
        }
    }

    public void unbind() {
        if (Platform.isFxApplicationThread()) {
            unbindInternal();
        } else {
            Platform.runLater(this::unbindInternal);
        }
    }

    public void setLocalClientId(String clientId) {
        this.localClientId = clientId != null ? clientId : "";
        for (ParticipantRow row : rowsByClientId.values()) {
            row.refreshModerationUi();
        }
    }

    public void setModerationEnabled(boolean enabled) {
        this.moderationEnabled = enabled;
        rebuildSections();
    }

    public void setKickHandler(BiConsumer<String, String> handler) {
        this.kickHandler = handler;
    }

    public void setRevokeSpeakHandler(BiConsumer<String, String> handler) {
        this.revokeSpeakHandler = handler;
    }

    public void setGrantSpeakHandler(Consumer<Participant> handler) {
        this.grantSpeakHandler = handler;
    }

    public void setBoardLockToggleHandler(Consumer<Boolean> handler) {
        this.boardLockToggleHandler = handler;
    }

    public void setBoardLockToggleVisible(boolean visible) {
        boardLockToggle.setVisible(visible);
        boardLockToggle.setManaged(visible);
    }

    public void syncBoardLockCheckbox(boolean locked) {
        boardLockSyncInProgress = true;
        try {
            boardLockToggle.setSelected(locked);
            boardLockToggle.setText(locked ? "\uD83D\uDD12" : "\uD83D\uDD13");
            boardLockToggle.setTooltip(new Tooltip(locked
                    ? "Board creation locked — click to allow members to add boards"
                    : "Board creation unlocked — click to lock new boards"));
        } finally {
            boardLockSyncInProgress = false;
        }
    }

    public double panelWidth() {
        return PANEL_WIDTH;
    }

    /** Stable node id for TestFX {@code lookup("#…")}. */
    public static String avatarNodeId(String clientId) {
        if (clientId == null) {
            return AVATAR_ID_PREFIX + "unknown";
        }
        return AVATAR_ID_PREFIX + clientId.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    public boolean isExpanded() {
        return isExpanded;
    }

    /** @deprecated use {@link #isExpanded()} */
    @Deprecated
    public boolean isRosterOpen() {
        return isExpanded;
    }

    public void toggleSlide() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::toggleSlide);
            return;
        }
        if (slideTransition != null) {
            slideTransition.stop();
        }
        isExpanded = !isExpanded;
        double fromX = panel.getTranslateX();
        double toX = isExpanded ? 0 : PANEL_WIDTH;
        toggleBtn.setText(isExpanded ? "<" : ">");

        slideTransition = new TranslateTransition(SLIDE_MS, panel);
        slideTransition.setInterpolator(Interpolator.EASE_BOTH);
        slideTransition.setFromX(fromX);
        slideTransition.setToX(toX);
        slideTransition.setOnFinished(e -> slideTransition = null);
        slideTransition.play();
    }

    private void bindToInternal(ParticipantManager manager) {
        unbindInternal();
        boundManager = manager;
        if (manager == null) {
            return;
        }
        participantListListener = change -> rebuildSections();
        manager.getParticipants().addListener(participantListListener);
        rebuildSections();
    }

    private void unbindInternal() {
        if (boundManager != null && participantListListener != null) {
            boundManager.getParticipants().removeListener(participantListListener);
        }
        participantListListener = null;
        boundManager = null;
        for (ParticipantRow row : rowsByClientId.values()) {
            row.dispose();
        }
        rowsByClientId.clear();
        sectionsContainer.getChildren().clear();
    }

    private void rebuildSections() {
        if (boundManager == null) {
            return;
        }
        for (ParticipantRow row : rowsByClientId.values()) {
            row.dispose();
        }
        rowsByClientId.clear();
        sectionsContainer.getChildren().clear();

        Map<String, List<Participant>> byBoard = new LinkedHashMap<>();
        List<Participant> unknownBoard = new ArrayList<>();
        for (Participant p : boundManager.getParticipants()) {
            String board = p.getCurrentBoardId();
            if (board == null || board.isBlank()) {
                unknownBoard.add(p);
            } else {
                byBoard.computeIfAbsent(board, k -> new ArrayList<>()).add(p);
            }
        }

        List<String> boardIds = new ArrayList<>(byBoard.keySet());
        boardIds.sort(Comparator.naturalOrder());

        for (String boardId : boardIds) {
            sectionsContainer.getChildren().add(new BoardSection(boardId, byBoard.get(boardId)).root);
        }
        if (!unknownBoard.isEmpty()) {
            sectionsContainer.getChildren().add(new BoardSection("Unknown", unknownBoard).root);
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

    private static Node createSlashedMicIcon() {
        SVGPath mic = new SVGPath();
        mic.setContent("M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm-9-3h2c0 3.31 2.69 6 6 6s6-2.69 6-6h2c0 4.42-3.58 8-8 8s-8-3.58-8-8z");
        mic.setFill(Color.web("#EF4444"));
        mic.setScaleX(0.45);
        mic.setScaleY(0.45);
        Line slash = new Line(1, 1, 15, 15);
        slash.setStroke(Color.web("#EF4444"));
        slash.setStrokeWidth(2);
        StackPane icon = new StackPane(mic, slash);
        icon.getStyleClass().add("roster-no-speak-icon");
        icon.setMinSize(16, 16);
        icon.setPrefSize(16, 16);
        icon.setMaxSize(16, 16);
        icon.setVisible(false);
        icon.setManaged(false);
        return icon;
    }

    private static Label createCrownLabel() {
        Label crown = new Label("\u2655");
        crown.getStyleClass().add("roster-crown-icon");
        crown.setVisible(false);
        crown.setManaged(false);
        Tooltip.install(crown, new Tooltip("Room Owner / Admin"));
        return crown;
    }

    /**
     * One collapsible board group in the roster with chevron toggle and height animation.
     */
    private final class BoardSection {
        private final String boardId;
        private final VBox root;
        private final VBox membersContainer;
        private final Button chevronBtn;
        private final Label titleLabel;
        private final Label countLabel;
        private final HBox headerRow;

        private boolean expanded;
        private FadeTransition fadeAnim;

        BoardSection(String boardId, List<Participant> members) {
            this.boardId = boardId;
            this.expanded = boardSectionExpanded.getOrDefault(boardId, true);

            chevronBtn = new Button();
            chevronBtn.getStyleClass().addAll("ghost-button", "roster-board-chevron");
            chevronBtn.setFocusTraversable(false);
            chevronBtn.setMnemonicParsing(false);

            titleLabel = new Label(boardId);
            titleLabel.getStyleClass().add("roster-board-header");

            int count = members != null ? members.size() : 0;
            countLabel = new Label("(" + count + ")");
            countLabel.getStyleClass().add("roster-board-count");

            headerRow = new HBox(6, chevronBtn, titleLabel, countLabel);
            headerRow.setAlignment(Pos.CENTER_LEFT);
            headerRow.getStyleClass().add("roster-board-header-row");
            HBox.setHgrow(titleLabel, Priority.ALWAYS);

            membersContainer = new VBox(4);
            membersContainer.getStyleClass().add("roster-board-members");

            if (members != null) {
                members.sort(Comparator.comparing(Participant::getName, String.CASE_INSENSITIVE_ORDER));
                for (Participant participant : members) {
                    ParticipantRow row = new ParticipantRow(participant);
                    rowsByClientId.put(participant.getClientId(), row);
                    membersContainer.getChildren().add(row.root);
                }
            }

            root = new VBox(4, headerRow, membersContainer);
            root.getStyleClass().add("roster-board-section");
            applyExpandedVisuals();
            updateChevronAndTooltips();

            chevronBtn.setMouseTransparent(true);
            headerRow.setOnMouseClicked(e -> toggleExpanded());
        }

        private void toggleExpanded() {
            setExpanded(!expanded, true);
        }

        private void setExpanded(boolean expand, boolean animate) {
            if (expand == expanded && fadeAnim == null) {
                return;
            }
            boardSectionExpanded.put(boardId, expand);
            if (fadeAnim != null) {
                fadeAnim.stop();
                fadeAnim = null;
            }
            if (!animate) {
                expanded = expand;
                applyExpandedVisuals();
                return;
            }

            if (expand) {
                expanded = true;
                membersContainer.setManaged(true);
                membersContainer.setVisible(true);
                membersContainer.setOpacity(0);
                updateChevronAndTooltips();
                syncCollapsedStyleClass();
                fadeAnim = new FadeTransition(BOARD_SECTION_ANIM_MS, membersContainer);
                fadeAnim.setFromValue(0);
                fadeAnim.setToValue(1);
                fadeAnim.setOnFinished(e -> {
                    fadeAnim = null;
                    applyExpandedVisuals();
                });
                fadeAnim.play();
            } else {
                expanded = false;
                updateChevronAndTooltips();
                syncCollapsedStyleClass();
                fadeAnim = new FadeTransition(BOARD_SECTION_ANIM_MS, membersContainer);
                fadeAnim.setFromValue(membersContainer.getOpacity());
                fadeAnim.setToValue(0);
                fadeAnim.setOnFinished(e -> {
                    fadeAnim = null;
                    applyExpandedVisuals();
                });
                fadeAnim.play();
            }
        }

        private void applyExpandedVisuals() {
            membersContainer.setMaxHeight(Region.USE_COMPUTED_SIZE);
            membersContainer.setMinHeight(Region.USE_COMPUTED_SIZE);
            if (expanded) {
                membersContainer.setManaged(true);
                membersContainer.setVisible(true);
                membersContainer.setOpacity(1);
            } else {
                membersContainer.setOpacity(0);
                membersContainer.setVisible(false);
                membersContainer.setManaged(false);
            }
            syncCollapsedStyleClass();
            updateChevronAndTooltips();
        }

        private void syncCollapsedStyleClass() {
            if (expanded) {
                root.getStyleClass().remove("roster-board-section-collapsed");
            } else if (!root.getStyleClass().contains("roster-board-section-collapsed")) {
                root.getStyleClass().add("roster-board-section-collapsed");
            }
        }

        private void updateChevronAndTooltips() {
            chevronBtn.setText(expanded ? "\u25BE" : "\u25B8");
            String boardLabel = "Unknown".equals(boardId) ? "participants without a board" : "board \"" + boardId + "\"";
            chevronBtn.setTooltip(new Tooltip(expanded
                    ? "Collapse " + boardLabel
                    : "Expand " + boardLabel));
            if (headerRow != null) {
                Tooltip.install(headerRow, new Tooltip(expanded
                        ? "Click to hide participants on " + boardLabel
                        : "Click to show participants on " + boardLabel));
            }
            if (countLabel != null) {
                countLabel.setTooltip(new Tooltip(countLabel.getText() + " participant(s)"));
            }
        }
    }

    private final class ParticipantRow {
        private final Participant participant;
        private final HBox root;
        private final Label crownLabel;
        private final Node hwMuteIcon;
        private final Tooltip hwMuteTooltip;
        private final Label nameLabel;
        private final Button modButton;
        private final javafx.scene.layout.StackPane avatar;

        private final ChangeListener<String> nameListener;
        private final ChangeListener<Boolean> mutedListener;
        private final ChangeListener<Boolean> speakingListener;
        private final ChangeListener<String> boardListener;
        private final ChangeListener<Number> permsListener;
        private final ContextMenu contextMenu;
        private MenuItem kickMenuItem;
        private MenuItem muteMenuItem;

        ParticipantRow(Participant participant) {
            this.participant = participant;

            crownLabel = createCrownLabel();
            hwMuteIcon = createSlashedMicIcon();
            hwMuteIcon.getStyleClass().add("roster-hw-mute-icon");
            hwMuteTooltip = new Tooltip("Microphone Muted");
            Tooltip.install(hwMuteIcon, hwMuteTooltip);

            avatar = new StackPane();
            avatar.setId(avatarNodeId(participant.getClientId()));
            avatar.getStyleClass().add("participant-avatar");
            avatar.setMinSize(28, 28);
            avatar.setPrefSize(28, 28);
            avatar.setMaxSize(28, 28);
            Label initials = new Label(initialsFor(participant.getName()));
            initials.getStyleClass().add("participant-avatar-initials");
            avatar.getChildren().add(initials);

            nameLabel = new Label(participant.getName());
            nameLabel.getStyleClass().add("roster-user-name");
            nameLabel.setMaxWidth(PANEL_WIDTH - 112);
            nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
            HBox.setHgrow(nameLabel, Priority.ALWAYS);

            contextMenu = buildContextMenu();
            modButton = new Button("\u22EE");
            modButton.getStyleClass().addAll("ghost-button", "roster-moderation-btn");
            modButton.setFocusTraversable(false);
            modButton.setMnemonicParsing(false);
            modButton.setTooltip(new Tooltip("Moderation actions"));
            modButton.setOnAction(e -> contextMenu.show(modButton, Side.BOTTOM, 0, 0));

            root = new HBox(8, crownLabel, avatar, nameLabel, hwMuteIcon, modButton);
            root.setAlignment(Pos.CENTER_LEFT);
            root.getStyleClass().add("roster-user-row");

            nameListener = (obs, was, now) -> {
                nameLabel.setText(now != null ? now : "");
                initials.setText(initialsFor(now));
            };
            mutedListener = (obs, was, muted) -> applyVisualMuteState();
            speakingListener = (obs, was, speaking) -> applySpeakingRing(Boolean.TRUE.equals(speaking));
            boardListener = (obs, was, now) -> rebuildSections();
            permsListener = (obs, was, now) -> {
                applyPermissionIndicators();
                applyVisualMuteState();
                refreshContextMenu();
            };

            participant.nameProperty().addListener(nameListener);
            participant.isMutedProperty().addListener(mutedListener);
            participant.isSpeakingProperty().addListener(speakingListener);
            participant.currentBoardIdProperty().addListener(boardListener);
            participant.permissionsProperty().addListener(permsListener);

            syncFromParticipant();
        }

        private ContextMenu buildContextMenu() {
            ContextMenu menu = new ContextMenu();
            kickMenuItem = new MenuItem("Kick from Room");
            kickMenuItem.setOnAction(e -> {
                if (kickHandler != null) {
                    kickHandler.accept(participant.getClientId(), participant.getName());
                }
            });
            muteMenuItem = new MenuItem();
            refreshContextMenu();
            applyModerationToMenuItems();
            menu.getItems().addAll(kickMenuItem, muteMenuItem);
            return menu;
        }

        private void refreshContextMenu() {
            if (RoomPermissions.canSpeak(participant.getPermissions())) {
                muteMenuItem.setText("Revoke Mic Access");
                muteMenuItem.setOnAction(e -> {
                    if (revokeSpeakHandler != null) {
                        revokeSpeakHandler.accept(participant.getClientId(), participant.getName());
                    }
                });
            } else {
                muteMenuItem.setText("Grant Mic Access");
                muteMenuItem.setOnAction(e -> {
                    if (grantSpeakHandler != null) {
                        grantSpeakHandler.accept(participant);
                    }
                });
            }
        }

        private void applyModerationToMenuItems() {
            boolean show = CollaborationRoster.this.moderationEnabled
                    && !participant.getClientId().equals(localClientId);
            kickMenuItem.setDisable(!show);
            muteMenuItem.setDisable(!show);
        }

        void refreshModerationUi() {
            boolean show = CollaborationRoster.this.moderationEnabled
                    && !participant.getClientId().equals(localClientId);
            modButton.setVisible(show);
            modButton.setManaged(show);
            applyModerationToMenuItems();
            refreshContextMenu();
        }

        void syncFromParticipant() {
            nameLabel.setText(participant.getName());
            applySpeakingRing(participant.isSpeaking());
            applyPermissionIndicators();
            applyVisualMuteState();
            refreshContextMenu();
            refreshModerationUi();
        }

        void dispose() {
            participant.nameProperty().removeListener(nameListener);
            participant.isMutedProperty().removeListener(mutedListener);
            participant.isSpeakingProperty().removeListener(speakingListener);
            participant.currentBoardIdProperty().removeListener(boardListener);
            participant.permissionsProperty().removeListener(permsListener);
        }

        private void applyVisualMuteState() {
            boolean canSpeak = RoomPermissions.canSpeak(participant.getPermissions());
            boolean showIcon = participant.isMuted() || !canSpeak;
            hwMuteIcon.setVisible(showIcon);
            hwMuteIcon.setManaged(showIcon);
            if (!canSpeak) {
                hwMuteTooltip.setText("Mic Access Revoked by Admin");
            } else if (participant.isMuted()) {
                hwMuteTooltip.setText("Microphone Muted");
            } else {
                hwMuteTooltip.setText("Microphone Muted");
            }
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

        private void applyPermissionIndicators() {
            int perms = participant.getPermissions();
            // Crown visibility is driven by permissionsProperty(), not display name or mute state.
            boolean manage = RoomPermissions.canManageRoom(perms);
            crownLabel.setVisible(manage);
            crownLabel.setManaged(manage);
        }
    }
}
