package com.distrisync.client;

import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.RoomPermissions;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;

import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Headless YouTube player hosted in a {@link WebView}, synchronized to the server's
 * authoritative {@link MessageCodec.MediaStatePayload} clock, with a permission-aware
 * JavaFX control bar that egresses {@code MEDIA_CONTROL} instead of iframe UI.
 */
public final class YoutubePlayerNode extends VBox {

    private static final double PLAYER_WIDTH = 640;
    private static final double VIDEO_HEIGHT = 360;
    private static final double HEADER_BAR_HEIGHT = 36;
    private static final double CONTROLS_BAR_HEIGHT = 52;
    private static final double PLAYER_HEIGHT = HEADER_BAR_HEIGHT + VIDEO_HEIGHT + CONTROLS_BAR_HEIGHT;

    private static final Pattern RAW_VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern EMBEDDED_VIDEO_ID = Pattern.compile(
            "(?:youtube\\.com/(?:watch\\?.*v=|embed/|v/|shorts/)|youtu\\.be/)([A-Za-z0-9_-]{11})");

    private static final double DRIFT_THRESHOLD_SECONDS = 1.5;
    private static final double DRIFT_SYNCED_SECONDS = 0.5;

    private static final Color SYNC_COLOR_OK = Color.web("#10B981");
    private static final Color SYNC_COLOR_WARM = Color.web("#FBBF24");
    private static final Color SYNC_COLOR_CORRECTING = Color.web("#F59E0B");

    private static final String PLAYER_HTML = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8"/>
                <style> body, html { width: 100%; height: 100%; margin: 0; padding: 0; background-color: #0E1015; overflow: hidden; } </style>
            </head>
            <body>
                <div id="player"></div>
                <script src="https://www.youtube.com/iframe_api"></script>
                <script>
                    window.onerror = function(msg, url, line) { if(window.javaBridge) window.javaBridge.error(msg); };
                    var player;
                    window.onYouTubeIframeAPIReady = function() { if(window.javaBridge) window.javaBridge.log("API Script Loaded."); };
                    window.initPlayer = function(vidId) {
                        if (typeof YT === 'undefined' || typeof YT.Player === 'undefined') {
                            window.setTimeout(function() { window.initPlayer(vidId); }, 100);
                            return;
                        }
                        if(window.javaBridge) window.javaBridge.log("Constructing player for: " + vidId);
                        player = new YT.Player('player', {
                            height: '100%', width: '100%',
                            videoId: vidId,
                            playerVars: { controls: 0, disablekb: 1, rel: 0, fs: 0 },
                            events: {
                                'onReady': function() { if(window.javaBridge) window.javaBridge.onPlayerReady(); },
                                'onError': function(e) { if(window.javaBridge) window.javaBridge.error("YT Error: " + e.data); }
                            }
                        });
                    };
                    window.setVolume = function(vol) {
                        if (player && typeof player.setVolume === 'function') {
                            player.setVolume(vol);
                        }
                    };
                    window.getVolume = function() {
                        if (player && typeof player.getVolume === 'function') {
                            return player.getVolume();
                        }
                        return 100;
                    };
                </script>
            </body>
            </html>
            """;

    private static int localServerPort = -1;

    private static void ensureLocalServer(String htmlContent) {
        if (localServerPort != -1) {
            return;
        }
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                byte[] response = htmlContent.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
            });
            server.setExecutor(null);
            server.start();
            localServerPort = server.getAddress().getPort();
            System.out.println("[YT-Server] Local HTTP proxy running on port " + localServerPort);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private final NetworkClient networkClient;
    private final ParticipantManager participantManager;

    private final WebView webView;
    private final WebEngine webEngine;
    private final JavaBridge javaBridge;
    private final AtomicBoolean playerReady = new AtomicBoolean(false);

    private final HBox headerBar;
    private final HBox adminHeaderRow;
    private final TextField urlInput;
    private final Button loadBtn;
    private final HBox windowControls;
    private final Button minimizeBtn;
    private final Button closeBtn;
    private final Label dragGrip;

    private final Button playPauseBtn;
    private final Slider timelineSlider;
    private final Slider volumeSlider;
    private final Label timeLabel;
    private final Circle syncDot;

    private final AnimationTimer controlsTimer;
    private boolean controlsTimerRunning;

    private double dragStartX;
    private double dragStartY;

    private boolean playerInitialized = false;
    private String loadedVideoId = "";
    private String currentMediaState = "";
    private MessageCodec.MediaStatePayload pendingState;
    private MessageCodec.MediaStatePayload pendingServerState;
    private MessageCodec.MediaStatePayload lastAppliedState;
    private boolean sliderDragging;

    public YoutubePlayerNode(NetworkClient networkClient, ParticipantManager participantManager) {
        if (networkClient == null) {
            throw new IllegalArgumentException("networkClient must not be null");
        }
        if (participantManager == null) {
            throw new IllegalArgumentException("participantManager must not be null");
        }
        this.networkClient = networkClient;
        this.participantManager = participantManager;

        getStyleClass().add("youtube-player-node");
        setPrefSize(PLAYER_WIDTH, PLAYER_HEIGHT);
        setMaxSize(PLAYER_WIDTH, PLAYER_HEIGHT);
        setMinSize(PLAYER_WIDTH, PLAYER_HEIGHT);

        webView = new WebView();
        webView.getStyleClass().add("youtube-player-webview");
        webView.setPrefHeight(VIDEO_HEIGHT);
        webView.setMinHeight(VIDEO_HEIGHT);
        webView.setMaxHeight(VIDEO_HEIGHT);
        VBox.setVgrow(webView, Priority.NEVER);
        webEngine = webView.getEngine();
        javaBridge = new JavaBridge();

        urlInput = new TextField();
        urlInput.getStyleClass().add("youtube-url-input");
        urlInput.setPromptText("YouTube URL or video ID");
        urlInput.setFocusTraversable(true);
        HBox.setHgrow(urlInput, Priority.ALWAYS);
        urlInput.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                onLoadClicked();
            }
        });

        loadBtn = new Button("Load");
        loadBtn.getStyleClass().add("youtube-load-btn");
        loadBtn.setFocusTraversable(false);
        loadBtn.setOnAction(e -> onLoadClicked());

        adminHeaderRow = new HBox(8, urlInput, loadBtn);
        adminHeaderRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(adminHeaderRow, Priority.ALWAYS);

        dragGrip = new Label("\u22EE\u22EE");
        dragGrip.getStyleClass().add("youtube-drag-grip");
        dragGrip.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(dragGrip, Priority.ALWAYS);
        dragGrip.setAlignment(Pos.CENTER);

        minimizeBtn = new Button("_");
        minimizeBtn.getStyleClass().addAll("youtube-window-btn", "youtube-window-minimize-btn");
        minimizeBtn.setPrefSize(28, 28);
        minimizeBtn.setMinSize(28, 28);
        minimizeBtn.setMaxSize(28, 28);
        minimizeBtn.setFocusTraversable(false);
        minimizeBtn.setOnMousePressed(e -> e.consume());
        minimizeBtn.setOnAction(e -> {
            pauseLocalPlayback();
            setVisible(false);
        });
        Tooltip.install(minimizeBtn, new Tooltip("Hide player (session stays active)"));

        closeBtn = new Button("\u00D7");
        closeBtn.getStyleClass().addAll("youtube-window-btn", "youtube-window-close-btn");
        closeBtn.setPrefSize(28, 28);
        closeBtn.setMinSize(28, 28);
        closeBtn.setMaxSize(28, 28);
        closeBtn.setFocusTraversable(false);
        closeBtn.setOnMousePressed(e -> e.consume());
        closeBtn.setOnAction(e -> networkClient.sendMediaControl("STOP", 0, ""));
        Tooltip.install(closeBtn, new Tooltip("End watch party for everyone"));

        windowControls = new HBox(4, minimizeBtn, closeBtn);
        windowControls.getStyleClass().add("youtube-window-controls");
        windowControls.setAlignment(Pos.CENTER_RIGHT);
        HBox.setHgrow(windowControls, Priority.NEVER);

        headerBar = new HBox();
        headerBar.getStyleClass().add("youtube-header-bar");
        headerBar.setMinHeight(HEADER_BAR_HEIGHT);
        headerBar.setPrefHeight(HEADER_BAR_HEIGHT);
        headerBar.setMaxHeight(HEADER_BAR_HEIGHT);
        headerBar.setAlignment(Pos.CENTER_LEFT);
        headerBar.setCursor(Cursor.MOVE);
        headerBar.getChildren().addAll(adminHeaderRow, dragGrip, windowControls);
        headerBar.setOnMousePressed(e -> {
            toFront();
            dragStartX = e.getSceneX() - getTranslateX();
            dragStartY = e.getSceneY() - getTranslateY();
        });
        headerBar.setOnMouseDragged(e -> {
            setTranslateX(e.getSceneX() - dragStartX);
            setTranslateY(e.getSceneY() - dragStartY);
        });

        playPauseBtn = new Button("Play");
        playPauseBtn.getStyleClass().add("youtube-play-btn");
        playPauseBtn.setFocusTraversable(false);
        playPauseBtn.setOnAction(e -> onPlayPauseClicked());

        timelineSlider = new Slider(0, 1, 0);
        timelineSlider.getStyleClass().add("youtube-timeline-slider");
        timelineSlider.setPrefWidth(320);
        HBox.setHgrow(timelineSlider, Priority.ALWAYS);
        timelineSlider.setOnMousePressed(e -> sliderDragging = true);
        timelineSlider.setOnMouseReleased(e -> onTimelineReleased());

        timeLabel = new Label("00:00 / 00:00");
        timeLabel.getStyleClass().add("youtube-time-label");

        syncDot = new Circle(5);
        syncDot.setFill(SYNC_COLOR_OK);

        HBox timeRow = new HBox(8, timeLabel, syncDot);
        timeRow.setAlignment(Pos.CENTER_LEFT);

        volumeSlider = new Slider(0, 100, 100);
        volumeSlider.setPrefWidth(100);
        volumeSlider.setMinWidth(80);
        volumeSlider.setFocusTraversable(false);

        Label volIcon = new Label("\uD83D\uDD0A");
        volIcon.setStyle("-fx-text-fill: white;");

        HBox volumeContainer = new HBox(8, volIcon, volumeSlider);
        volumeContainer.setAlignment(Pos.CENTER);

        volumeSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (playerReady.get()) {
                webEngine.executeScript("window.setVolume(" + newVal.intValue() + ");");
            }
        });

        HBox controlsBar = new HBox(16, playPauseBtn, timelineSlider, timeRow, volumeContainer);
        controlsBar.getStyleClass().add("youtube-controls-bar");
        controlsBar.setStyle(
                "-fx-background-color: rgba(24, 24, 27, 0.95);"
                        + "-fx-padding: 12px;"
                        + "-fx-spacing: 16px;"
                        + "-fx-alignment: CENTER_LEFT;");
        controlsBar.setMinHeight(CONTROLS_BAR_HEIGHT);
        controlsBar.setPrefHeight(CONTROLS_BAR_HEIGHT);
        controlsBar.setMaxHeight(CONTROLS_BAR_HEIGHT);

        webEngine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            if (state == Worker.State.SUCCEEDED) {
                JSObject window = (JSObject) webEngine.executeScript("window");
                window.setMember("javaBridge", javaBridge);
                if (pendingServerState != null) {
                    pendingState = pendingServerState;
                    applyServerStateNow(pendingServerState);
                    pendingServerState = null;
                }
            }
        });

        ensureLocalServer(PLAYER_HTML);
        webEngine.load("http://127.0.0.1:" + localServerPort + "/");
        getChildren().addAll(headerBar, webView, controlsBar);

        setEffect(new DropShadow(BlurType.GAUSSIAN, Color.rgb(0, 0, 0, 0.6), 30, 0, 0, 15));

        participantManager.localPermissionsProperty().addListener((obs, old, perms) ->
                applyMediaPermissions());

        controlsTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                MessageCodec.MediaStatePayload state = lastAppliedState;
                if (state == null || !playerReady.get()) {
                    return;
                }
                refreshControlsUi(state);
            }
        };

        applyMediaPermissions();
    }

    /**
     * Pauses iframe playback locally without changing room media state (e.g. when hiding the PiP).
     */
    public void pauseLocalPlayback() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::pauseLocalPlayback);
            return;
        }
        if (isPlayerScriptAvailable()) {
            webEngine.executeScript("player.pauseVideo();");
        }
    }

    /**
     * Stops UI refresh timers, halts YouTube playback, and tears down the {@link WebEngine}
     * document (via {@code about:blank}) before removing this node from the scene graph.
     */
    public void dispose() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::dispose);
            return;
        }
        controlsTimer.stop();
        controlsTimerRunning = false;
        try {
            webEngine.executeScript(
                    "if(typeof player !== 'undefined' && player.stopVideo) player.stopVideo();");
        } catch (Exception ignored) {
            // Page may be unloading or bridge unavailable during teardown
        }
        webEngine.load("about:blank");
        playerReady.set(false);
        playerInitialized = false;
        pendingState = null;
        pendingServerState = null;
        lastAppliedState = null;
        loadedVideoId = "";
        currentMediaState = "";
    }

    /**
     * Applies authoritative room media state. Must run on the JavaFX application thread.
     */
    public void applyServerState(MessageCodec.MediaStatePayload state) {
        if (state == null) {
            return;
        }
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> applyServerState(state));
            return;
        }
        if (webEngine.getLoadWorker().getState() != Worker.State.SUCCEEDED) {
            this.pendingServerState = state;
            return;
        }
        pendingState = state;
        String videoId = state.videoId() != null ? state.videoId() : "";
        if (!videoId.isBlank() && !playerInitialized) {
            applyServerStateNow(state);
            return;
        }
        if (!playerReady.get() || !isPlayerScriptAvailable()) {
            return;
        }
        applyServerStateNow(state);
    }

    private void applyServerStateNow(MessageCodec.MediaStatePayload state) {
        String videoId = state.videoId() != null ? state.videoId() : "";
        if (!videoId.isBlank() && !playerInitialized) {
            playerInitialized = true;
            webEngine.executeScript("window.initPlayer('" + escapeJsString(videoId) + "');");
            loadedVideoId = videoId;
            return;
        }
        if (!videoId.isBlank() && !videoId.equals(loadedVideoId)) {
            webEngine.executeScript("player.cueVideoById('" + escapeJsString(videoId) + "')");
            loadedVideoId = videoId;
        }

        lastAppliedState = state;
        currentMediaState = state.state() != null ? state.state().strip().toUpperCase() : "";

        String mediaState = currentMediaState;
        if ("PLAYING".equals(mediaState)) {
            applyPlayingState(state);
            if (!controlsTimerRunning) {
                controlsTimer.start();
                controlsTimerRunning = true;
            }
        } else if ("PAUSED".equals(mediaState)) {
            applyPausedState(state);
            if (controlsTimerRunning) {
                controlsTimer.stop();
                controlsTimerRunning = false;
            }
        }

        refreshDuration();
        refreshControlsUi(state);
        updatePlayPauseButtonLabel();
    }

    private void applyPlayingState(MessageCodec.MediaStatePayload state) {
        if (loadedVideoId.isBlank()) {
            return;
        }
        double drift = computeDriftSeconds(state);
        if (drift > DRIFT_THRESHOLD_SECONDS) {
            double expectedTime = expectedPlaybackSeconds(state, System.currentTimeMillis());
            webEngine.executeScript("player.seekTo(" + expectedTime + ", true)");
        }
        webEngine.executeScript("player.playVideo()");
    }

    private void applyPausedState(MessageCodec.MediaStatePayload state) {
        if (loadedVideoId.isBlank()) {
            return;
        }
        double drift = computeDriftSeconds(state);
        if (drift > DRIFT_THRESHOLD_SECONDS) {
            double expectedTime = expectedPlaybackSeconds(state, System.currentTimeMillis());
            webEngine.executeScript("player.seekTo(" + expectedTime + ", true)");
        }
        webEngine.executeScript("player.pauseVideo()");
    }

    private void refreshControlsUi(MessageCodec.MediaStatePayload state) {
        if (state == null) {
            return;
        }
        double duration = readDuration();
        if (duration > 0) {
            timelineSlider.setMax(duration);
        }
        double position = expectedPlaybackSeconds(state, System.currentTimeMillis());
        if (!sliderDragging) {
            timelineSlider.setValue(Math.min(Math.max(0, position), timelineSlider.getMax()));
        }
        timeLabel.setText(formatMediaTime(position) + " / " + formatMediaTime(duration > 0 ? duration : 0));
        updateSyncIndicator(computeDriftSeconds(state));
    }

    private void updateSyncIndicator(double driftSeconds) {
        syncDot.setFill(syncDriftColor(driftSeconds));
    }

    static Color syncDriftColor(double driftSeconds) {
        if (driftSeconds < DRIFT_SYNCED_SECONDS) {
            return SYNC_COLOR_OK;
        }
        if (driftSeconds > DRIFT_THRESHOLD_SECONDS) {
            return SYNC_COLOR_CORRECTING;
        }
        return SYNC_COLOR_WARM;
    }

    private double computeDriftSeconds(MessageCodec.MediaStatePayload state) {
        double expected = expectedPlaybackSeconds(state, System.currentTimeMillis());
        return Math.abs(readCurrentTime() - expected);
    }

    private void applyMediaPermissions() {
        boolean canManage = RoomPermissions.canManageMedia(participantManager.getLocalPermissions());
        if (canManage) {
            adminHeaderRow.setManaged(true);
            adminHeaderRow.setVisible(true);
            closeBtn.setManaged(true);
            closeBtn.setVisible(true);
            dragGrip.setManaged(false);
            dragGrip.setVisible(false);
            playPauseBtn.setDisable(false);
            timelineSlider.setDisable(false);
        } else {
            adminHeaderRow.setManaged(false);
            adminHeaderRow.setVisible(false);
            closeBtn.setManaged(false);
            closeBtn.setVisible(false);
            dragGrip.setManaged(true);
            dragGrip.setVisible(true);
            playPauseBtn.setDisable(true);
            timelineSlider.setDisable(true);
        }
        minimizeBtn.setManaged(true);
        minimizeBtn.setVisible(true);
        minimizeBtn.setDisable(false);
        volumeSlider.setManaged(true);
        volumeSlider.setVisible(true);
        volumeSlider.setDisable(false);
    }

    private void onLoadClicked() {
        if (!RoomPermissions.canManageMedia(participantManager.getLocalPermissions())) {
            return;
        }
        String id = parseYouTubeVideoId(urlInput.getText());
        if (id == null) {
            return;
        }
        networkClient.sendMediaControl("LOAD", 0, id);
    }

    /**
     * Extracts an 11-character YouTube video id from a URL or bare id string.
     *
     * @return video id, or {@code null} if not parseable
     */
    static String parseYouTubeVideoId(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        Matcher raw = RAW_VIDEO_ID.matcher(trimmed);
        if (raw.matches()) {
            return trimmed;
        }
        Matcher embedded = EMBEDDED_VIDEO_ID.matcher(trimmed);
        if (embedded.find()) {
            return embedded.group(1);
        }
        return null;
    }

    private void onPlayPauseClicked() {
        if (!RoomPermissions.canManageMedia(participantManager.getLocalPermissions())) {
            return;
        }
        double t = readCurrentTime();
        if ("PLAYING".equals(currentMediaState)) {
            networkClient.sendMediaControl("PAUSE", t, null);
        } else {
            networkClient.sendMediaControl("PLAY", t, null);
        }
    }

    private void onTimelineReleased() {
        sliderDragging = false;
        if (!RoomPermissions.canManageMedia(participantManager.getLocalPermissions())) {
            return;
        }
        networkClient.sendMediaControl("SEEK", timelineSlider.getValue(), null);
    }

    private void updatePlayPauseButtonLabel() {
        playPauseBtn.setText("PLAYING".equals(currentMediaState) ? "Pause" : "Play");
    }

    private void refreshDuration() {
        double duration = readDuration();
        if (duration > 0) {
            timelineSlider.setMax(duration);
        }
    }

    /**
     * Playback position at client wall time {@code nowMs} per server contract.
     */
    static double expectedPlaybackSeconds(MessageCodec.MediaStatePayload state, long nowMs) {
        if (state == null) {
            return 0.0;
        }
        String mediaState = state.state() != null ? state.state().strip().toUpperCase() : "";
        if ("PLAYING".equals(mediaState)) {
            return state.mediaTimeSeconds()
                    + (nowMs - state.serverEpochMs()) / 1000.0;
        }
        return state.mediaTimeSeconds();
    }

    static String formatMediaTime(double totalSeconds) {
        if (totalSeconds < 0 || !Double.isFinite(totalSeconds)) {
            totalSeconds = 0;
        }
        int seconds = (int) Math.floor(totalSeconds);
        int minutes = seconds / 60;
        int secs = seconds % 60;
        return String.format("%02d:%02d", minutes, secs);
    }

    private double readCurrentTime() {
        if (!isPlayerScriptAvailable()) {
            return 0.0;
        }
        Object raw = webEngine.executeScript("player.getCurrentTime()");
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        return 0.0;
    }

    private double readDuration() {
        if (!isPlayerScriptAvailable()) {
            return 0.0;
        }
        Object raw = webEngine.executeScript("player.getDuration()");
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            return d > 0 && Double.isFinite(d) ? d : 0.0;
        }
        return 0.0;
    }

    private boolean isPlayerScriptAvailable() {
        Object raw = webEngine.executeScript(
                "typeof player !== 'undefined' && player && typeof player.getCurrentTime === 'function'");
        return Boolean.TRUE.equals(raw);
    }

    private static String escapeJsString(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    /**
     * JavaScript → Java bridge injected on {@code window.javaBridge} after HTML load succeeds.
     */
    public final class JavaBridge {

        @SuppressWarnings("unused")
        public void log(String message) {
            System.out.println("[YT-JS-LOG] " + message);
        }

        @SuppressWarnings("unused")
        public void error(String message) {
            System.err.println("[YT-JS-ERROR] " + message);
        }

        @SuppressWarnings("unused")
        public void onPlayerReady() {
            if (!Platform.isFxApplicationThread()) {
                Platform.runLater(() -> onPlayerReady());
                return;
            }
            playerReady.set(true);
            refreshDuration();
            MessageCodec.MediaStatePayload pending = pendingState;
            if (pending != null) {
                applyServerStateNow(pending);
            }
        }
    }
}
