package com.distrisync.client;

import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.RoomPermissions;
import com.distrisync.model.ArrowNode;
import com.distrisync.model.Circle;
import com.distrisync.model.EllipseNode;
import com.distrisync.model.EraserPath;
import com.distrisync.model.Line;
import com.distrisync.model.RectangleNode;
import com.distrisync.model.Shape;
import com.distrisync.model.TextNode;
import javafx.animation.Animation;
import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.util.Duration;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.ImageCursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ScrollPane.ScrollBarPolicy;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.TextInputControl;
import javafx.util.Duration;
import javafx.scene.Parent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderStrokeStyle;
import javafx.scene.layout.BorderWidths;
import javafx.scene.layout.CornerRadii;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main JavaFX entry point for the DistriSync collaborative whiteboard client.
 *
 * <h2>Canvas architecture — three-layer StackPane</h2>
 * <ul>
 *   <li><b>Layer 1 — {@code baseCanvas}</b>: redrawn on snapshot/mutation events and
 *       viewport resize; draws the white background and all committed shapes sorted
 *       by Lamport timestamp.</li>
 *   <li><b>Layer 2 — {@code transientCanvas}</b>: transparent by default;
 *       updated directly from mouse-drag events to show the rubber-band preview
 *       (Line / Circle) or the in-progress freehand / eraser stroke.  Cleared
 *       completely on {@code MOUSE_RELEASED} before the shape is committed.</li>
 *   <li><b>Layer 3 — {@code cursorPane}</b>: a transparent {@link Pane} that
 *       hosts JavaFX {@code Group} nodes for each remote cursor.
 *       {@link UdpPointerTracker} manages these nodes exclusively via
 *       {@link Platform#runLater}.</li>
 * </ul>
 *
 * <h2>Thread model</h2>
 * All {@link NetworkClient} callbacks arrive on background threads and are
 * marshalled back to the FX Application Thread via {@link Platform#runLater}
 * before touching any shared state.  The {@link UdpPointerTracker} owns its
 * own send/receive threads; UI mutations happen exclusively inside
 * {@code Platform.runLater}.
 */
public class WhiteboardApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(WhiteboardApp.class);

    // ── server defaults ───────────────────────────────────────────────────────
    /** Prefer IPv4 loopback on Windows to avoid ::1 vs 127.0.0.1 split with some servers. */
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int    DEFAULT_PORT = 9090;

    // ── Catppuccin Mocha / dark palette ───────────────────────────────────────
    private static final String BG_BASE      = "#1e1e2e";
    private static final String BG_OVERLAY   = "#45475a";
    private static final String FG_TEXT      = "#cdd6f4";
    private static final String FG_MUTED     = "#7f849c";
    private static final String ACCENT       = "#89b4fa";
    private static final String GREEN        = "#a6e3a1";
    private static final String RED          = "#f38ba8";
    private static final String TOOLBAR_BG   = "#2C2F33";

    // ── toolbar geometry ──────────────────────────────────────────────────────
    // ── drawing constants ─────────────────────────────────────────────────────
    private static final double MIN_DRAG_DIST     = 2.0;
    private static final double MIN_FREEHAND_STEP = 4.0;
    /** Min interval between eraser-driven full base-canvas repaints (~30 FPS). */
    private static final long ERASER_REDRAW_INTERVAL_NS = 33_000_000L;
    private static final double ERASER_BASE_WIDTH = 14.0;
    private static final double MIN_STAGE_WIDTH   = 800;
    private static final double MIN_STAGE_HEIGHT  = 600;

    /** {@link javafx.scene.Node#setId(String)} on the canvas clear control for layout / visibility tests. */
    static final String TOOLBAR_CLEAR_BUTTON_ID = "whiteboard-toolbar-clear";
    static final String WORKSPACE_ROOT_ID = "whiteboard-workspace-root";
    static final String WORKSPACE_CANVAS_LAYER_ID = "whiteboard-workspace-canvas-layer";
    static final String WORKSPACE_TOOLBAR_LAYER_ID = "whiteboard-workspace-toolbar-layer";
    static final String WORKSPACE_DOCK_LAYER_ID = "whiteboard-workspace-dock-layer";
    /** StackPane layer wrapping the color/stroke properties bar (margin / TestFX hooks). */
    static final String WORKSPACE_PROPERTIES_LAYER_ID = "whiteboard-workspace-properties-layer";
    static final String WORKSPACE_MIC_LAYER_ID = "whiteboard-workspace-mic-layer";
    static final String WORKSPACE_PARTICIPANT_LAYER_ID = "whiteboard-workspace-participant-layer";
    static final String MIC_TOGGLE_BUTTON_ID = "whiteboard-mic-toggle";
    static final String WORKSPACE_PROPERTIES_BAR_ID = "whiteboard-workspace-properties-bar";
    static final String WORKSPACE_ERASER_TYPE_BAR_ID = "whiteboard-workspace-eraser-type-bar";
    static final String ERASER_TYPE_CIRCLE_BUTTON_ID = "whiteboard-eraser-type-circle";
    static final String ERASER_TYPE_SQUARE_BUTTON_ID = "whiteboard-eraser-type-square";
    static final String BOARDS_BUTTON_ID = "whiteboard-boards-button";
    static final String TOOL_DOCK_SELECT_BUTTON_ID = "whiteboard-tool-select";
    static final String TOOL_DOCK_PEN_BUTTON_ID = "whiteboard-tool-pen";
    static final String TOOL_DOCK_ERASER_BUTTON_ID = "whiteboard-tool-eraser";
    static final String TOOL_DOCK_RECTANGLE_BUTTON_ID = "whiteboard-tool-rectangle";
    static final String TOOL_DOCK_ELLIPSE_BUTTON_ID = "whiteboard-tool-ellipse";
    static final String TOOL_DOCK_ARROW_BUTTON_ID = "whiteboard-tool-arrow";
    /** Leave Room control on the canvas HUD (contrast / TestFX hooks). */
    static final String LEAVE_ROOM_BUTTON_ID = "whiteboard-leave-room-button";
    static final String DELETE_ROOM_BUTTON_ID = "whiteboard-delete-room-button";
    /** Chevron control that collapses / expands the tool dock beside the boards strip. */
    static final String TOOL_DOCK_TOGGLE_BUTTON_ID = "whiteboard-tool-dock-toggle";
    /** Login card primary action (flat button reset / TestFX). */
    static final String LOGIN_JOIN_NETWORK_BUTTON_ID = "login-join-network";
    static final String LOBBY_MODAL_CARD_ID = "lobby-modal-card";
    static final String LOGIN_MODAL_CARD_ID = "login-modal-card";

    // ── active drawing tool ───────────────────────────────────────────────────
    private enum Tool { LINE, CIRCLE, RECTANGLE, ELLIPSE, ARROW, FREEHAND, ERASER, TEXT }
    private volatile Tool activeTool = Tool.LINE;

    // ── canvas layers ─────────────────────────────────────────────────────────
    private Canvas          baseCanvas;             // Layer 1: committed shapes
    private Canvas          remoteTransientCanvas;  // Layer 2: remote peers' in-progress shapes
    private Canvas          transientCanvas;        // Layer 3: local rubber-band / freehand preview
    private GraphicsContext baseGc;
    private GraphicsContext remoteTransientGc;
    private GraphicsContext transientGc;
    private Pane            cursorPane;             // Layer 4: remote-cursor JavaFX nodes
    private Pane            controlPane;            // Layer 5: floating text-input controls

    // ── global stroke / color (Figma-style shared context) ───────────────────
    private final GlobalCanvasContext globalCanvasContext = new GlobalCanvasContext();
    private final GlobalCanvasShapeFactory shapeFactory =
            new GlobalCanvasShapeFactory(globalCanvasContext);
    private final SpatialHashGrid shapeSpatialGrid = new SpatialHashGrid();
    private EraserSpatialIntersection eraserIntersection;
    /** Shape ids removed during the current eraser press/drag (FX thread only). */
    private final Set<UUID> erasedInGesture = new HashSet<>();
    /** Set when eraser deletes shapes; consumed by {@link #eraserRedrawTimer}. */
    private boolean needsBaseRedraw;
    private boolean eraserRedrawTimerActive;
    private long eraserRedrawLastNanos;
    /** Latest eraser pointer during drag; consumed at ~30 Hz by {@link #eraserRedrawTimer}. */
    private volatile double lastEraserX = -1;
    private volatile double lastEraserY = -1;
    private final AnimationTimer eraserRedrawTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            if (eraserRedrawLastNanos != 0
                    && now - eraserRedrawLastNanos < ERASER_REDRAW_INTERVAL_NS) {
                return;
            }
            eraserRedrawLastNanos = now;

            if (lastEraserX != -1) {
                double x = lastEraserX;
                double y = lastEraserY;
                lastEraserX = -1;
                lastEraserY = -1;
                performEraserAt(x, y);
            }

            if (!needsBaseRedraw) {
                if (lastEraserX == -1 && !(isDragging && activeTool == Tool.ERASER)) {
                    stop();
                    eraserRedrawTimerActive = false;
                    eraserRedrawLastNanos = 0;
                }
                return;
            }
            needsBaseRedraw = false;
            redrawBaseCanvas(shapes.values());
        }
    };

    // ── toolbar controls ──────────────────────────────────────────────────────
    private ColorPicker colorPicker;
    private Slider      strokeSlider;
    private HBox        eraserTypeBar;
    private ToggleButton eraserTypeCircleBtn;
    private ToggleButton eraserTypeSquareBtn;
    private Label       statusLabel;
    private final ToolsDrawerToggleModel toolsDrawerToggleModel = new ToolsDrawerToggleModel();
    private TranslateTransition toolsDrawerSlideTransition;
    private Button    toolDockToggleButton;
    /** Toggle + dock row; chevron stays outside the clipped slide so it always receives clicks. */
    private HBox      toolsChromeRow;
    private StackPane dockSlideClipStack;
    private Rectangle dockClipRect;
    private VBox      canvasToolDock;
    /** Opens / hides the spatial YouTube player (decoupled from {@code MEDIA_STATE_UPDATE}). */
    private Button    watchPartyBtn;
    /** Tools-island toggle group and buttons (for shortcuts + programmatic selection). */
    private ToggleGroup canvasToolGroup;
    private ToggleButton canvasToolLine;
    private ToggleButton canvasToolCircle;
    private ToggleButton canvasToolPen;
    private ToggleButton canvasToolEraser;
    private ToggleButton canvasToolRectangle;
    private ToggleButton canvasToolEllipse;
    private ToggleButton canvasToolArrow;
    private ToggleButton canvasToolText;
    /** Tracks SHIFT for aspect-ratio locks during rubber-band drags. */
    private volatile boolean shiftKeyHeld;

    // ── shape-ownership tooltip (shown on hover over committed shapes) ─────────
    private Tooltip ownerTooltip;

    // ── network subsystems ────────────────────────────────────────────────────
    private NetworkClient     networkClient;
    private RemoteCursorManager remoteCursorManager;
    /** Endpoint last passed to {@link NetworkClient} (for lobby error text). */
    private String            networkHost = DEFAULT_HOST;
    private int               networkPort = DEFAULT_PORT;
    /** Fires if we stay on the lobby after requesting a room join (no SNAPSHOT). */
    private PauseTransition   lobbyJoinWatchdog;

    /** Bottom-right performance overlay (canvas scene): ping RTT + UI FPS. */
    private HBox   telemetryHudRoot;
    private Label  performanceHud;
    private AnimationTimer performanceFpsTimer;
    private Timeline performanceHudTimeline;
    private int performanceFps;
    private int performanceFrameCount;
    private long performanceFpsLastSecondNanos;
    private volatile boolean telemetryHudWired;

    /** Subtle hover scale on the telemetry HUD strip. */
    private ScaleTransition   telemetryHoverScaleTransition;

    /** Bottom-left Discord-style mute / unmute control on the canvas scene. */
    private Button micToggleBtn;
    private Button btnDeleteRoom;
    private CollaborationRoster collaborationRoster;
    private VBox newBoardSwitcherCard;
    private Tooltip micToggleTooltip;
    private boolean micToggleHudWired;
    /** Tool dock row + properties bar — disabled when the user cannot draw. */
    private HBox      drawingToolbar;
    private HBox      workspacePropertiesBar;
    private List<RoomInfo> lastLobbyRooms = List.of();
    private final AtomicBoolean sessionRevokedOverlayShown = new AtomicBoolean(false);
    private StackPane sessionRevokedOverlay;

    private static final String MIC_ON_GLYPH  = "\uD83C\uDFA4";
    private static final String MIC_OFF_GLYPH = "\uD83D\uDD07";

    // ── user identity (name from dialog); room title updated after JOIN_ROOM ─
    private String authorName = "Anonymous";
    private String clientId   = UUID.randomUUID().toString();
    private String roomId     = "";

    // ── two-scene state machine: login → lobby ↔ canvas ───────────────────────
    private Stage   primaryStage;
    private Scene   loginScene;
    private Scene   lobbyScene;
    private Scene   canvasScene;
    /** Root {@link StackPane} of {@link #canvasScene} — hosts overlay and floating board switcher control. */
    private StackPane canvasSceneRoot;
    /** Canvas + floating HUD; blurred when {@link #onSessionRevoked} overlay is shown (sibling of kick overlay). */
    private StackPane workspaceContentLayer;
    /** Transparent host for draggable spatial nodes (e.g. WebView media); sits above canvas, below HUD. */
    private Pane spatialOverlayLayer;
    private YoutubePlayerNode youtubePlayer;
    private ChangeListener<Number> youtubeCenterWidthListener;
    private WeakChangeListener<Number> youtubeCenterWidthWeakListener;
    /** Previous {@code MediaStatePayload#state} for PAUSED→PLAYING echo-guard detection. */
    private String lastMediaState;
    /** Last non-blank room video id from {@code MEDIA_STATE_UPDATE}; drives watch-party toolbar visibility. */
    private String lastActiveRoomVideoId = "";
    /** Full-screen Task View–style board picker; added/removed from {@link #canvasSceneRoot} when toggled. */
    private StackPane switcherOverlay;
    private FlowPane  switcherBoardGrid;
    /** Thumbnails captured from {@link #baseCanvas} before leaving each board (FX thread only). */
    private final Map<String, Image> boardSnapshots = new HashMap<>();
    private final GaussianBlur boardSwitcherCanvasBlur = new GaussianBlur(14);
    private final GaussianBlur sessionRevokedWorkspaceBlur = new GaussianBlur(18);
    /** Layered workspace: three {@link Canvas} layers plus cursor and control {@link Pane}s; sizes follow this stack. */
    private StackPane canvasContainer;
    private CanvasViewportResizeHandler canvasViewportResizeHandler;
    /** In-flight snapshot hydration fade; stopped when a newer SNAPSHOT arrives. */
    private Animation snapshotHydrationAnimation;
    /** Bumped on each SNAPSHOT so stale fade callbacks do not paint after a newer snapshot. */
    private long snapshotHydrationToken;
    private VBox    lobbyRoomList;
    private Label   lobbyEmptyStateLabel;
    private Label     lobbyStatusLabel;
    private StackPane lobbyToastShell;
    private Animation lobbyToastSequence;
    private TextField newRoomField;

    static final String LOBBY_TOAST_SHELL_ID = "lobby-toast-shell";

    /** Debounce duplicate lobby join/create clicks (same room, short window). */
    private long   lastLobbyJoinMillis;
    private String lastLobbyJoinRoomId = "";

    /**
     * LIFO history of shape IDs committed by this local user during the current
     * session.  Used to implement single-level undo via {@code UNDO_REQUEST}.
     * Accessed exclusively on the FX Application Thread.
     */
    private final Deque<UUID> undoHistory = new ArrayDeque<>();

    // ── committed shape store (FX thread only; written via Platform.runLater) ─
    private final Map<UUID, Shape> shapes = new ConcurrentHashMap<>();

    // ── drag / freehand state (FX Application Thread only) ───────────────────
    private double         dragStartX, dragStartY;
    private double         dragCurrentX, dragCurrentY;
    private boolean        isDragging;
    private final List<double[]> freehandPoints = new ArrayList<>();
    private double         lastFreehandX, lastFreehandY;

    // ── live-drawing streaming state (FX Application Thread only) ────────────
    private UUID activeShapeId;
    private long lastSendTime = 0;

    // ── remote peers' in-progress shapes (written on FX thread via runLater) ─
    private final Map<UUID, TransientShapeEntry> transientShapes = new ConcurrentHashMap<>();

    // ── ghost text overlays for remote live-typing (FX Application Thread only) ─
    private final Map<UUID, VBox> ghostTextNodes = new ConcurrentHashMap<>();

    // ── UUID of the local TextField currently being composed (FX thread only) ─
    private UUID activeTextId;

    // =========================================================================
    // Application lifecycle
    // =========================================================================

    @Override
    public void start(Stage stage) {
        if (java.net.CookieHandler.getDefault() == null) {
            java.net.CookieHandler.setDefault(new java.net.CookieManager());
        }
        primaryStage = stage;
        Rectangle2D visual = Screen.getPrimary().getVisualBounds();
        enforceMinimumStageBounds(stage);
        stage.setTitle("DistriSync – Welcome");

        // ── Layer 1: base canvas ──────────────────────────────────────────────
        baseCanvas = new Canvas(1, 1);
        baseGc     = baseCanvas.getGraphicsContext2D();

        // ── Layer 2: remote-transient canvas (peers' in-progress shapes) ──────
        remoteTransientCanvas = new Canvas(1, 1);
        remoteTransientGc     = remoteTransientCanvas.getGraphicsContext2D();

        // ── Layer 3: local transient canvas (rubber-band / freehand preview) ──
        transientCanvas = new Canvas(1, 1);
        transientGc     = transientCanvas.getGraphicsContext2D();

        // ── Layer 4: cursor pane (transparent, mouse-transparent) ─────────────
        cursorPane = new Pane();
        cursorPane.setMouseTransparent(true);

        // ── Layer 5: control pane for floating text-input widgets ─────────────
        // Starts mouse-transparent; becomes interactive only while a TextField
        // is actively placed on it, then returns to transparent on commit/cancel.
        controlPane = new Pane();
        controlPane.setMouseTransparent(true);
        controlPane.getStyleClass().add("canvas-control-layer");

        // ── Stack all five layers ─────────────────────────────────────────────
        canvasContainer = new StackPane(
                baseCanvas, remoteTransientCanvas, transientCanvas, cursorPane, controlPane);

        // Bind canvas dimensions; floor at 1px so Prism never allocates a 0×0 RTTexture (NGCanvas NPE).
        javafx.beans.value.ObservableDoubleValue canvasWidth =
                canvasDimensionBinding(canvasContainer.widthProperty());
        javafx.beans.value.ObservableDoubleValue canvasHeight =
                canvasDimensionBinding(canvasContainer.heightProperty());
        baseCanvas.widthProperty().bind(canvasWidth);
        baseCanvas.heightProperty().bind(canvasHeight);
        remoteTransientCanvas.widthProperty().bind(canvasWidth);
        remoteTransientCanvas.heightProperty().bind(canvasHeight);
        transientCanvas.widthProperty().bind(canvasWidth);
        transientCanvas.heightProperty().bind(canvasHeight);
        cursorPane.prefWidthProperty().bind(canvasContainer.widthProperty());
        cursorPane.prefHeightProperty().bind(canvasContainer.heightProperty());
        controlPane.prefWidthProperty().bind(canvasContainer.widthProperty());
        controlPane.prefHeightProperty().bind(canvasContainer.heightProperty());

        canvasViewportResizeHandler = new CanvasViewportResizeHandler(this::redrawAllLayersAfterViewportChange);
        canvasViewportResizeHandler.attachTo(canvasContainer);

        // ── Root layout — StackPane: full-bleed canvas layer + floating HUD islands ─
        canvasSceneRoot = new StackPane();
        canvasSceneRoot.getStyleClass().add("canvas-root");
        canvasSceneRoot.setPrefSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);

        canvasSceneRoot.setId(WORKSPACE_ROOT_ID);
        workspaceContentLayer = new StackPane();
        workspaceContentLayer.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        workspaceContentLayer.prefWidthProperty().bind(canvasSceneRoot.widthProperty());
        workspaceContentLayer.prefHeightProperty().bind(canvasSceneRoot.heightProperty());

        canvasContainer.setId(WORKSPACE_CANVAS_LAYER_ID);
        canvasContainer.prefWidthProperty().bind(workspaceContentLayer.widthProperty());
        canvasContainer.prefHeightProperty().bind(workspaceContentLayer.heightProperty());

        spatialOverlayLayer = new Pane();
        spatialOverlayLayer.setPickOnBounds(false);

        buildBoardSwitcherOverlay();

        HBox boardsIsland = new HBox();
        boardsIsland.getStyleClass().addAll("hud-panel", "workspace-perimeter-node");
        boardsIsland.setAlignment(Pos.CENTER_LEFT);
        boardsIsland.setPickOnBounds(false);
        Button boardsTrigger = new Button("Boards ▾");
        boardsTrigger.setId(BOARDS_BUTTON_ID);
        boardsTrigger.setFocusTraversable(false);
        boardsTrigger.setMnemonicParsing(false);
        boardsTrigger.getStyleClass().add("hud-inline-action");
        boardsTrigger.setOnAction(e -> toggleBoardSwitcher());
        boardsIsland.getChildren().add(boardsTrigger);
        boardsIsland.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        VBox toolDrawer = buildToolDrawer();
        canvasToolDock = toolDrawer;

        toolDockToggleButton = new Button(toolsDrawerToggleModel.restChevronText());
        toolDockToggleButton.setId(TOOL_DOCK_TOGGLE_BUTTON_ID);
        toolDockToggleButton.getStyleClass().add("tool-dock-toggle");
        toolDockToggleButton.setFocusTraversable(false);
        toolDockToggleButton.setMnemonicParsing(false);
        Tooltip.install(toolDockToggleButton, createTooltip("Hide tools", ""));

        dockClipRect = new Rectangle(0, 0, 88, 400);
        dockClipRect.setArcWidth(8);
        dockClipRect.setArcHeight(8);

        dockSlideClipStack = new StackPane();
        dockSlideClipStack.setPickOnBounds(false);
        dockSlideClipStack.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        dockSlideClipStack.setClip(dockClipRect);
        dockSlideClipStack.getChildren().add(toolDrawer);
        StackPane.setAlignment(toolDrawer, Pos.TOP_LEFT);
        toolDrawer.setTranslateX(0);

        toolsChromeRow = new HBox(0, toolDockToggleButton, dockSlideClipStack);
        toolsChromeRow.setAlignment(Pos.TOP_LEFT);
        toolsChromeRow.setPickOnBounds(false);

        toolDockToggleButton.setOnAction(e -> toggleToolsDrawerSlide());

        toolDrawer.boundsInLocalProperty().addListener((o, a, b) -> updateToolsDrawerClipAndHostWidth());
        toolDrawer.widthProperty().addListener((o, a, b) -> updateToolsDrawerClipAndHostWidth());
        toolDrawer.heightProperty().addListener((o, a, b) -> updateToolsDrawerClipAndHostWidth());
        toolDockToggleButton.widthProperty().addListener((o, a, b) -> updateToolsDrawerClipAndHostWidth());

        toolsChromeRow.setEffect(UiEffects.toolbarDropShadow());

        /* Floating chrome: strict 16px workspace perimeter (margins on root StackPane children, not inner padding). */
        StackPane boardsLayer = wrapFloatingNode(boardsIsland, Pos.TOP_LEFT, new Insets(16, 0, 0, 16),
                WORKSPACE_TOOLBAR_LAYER_ID);
        StackPane dockLayer = wrapFloatingNode(toolsChromeRow, Pos.TOP_LEFT, new Insets(64, 0, 0, 16),
                WORKSPACE_DOCK_LAYER_ID);
        workspacePropertiesBar = buildPropertiesBar();
        drawingToolbar = toolsChromeRow;
        StackPane propertiesBarLayer = wrapFloatingNode(workspacePropertiesBar, Pos.TOP_CENTER, new Insets(16, 0, 0, 0),
                WORKSPACE_PROPERTIES_LAYER_ID);
        propertiesBarLayer.setEffect(UiEffects.toolbarDropShadow());

        collaborationRoster = new CollaborationRoster();
        HBox topRightIsland = buildTopRightHud();
        topRightIsland.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane topRightLayer = wrapFloatingNode(topRightIsland, Pos.TOP_RIGHT, new Insets(16, 16, 0, 0),
                WORKSPACE_PARTICIPANT_LAYER_ID);
        StackPane rosterLayer = wrapFloatingNode(collaborationRoster, Pos.CENTER_RIGHT, new Insets(16, 0, 16, 0),
                "whiteboard-workspace-roster-layer");

        performanceHud = new Label("Ping: — | FPS: —");
        performanceHud.getStyleClass().add("telemetry-hud-line");
        telemetryHudRoot = new HBox(performanceHud);
        telemetryHudRoot.getStyleClass().addAll("telemetry-hud", "telemetry-pill", "floating-panel");
        telemetryHudRoot.setPickOnBounds(true);
        telemetryHudRoot.setMouseTransparent(false);
        telemetryHudRoot.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane telemetryLayer = wrapFloatingNode(telemetryHudRoot, Pos.BOTTOM_RIGHT, new Insets(0, 16, 16, 0),
                "whiteboard-workspace-telemetry-layer");

        wireTelemetryHoverScale();

        micToggleBtn = new Button(MIC_OFF_GLYPH);
        micToggleBtn.setId(MIC_TOGGLE_BUTTON_ID);
        micToggleBtn.getStyleClass().add("mic-toggle-btn");
        micToggleBtn.getStyleClass().add("muted");
        micToggleBtn.setFocusTraversable(false);
        micToggleTooltip = createTooltip("Unmute mic", "");
        micToggleBtn.setTooltip(micToggleTooltip);
        micToggleBtn.setOnAction(e -> {
            if (networkClient == null) {
                return;
            }
            int perms = networkClient.getParticipantManager().getLocalPermissions();
            if (!RoomPermissions.canSpeak(perms)) {
                return;
            }
            AudioEngine audio = networkClient.getAudioEngine();
            audio.toggleMic();
            ParticipantManager participantManager = networkClient.getParticipantManager();
            Participant local = participantManager.get(networkClient.getClientId());
            if (local != null) {
                local.setMuted(audio.isMicMuted());
            }
        });

        StackPane micLayer = wrapFloatingNode(micToggleBtn, Pos.BOTTOM_LEFT, new Insets(0, 0, 16, 16),
                WORKSPACE_MIC_LAYER_ID);

        workspaceContentLayer.getChildren().addAll(canvasContainer, boardsLayer, dockLayer, propertiesBarLayer,
                topRightLayer, rosterLayer, micLayer, telemetryLayer);
        workspaceContentLayer.getChildren().add(1, spatialOverlayLayer);
        canvasSceneRoot.getChildren().add(workspaceContentLayer);

        Platform.runLater(this::updateToolsDrawerClipAndHostWidth);

        // ── Ownership tooltip — shown when hovering over a committed shape ────
        ownerTooltip = new Tooltip();
        ownerTooltip.setShowDelay(Duration.millis(200));
        ownerTooltip.setHideDelay(Duration.millis(100));
        ownerTooltip.setShowDuration(Duration.seconds(6));

        double canvasW = Math.clamp(visual.getWidth() * 0.92, 640, 1400);
        double canvasH = Math.clamp(visual.getHeight() * 0.90, 420, 920);
        canvasW = Math.min(canvasW, visual.getWidth() - 8);
        canvasH = Math.min(canvasH, visual.getHeight() - 32);
        canvasScene = new Scene(canvasSceneRoot, canvasW, canvasH);
        canvasScene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        attachShiftKeyTracking(canvasScene);

        // ── Canvas scene shortcuts: tools, undo, board switcher, dismiss overlay ─
        canvasScene.setOnKeyPressed(e -> {
            if (e.isControlDown() && e.getCode() == KeyCode.Z) {
                undoLastShape();
                return;
            }
            if (!(e.getTarget() instanceof TextInputControl)
                    && !e.isAltDown() && !e.isControlDown() && !e.isMetaDown() && !e.isShortcutDown()) {
                switch (e.getCode()) {
                    case L -> {
                        activateCanvasTool(Tool.LINE, canvasToolLine);
                        e.consume();
                    }
                    case P -> {
                        activateCanvasTool(Tool.FREEHAND, canvasToolPen);
                        e.consume();
                    }
                    case E -> {
                        activateCanvasTool(Tool.ERASER, canvasToolEraser);
                        e.consume();
                    }
                    case T -> {
                        activateCanvasTool(Tool.TEXT, canvasToolText);
                        e.consume();
                    }
                    default -> { /* fall through */ }
                }
            }
            if (e.getCode() == KeyCode.ESCAPE && isBoardSwitcherShowing()) {
                hideBoardSwitcher();
                e.consume();
                return;
            }
            if (e.isControlDown() && e.getCode() == KeyCode.TAB) {
                toggleBoardSwitcher();
                e.consume();
            }
        });

        Parent lobbyRoot = buildLobbyRoot();
        double shellW = Math.clamp(visual.getWidth() * 0.88, 480, 960);
        double shellH = Math.clamp(visual.getHeight() * 0.86, 380, 720);
        shellW = Math.min(shellW, visual.getWidth() - 8);
        shellH = Math.min(shellH, visual.getHeight() - 32);
        lobbyScene = new Scene(lobbyRoot, shellW, shellH);
        lobbyScene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());

        StackPane loginRoot = new StackPane();
        loginRoot.getStyleClass().add("login-root");

        Label loginTitle = new Label("Welcome to DistriSync");
        loginTitle.getStyleClass().add("lobby-header");
        loginTitle.setMaxWidth(Double.MAX_VALUE);

        Label loginSubtitle = new Label("Enter your display name to continue");
        loginSubtitle.getStyleClass().add("lobby-subtitle");
        loginSubtitle.setWrapText(true);
        loginSubtitle.setMaxWidth(Double.MAX_VALUE);

        TextField nameField = new TextField();
        nameField.setPromptText("Display name");
        nameField.getStyleClass().add("text-input-modern");
        nameField.setMaxWidth(Double.MAX_VALUE);

        Button joinNetworkBtn = new Button("Join Network");
        joinNetworkBtn.setId(LOGIN_JOIN_NETWORK_BUTTON_ID);
        joinNetworkBtn.getStyleClass().add("primary-button-large");
        joinNetworkBtn.setMaxWidth(Double.MAX_VALUE);

        VBox loginForm = new VBox(16, loginTitle, loginSubtitle, nameField, joinNetworkBtn);
        loginForm.setAlignment(Pos.TOP_LEFT);
        loginForm.setFillWidth(true);
        loginForm.setMaxWidth(Double.MAX_VALUE);

        VBox loginModalCard = new VBox(loginForm);
        loginModalCard.setId(LOGIN_MODAL_CARD_ID);
        loginModalCard.getStyleClass().add("modal-card");
        loginModalCard.setFillWidth(true);
        loginModalCard.setMaxWidth(600);
        StackPane.setAlignment(loginModalCard, Pos.CENTER);

        loginRoot.getChildren().add(loginModalCard);

        loginScene = new Scene(loginRoot, shellW, shellH);
        loginScene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());

        Runnable attemptJoin = () -> {
            String raw = nameField.getText() != null ? nameField.getText().strip() : "";
            if (raw.isEmpty()) {
                return;
            }
            authorName = raw;
            clientId = UUID.randomUUID().toString();
            initNetworking();
            stage.setScene(lobbyScene);
            stage.setTitle("DistriSync – Lobby");
        };
        joinNetworkBtn.setOnAction(e -> attemptJoin.run());
        nameField.setOnAction(e -> attemptJoin.run());

        stage.setScene(loginScene);
        stage.show();

        // controlPane must sit above cursorPane; toFront() reaffirms StackPane z-order.
        controlPane.toFront();

        eraserIntersection = new EraserSpatialIntersection(shapeSpatialGrid);
        setupEraserTool();

        // Wire all mouse events to the StackPane (always hit-testable)
        wireMouseEvents(canvasContainer);
    }

    static void enforceMinimumStageBounds(Stage stage) {
        stage.setMinWidth(MIN_STAGE_WIDTH);
        stage.setMinHeight(MIN_STAGE_HEIGHT);
        stage.widthProperty().addListener((obs, oldWidth, newWidth) -> {
            if (newWidth != null && newWidth.doubleValue() < MIN_STAGE_WIDTH) {
                stage.setWidth(MIN_STAGE_WIDTH);
            }
        });
        stage.heightProperty().addListener((obs, oldHeight, newHeight) -> {
            if (newHeight != null && newHeight.doubleValue() < MIN_STAGE_HEIGHT) {
                stage.setHeight(MIN_STAGE_HEIGHT);
            }
        });
    }

    private static StackPane wrapFloatingNode(Node node, Pos alignment, Insets margin, String nodeId) {
        StackPane layer = new StackPane(node);
        layer.setId(nodeId);
        layer.setPickOnBounds(false);
        layer.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(layer, alignment);
        StackPane.setMargin(layer, margin);
        return layer;
    }

    /**
     * Ordered board ids for the switcher grid: server list plus current board if missing
     * (race between SNAPSHOT and {@code BOARD_LIST_UPDATE}).
     */
    private List<String> resolveKnownBoardIdsForSwitcher() {
        List<String> boards = new ArrayList<>();
        if (networkClient != null) {
            for (String boardId : networkClient.getKnownBoards()) {
                if (boardId == null || boardId.isBlank()) {
                    continue;
                }
                boards.add(boardId.strip());
            }
            String curRaw = networkClient.getCurrentBoardId();
            final String curNorm = curRaw != null ? curRaw.strip() : "";
            if (!curNorm.isEmpty() && boards.stream().noneMatch(id -> Objects.equals(id, curNorm))) {
                boards.add(curNorm);
            }
        }
        return boards;
    }

    private void buildBoardSwitcherOverlay() {
        Region backdrop = new Region();
        backdrop.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        backdrop.getStyleClass().add("board-switcher-backdrop");
        backdrop.setOnMouseClicked(e -> hideBoardSwitcher());

        switcherBoardGrid = new FlowPane();
        switcherBoardGrid.setOrientation(Orientation.HORIZONTAL);
        switcherBoardGrid.setAlignment(Pos.CENTER);
        switcherBoardGrid.setHgap(25);
        switcherBoardGrid.setVgap(25);
        switcherBoardGrid.setPrefWrapLength(1000);
        switcherBoardGrid.setMaxWidth(1000);

        switcherOverlay = new StackPane(backdrop, switcherBoardGrid);
        switcherOverlay.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        StackPane.setAlignment(switcherBoardGrid, Pos.CENTER);
        switcherOverlay.setOpacity(0);
        switcherOverlay.setVisible(false);
        switcherOverlay.setMouseTransparent(false);
        switcherOverlay.setPickOnBounds(true);
    }

    private boolean isBoardSwitcherShowing() {
        return canvasSceneRoot != null
                && switcherOverlay != null
                && canvasSceneRoot.getChildren().contains(switcherOverlay);
    }

    private Animation boardSwitcherVisibilityAnimation;

    private void toggleBoardSwitcher() {
        if (isBoardSwitcherShowing()) {
            hideBoardSwitcher();
        } else {
            showBoardSwitcher();
        }
    }

    private void showBoardSwitcher() {
        if (canvasSceneRoot == null || switcherOverlay == null || switcherBoardGrid == null) {
            return;
        }
        if (canvasSceneRoot.getChildren().contains(switcherOverlay)) {
            return;
        }
        refreshSwitcherBoardGrid();
        Animation a = boardSwitcherVisibilityAnimation;
        boardSwitcherVisibilityAnimation = null;
        if (a != null) {
            a.stop();
        }
        if (canvasContainer != null) {
            canvasContainer.setEffect(boardSwitcherCanvasBlur);
        }
        switcherOverlay.setOpacity(0);
        switcherOverlay.setVisible(true);
        canvasSceneRoot.getChildren().add(switcherOverlay);
        FadeTransition fadeIn = new FadeTransition(Duration.millis(150), switcherOverlay);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);
        fadeIn.setOnFinished(ev -> {
            if (boardSwitcherVisibilityAnimation == fadeIn) {
                boardSwitcherVisibilityAnimation = null;
            }
        });
        boardSwitcherVisibilityAnimation = fadeIn;
        fadeIn.play();
    }

    private void hideBoardSwitcher() {
        if (!isBoardSwitcherShowing() || switcherOverlay == null) {
            if (canvasContainer != null) {
                canvasContainer.setEffect(null);
            }
            return;
        }
        Animation a = boardSwitcherVisibilityAnimation;
        boardSwitcherVisibilityAnimation = null;
        if (a != null) {
            a.stop();
        }
        FadeTransition fadeOut = new FadeTransition(Duration.millis(150), switcherOverlay);
        fadeOut.setFromValue(switcherOverlay.getOpacity());
        fadeOut.setToValue(0);
        fadeOut.setOnFinished(ev -> {
            if (canvasSceneRoot != null && switcherOverlay != null) {
                canvasSceneRoot.getChildren().remove(switcherOverlay);
            }
            if (switcherOverlay != null) {
                switcherOverlay.setVisible(false);
            }
            if (canvasContainer != null) {
                canvasContainer.setEffect(null);
            }
            if (boardSwitcherVisibilityAnimation == fadeOut) {
                boardSwitcherVisibilityAnimation = null;
            }
        });
        boardSwitcherVisibilityAnimation = fadeOut;
        fadeOut.play();
    }

    private void refreshSwitcherBoardGrid() {
        if (switcherBoardGrid == null) {
            return;
        }
        switcherBoardGrid.getChildren().clear();
        for (String boardId : resolveKnownBoardIdsForSwitcher()) {
            switcherBoardGrid.getChildren().add(createBoardSwitcherCard(boardId));
        }
        newBoardSwitcherCard = createNewBoardSwitcherCard();
        switcherBoardGrid.getChildren().add(newBoardSwitcherCard);
    }

    private VBox createBoardSwitcherCard(String boardId) {
        VBox card = new VBox(4);
        card.setMinSize(240, 160);
        card.setMaxSize(240, 160);
        card.setPadding(new Insets(4, 10, 4, 10));
        card.setAlignment(Pos.TOP_CENTER);
        card.getStyleClass().add("board-switcher-card");

        StackPane thumb = new StackPane();
        thumb.setPrefSize(220, 124);
        thumb.setMinSize(220, 124);
        thumb.setMaxSize(220, 124);
        thumb.getStyleClass().add("board-switcher-thumb");

        Image snap = boardSnapshots.get(boardId);
        if (snap != null) {
            ImageView iv = new ImageView(snap);
            iv.setFitWidth(220);
            iv.setFitHeight(124);
            iv.setPreserveRatio(true);
            iv.setSmooth(true);
            thumb.getChildren().add(iv);
        } else {
            Rectangle placeholder = new Rectangle(220, 124);
            placeholder.setArcWidth(8);
            placeholder.setArcHeight(8);
            placeholder.setFill(Color.web("#334155"));
            placeholder.setStroke(Color.web("#475569"));
            placeholder.setStrokeWidth(1);
            thumb.getChildren().add(placeholder);
        }

        Button trashButton = new Button("✕");
        trashButton.getStyleClass().add("board-switcher-trash-btn");
        trashButton.setFocusTraversable(false);
        trashButton.setTooltip(new Tooltip("Delete board"));
        trashButton.setPickOnBounds(true);
        if (networkClient != null) {
            trashButton.setVisible(
                    RoomPermissions.canManageRoom(networkClient.getParticipantManager().getLocalPermissions())
                            && !boardId.equals(MessageCodec.DEFAULT_INITIAL_BOARD_ID));
        } else {
            trashButton.setVisible(false);
        }
        trashButton.setOnAction(e -> {
            e.consume();
            confirmDeleteBoard(boardId);
        });
        StackPane.setAlignment(trashButton, Pos.TOP_RIGHT);
        StackPane.setMargin(trashButton, new Insets(4, 4, 0, 0));
        thumb.getChildren().add(trashButton);

        Label name = new Label(boardId);
        name.setWrapText(true);
        name.setMaxWidth(220);
        name.getStyleClass().add("board-switcher-title");

        card.getChildren().addAll(thumb, name);

        card.setOnMouseEntered(e -> {
            playBoardSwitcherCardScale(card, 1.05);
            card.getStyleClass().add("board-switcher-card-hover");
        });
        card.setOnMouseExited(e -> {
            playBoardSwitcherCardScale(card, 1.0);
            card.getStyleClass().remove("board-switcher-card-hover");
        });
        card.setOnMouseClicked(e -> {
            e.consume();
            if (networkClient == null) {
                return;
            }
            if (Objects.equals(boardId, networkClient.getCurrentBoardId())) {
                hideBoardSwitcher();
                return;
            }
            switchBoard(boardId);
            hideBoardSwitcher();
        });
        return card;
    }

    private VBox createNewBoardSwitcherCard() {
        VBox card = new VBox(4);
        card.setMinSize(240, 160);
        card.setMaxSize(240, 160);
        card.setPadding(new Insets(4, 10, 4, 10));
        card.setAlignment(Pos.TOP_CENTER);
        card.getStyleClass().add("board-switcher-card");
        StackPane thumb = new StackPane();
        thumb.setPrefSize(220, 124);
        thumb.setMinSize(220, 124);
        thumb.setMaxSize(220, 124);
        thumb.getStyleClass().add("board-switcher-thumb");
        Label plus = new Label("+");
        plus.getStyleClass().add("board-switcher-plus");
        thumb.getChildren().add(plus);
        Label hint = new Label("New board");
        hint.setMaxWidth(220);
        hint.getStyleClass().add("board-switcher-hint");
        card.getChildren().addAll(thumb, hint);
        card.setOnMouseEntered(e -> {
            playBoardSwitcherCardScale(card, 1.05);
            card.getStyleClass().add("board-switcher-card-hover");
        });
        card.setOnMouseExited(e -> {
            playBoardSwitcherCardScale(card, 1.0);
            card.getStyleClass().remove("board-switcher-card-hover");
        });
        card.setOnMouseClicked(e -> {
            e.consume();
            hideBoardSwitcher();
            promptNewWorkspaceBoard();
        });
        return card;
    }

    private static final Object BOARD_SWITCHER_CARD_SCALE_TX_KEY = new Object();

    private static void playBoardSwitcherCardScale(VBox card, double scale) {
        Object prev = card.getProperties().get(BOARD_SWITCHER_CARD_SCALE_TX_KEY);
        if (prev instanceof ScaleTransition running) {
            running.stop();
        }
        ScaleTransition st = new ScaleTransition(Duration.millis(150), card);
        st.setToX(scale);
        st.setToY(scale);
        card.getProperties().put(BOARD_SWITCHER_CARD_SCALE_TX_KEY, st);
        st.setOnFinished(ev -> {
            if (card.getProperties().get(BOARD_SWITCHER_CARD_SCALE_TX_KEY) == st) {
                card.getProperties().remove(BOARD_SWITCHER_CARD_SCALE_TX_KEY);
            }
        });
        st.play();
    }

    /**
     * Captures {@link #baseCanvas} for the board we are leaving, then sends {@code SWITCH_BOARD}.
     * Call from the JavaFX Application Thread only.
     */
    private void switchBoard(String targetBoardId) {
        if (networkClient == null || targetBoardId == null) {
            return;
        }
        String target = targetBoardId.strip();
        if (target.isEmpty()) {
            return;
        }
        String curRaw = networkClient.getCurrentBoardId();
        String cur = curRaw != null ? curRaw.strip() : "";
        if (!cur.isEmpty() && !cur.equals(target) && baseCanvas != null) {
            double bw = baseCanvas.getWidth();
            double bh = baseCanvas.getHeight();
            if (bw > 1 && bh > 1) {
                try {
                    Image snap = baseCanvas.snapshot(null, null);
                    boardSnapshots.put(cur, snap);
                } catch (RuntimeException ex) {
                    log.debug("Board snapshot failed: {}", ex.getMessage());
                }
            }
        }
        networkClient.sendSwitchBoard(target);
    }

    private void stopSnapshotHydrationAndResetOpacity() {
        Animation a = snapshotHydrationAnimation;
        snapshotHydrationAnimation = null;
        if (a != null) {
            a.stop();
        }
        if (canvasContainer != null) {
            canvasContainer.setOpacity(1.0);
        }
    }

    /**
     * Dark-themed name entry for creating a board via {@code SWITCH_BOARD}.
     */
    private void promptNewWorkspaceBoard() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("New board");
        dialog.setHeaderText(null);
        dialog.setGraphic(null);
        dialog.setContentText("New Board Name");
        DialogPane pane = dialog.getDialogPane();
        if (canvasScene != null && canvasScene.getStylesheets() != null) {
            pane.getStylesheets().addAll(canvasScene.getStylesheets());
        }
        pane.getStyleClass().add("workspace-input-dialog");
        Optional<String> result = dialog.showAndWait();
        result.map(String::strip)
                .filter(s -> !s.isEmpty())
                .ifPresent(this::switchBoard);
    }

    @Override
    public void stop() {
        shutdown();
        // Force a clean JVM exit to bypass the JavaFX Direct3D native teardown that
        // produces a STATUS_STACK_BUFFER_OVERRUN (0xC0000409) crash on Windows when
        // the D3D pipeline cleans up its native threads after the FX toolkit exits.
        System.exit(0);
    }

    // =========================================================================
    // Toolbar construction
    // =========================================================================

    private void toggleToolsDrawerSlide() {
        if (toolDockToggleButton == null || canvasToolDock == null || dockSlideClipStack == null) {
            return;
        }
        canvasToolDock.applyCss();
        canvasToolDock.layout();
        double panelW = Math.max(canvasToolDock.getBoundsInLocal().getWidth(), canvasToolDock.prefWidth(-1));
        if (panelW <= 1) {
            panelW = 56;
        }
        if (toolsDrawerSlideTransition != null) {
            toolsDrawerSlideTransition.stop();
        }
        if (!toolsDrawerToggleModel.isToolsOpen()) {
            double h = Math.max(dockSlideClipStack.snapSizeY(canvasToolDock.prefHeight(-1)), 160);
            dockClipRect.setWidth(panelW);
            dockClipRect.setHeight(h);
            dockSlideClipStack.setMinWidth(panelW);
            dockSlideClipStack.setPrefWidth(panelW);
            dockSlideClipStack.setMaxWidth(panelW);
            dockSlideClipStack.setMinHeight(h);
            dockSlideClipStack.setPrefHeight(h);
            dockSlideClipStack.setMaxHeight(h);
        }
        double fromX = canvasToolDock.getTranslateX();
        double toX = toolsDrawerToggleModel.animationTargetTranslateX(panelW);
        toolDockToggleButton.setText(toolsDrawerToggleModel.chevronAtAnimationStart());
        toolsDrawerSlideTransition = new TranslateTransition(Duration.millis(250), canvasToolDock);
        toolsDrawerSlideTransition.setFromX(fromX);
        toolsDrawerSlideTransition.setToX(toX);
        toolsDrawerSlideTransition.setOnFinished(ev -> {
            toolsDrawerToggleModel.commitAfterToggleAnimation();
            toolDockToggleButton.setText(toolsDrawerToggleModel.restChevronText());
            String tip = toolsDrawerToggleModel.isToolsOpen() ? "Hide tools" : "Show tools";
            Tooltip.install(toolDockToggleButton, createTooltip(tip, ""));
            if (!toolsDrawerToggleModel.isToolsOpen()) {
                dockSlideClipStack.setMinWidth(0);
                dockSlideClipStack.setPrefWidth(0);
                dockSlideClipStack.setMaxWidth(0);
            }
            updateToolsDrawerClipAndHostWidth();
            toolsDrawerSlideTransition = null;
        });
        toolsDrawerSlideTransition.play();
    }

    private void updateToolsDrawerClipAndHostWidth() {
        if (dockClipRect == null || dockSlideClipStack == null || canvasToolDock == null
                || toolDockToggleButton == null || toolsChromeRow == null) {
            return;
        }
        dockSlideClipStack.applyCss();
        dockSlideClipStack.layout();
        canvasToolDock.applyCss();
        canvasToolDock.layout();
        double dw = Math.max(
                canvasToolDock.getBoundsInLocal().getWidth(),
                dockSlideClipStack.snapSizeX(canvasToolDock.prefWidth(-1)));
        if (dw <= 0) {
            dw = 56;
        }
        double h = Math.max(dockSlideClipStack.snapSizeY(canvasToolDock.prefHeight(-1)), 160);
        boolean animating = toolsDrawerSlideTransition != null
                && toolsDrawerSlideTransition.getStatus() == Animation.Status.RUNNING;
        if (!animating) {
            canvasToolDock.setTranslateX(toolsDrawerToggleModel.restPanelTranslateX(dw));
        }
        if (toolsDrawerToggleModel.isToolsOpen()) {
            dockClipRect.setWidth(dw);
        } else {
            dockClipRect.setWidth(0);
        }
        dockClipRect.setHeight(h);

        double toggleH = Math.max(dockSlideClipStack.snapSizeY(toolDockToggleButton.prefHeight(-1)), h);
        toolsChromeRow.setMinHeight(toggleH);
        toolsChromeRow.setPrefHeight(toggleH);

        if (toolsDrawerToggleModel.isToolsOpen()) {
            dockSlideClipStack.setMinWidth(dw);
            dockSlideClipStack.setPrefWidth(dw);
            dockSlideClipStack.setMaxWidth(dw);
        } else {
            dockSlideClipStack.setMinWidth(0);
            dockSlideClipStack.setPrefWidth(0);
            dockSlideClipStack.setMaxWidth(0);
        }
        dockSlideClipStack.setMinHeight(h);
        dockSlideClipStack.setPrefHeight(h);
        dockSlideClipStack.setMaxHeight(h);
    }

    /** Vertical floating left dock for primary drawing tools. */
    VBox buildToolDrawer() {
        VBox toolDock = new VBox(6);
        toolDock.getStyleClass().addAll("tool-dock", "floating-panel", "toolbar");
        toolDock.setAlignment(Pos.TOP_CENTER);
        toolDock.setPickOnBounds(false);
        toolDock.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        canvasToolGroup = new ToggleGroup();
        // Icon glyphs (tooltip carries full names); geometric / tool Unicode for compact 40×40 toggles.
        canvasToolLine   = toolToggle("\u21F1", "Line", "L", canvasToolGroup, true);
        canvasToolPen    = toolToggle("\u270E", "Pen", "P", canvasToolGroup, false);
        canvasToolEraser = toolToggle("\u25A7", "Eraser", "E", canvasToolGroup, false);
        canvasToolRectangle = toolToggle("\u25AD", "Rectangle", "R", canvasToolGroup, false);
        canvasToolEllipse = toolToggle("\u2B2D", "Ellipse", "O", canvasToolGroup, false);
        canvasToolArrow = toolToggle("\u2192", "Arrow", "A", canvasToolGroup, false);
        canvasToolText   = toolToggle("T", "Text", "T", canvasToolGroup, false);
        canvasToolLine.setId(TOOL_DOCK_SELECT_BUTTON_ID);
        canvasToolPen.setId(TOOL_DOCK_PEN_BUTTON_ID);
        canvasToolEraser.setId(TOOL_DOCK_ERASER_BUTTON_ID);
        canvasToolRectangle.setId(TOOL_DOCK_RECTANGLE_BUTTON_ID);
        canvasToolEllipse.setId(TOOL_DOCK_ELLIPSE_BUTTON_ID);
        canvasToolArrow.setId(TOOL_DOCK_ARROW_BUTTON_ID);

        canvasToolLine.setOnAction(e       -> activateCanvasTool(Tool.LINE, canvasToolLine));
        canvasToolPen.setOnAction(e        -> activateCanvasTool(Tool.FREEHAND, canvasToolPen));
        canvasToolEraser.setOnAction(e     -> activateCanvasTool(Tool.ERASER, canvasToolEraser));
        canvasToolRectangle.setOnAction(e  -> activateCanvasTool(Tool.RECTANGLE, canvasToolRectangle));
        canvasToolEllipse.setOnAction(e    -> activateCanvasTool(Tool.ELLIPSE, canvasToolEllipse));
        canvasToolArrow.setOnAction(e      -> activateCanvasTool(Tool.ARROW, canvasToolArrow));
        canvasToolText.setOnAction(e       -> activateCanvasTool(Tool.TEXT, canvasToolText));

        canvasToolGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            applyToolToggleStyleClasses(canvasToolGroup);
            if (newToggle == null) return;
            if (newToggle == canvasToolEraser) {
                syncEraserTypeToggleButtons();
                applyEraserCursorToCanvas();
            } else {
                Cursor cursor = (newToggle == canvasToolText) ? Cursor.TEXT : Cursor.CROSSHAIR;
                applyDrawingCursor(cursor);
            }
        });
        applyToolToggleStyleClasses(canvasToolGroup);
        applyDrawingCursor(Cursor.CROSSHAIR);

        Button undoBtn = new Button("⤺");
        undoBtn.getStyleClass().add("tool-btn");
        undoBtn.setPrefSize(40, 40);
        undoBtn.setMinSize(40, 40);
        undoBtn.setMaxSize(40, 40);
        undoBtn.setAlignment(Pos.CENTER);
        undoBtn.setContentDisplay(ContentDisplay.CENTER);
        undoBtn.setFont(Font.font(null, FontWeight.NORMAL, 16));
        undoBtn.setFocusTraversable(false);
        undoBtn.setMnemonicParsing(false);
        undoBtn.setOnAction(e -> undoLastShape());
        Tooltip.install(undoBtn, createTooltip("Undo", "Ctrl+Z"));

        Button clearBtn = new Button("⌧");
        clearBtn.setId(TOOLBAR_CLEAR_BUTTON_ID);
        clearBtn.getStyleClass().add("tool-btn");
        clearBtn.setPrefSize(40, 40);
        clearBtn.setMinSize(40, 40);
        clearBtn.setMaxSize(40, 40);
        clearBtn.setAlignment(Pos.CENTER);
        clearBtn.setContentDisplay(ContentDisplay.CENTER);
        clearBtn.setFont(Font.font(null, FontWeight.NORMAL, 16));
        clearBtn.setFocusTraversable(false);
        clearBtn.setMnemonicParsing(false);
        clearBtn.setOnAction(e -> clearBoard());
        Tooltip.install(clearBtn, createTooltip("Clear board", ""));

        toolDock.getChildren().addAll(
                canvasToolLine, canvasToolPen, canvasToolRectangle, canvasToolEllipse, canvasToolArrow,
                canvasToolEraser, canvasToolText,
                undoBtn, clearBtn);
        return toolDock;
    }

    private HBox buildPropertiesBar() {
        colorPicker = new ColorPicker(globalCanvasContext.getActiveColor());
        colorPicker.valueProperty().bindBidirectional(globalCanvasContext.activeColorProperty());
        colorPicker.getStyleClass().add("properties-color-picker");
        colorPicker.setFocusTraversable(false);
        strokeSlider = new Slider(1, 20, globalCanvasContext.getActiveStrokeWidth());
        strokeSlider.valueProperty().bindBidirectional(globalCanvasContext.activeStrokeWidthProperty());
        strokeSlider.setShowTickLabels(false);
        strokeSlider.getStyleClass().add("properties-stroke-slider");
        strokeSlider.setPrefWidth(180);
        Tooltip.install(colorPicker, createTooltip("Color", ""));
        Tooltip.install(strokeSlider, createTooltip("Stroke Width", ""));

        ToggleGroup eraserTypeGroup = new ToggleGroup();
        eraserTypeCircleBtn = new ToggleButton("○");
        eraserTypeCircleBtn.setId(ERASER_TYPE_CIRCLE_BUTTON_ID);
        eraserTypeCircleBtn.getStyleClass().addAll("tool-btn", "properties-eraser-type-btn");
        eraserTypeCircleBtn.setToggleGroup(eraserTypeGroup);
        eraserTypeCircleBtn.setFocusTraversable(false);
        eraserTypeCircleBtn.setPrefSize(32, 32);
        Tooltip.install(eraserTypeCircleBtn, createTooltip("Stroke eraser", ""));

        eraserTypeSquareBtn = new ToggleButton("□");
        eraserTypeSquareBtn.setId(ERASER_TYPE_SQUARE_BUTTON_ID);
        eraserTypeSquareBtn.getStyleClass().addAll("tool-btn", "properties-eraser-type-btn");
        eraserTypeSquareBtn.setToggleGroup(eraserTypeGroup);
        eraserTypeSquareBtn.setFocusTraversable(false);
        eraserTypeSquareBtn.setPrefSize(32, 32);
        Tooltip.install(eraserTypeSquareBtn, createTooltip("Block eraser", ""));

        eraserTypeCircleBtn.setSelected(globalCanvasContext.getActiveEraserType() == EraserType.CIRCLE);
        eraserTypeSquareBtn.setSelected(globalCanvasContext.getActiveEraserType() == EraserType.SQUARE);
        eraserTypeCircleBtn.setOnAction(e -> globalCanvasContext.setActiveEraserType(EraserType.CIRCLE));
        eraserTypeSquareBtn.setOnAction(e -> globalCanvasContext.setActiveEraserType(EraserType.SQUARE));

        eraserTypeBar = new HBox(6, eraserTypeCircleBtn, eraserTypeSquareBtn);
        eraserTypeBar.setId(WORKSPACE_ERASER_TYPE_BAR_ID);
        eraserTypeBar.setAlignment(Pos.CENTER);
        eraserTypeBar.getStyleClass().add("properties-eraser-type-bar");

        BooleanBinding eraserToolActive = Bindings.createBooleanBinding(
                () -> canvasToolGroup != null && canvasToolGroup.getSelectedToggle() == canvasToolEraser,
                canvasToolGroup != null ? canvasToolGroup.selectedToggleProperty() : null);
        eraserTypeBar.visibleProperty().bind(eraserToolActive);
        eraserTypeBar.managedProperty().bind(eraserToolActive);

        HBox propertiesBar = new HBox(12, colorPicker, strokeSlider, eraserTypeBar);
        propertiesBar.setId(WORKSPACE_PROPERTIES_BAR_ID);
        propertiesBar.getStyleClass().addAll("properties-bar", "floating-panel", "toolbar", "workspace-perimeter-node");
        propertiesBar.setAlignment(Pos.CENTER);
        propertiesBar.setPickOnBounds(false);
        propertiesBar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        return propertiesBar;
    }

    /** Package-visible for TestFX; not a static singleton. */
    GlobalCanvasContext getGlobalCanvasContext() {
        return globalCanvasContext;
    }

    /** HUD tool toggles: {@code tool-btn-active} on the selected tool, {@code tool-btn} on all. */
    private static void applyToolToggleStyleClasses(ToggleGroup group) {
        Toggle selected = group.getSelectedToggle();
        for (Toggle t : group.getToggles()) {
            if (t instanceof ToggleButton tb) {
                tb.getStyleClass().remove("tool-btn-active");
                if (t == selected) {
                    tb.getStyleClass().add("tool-btn-active");
                }
            }
        }
    }

    /** Top-right floating island: watch party + leave room + connection status (initialises {@link #statusLabel}). */
    private HBox buildTopRightHud() {
        watchPartyBtn = new Button("▶ Start Watch Party");
        watchPartyBtn.setStyle(
                "-fx-background-color: #6366F1; -fx-text-fill: white; -fx-font-weight: bold; "
                        + "-fx-padding: 8px 16px; -fx-background-radius: 6px; -fx-cursor: hand;");
        watchPartyBtn.setFocusTraversable(false);
        watchPartyBtn.setMnemonicParsing(false);
        watchPartyBtn.setOnAction(e -> toggleWatchPartyUI());
        Tooltip.install(watchPartyBtn, createTooltip("Watch party / YouTube", ""));

        Button leaveRoomBtn = new Button("Leave Room");
        leaveRoomBtn.setId(LEAVE_ROOM_BUTTON_ID);
        leaveRoomBtn.getStyleClass().add("leave-room-button");
        leaveRoomBtn.setFocusTraversable(false);
        leaveRoomBtn.setMnemonicParsing(false);
        leaveRoomBtn.setOnAction(e -> leaveCanvasRoom(true));
        leaveRoomBtn.setTooltip(new Tooltip("Return to the lobby"));

        btnDeleteRoom = new Button("Delete Room");
        btnDeleteRoom.setId(DELETE_ROOM_BUTTON_ID);
        btnDeleteRoom.getStyleClass().add("danger-btn");
        btnDeleteRoom.setFocusTraversable(false);
        btnDeleteRoom.setMnemonicParsing(false);
        btnDeleteRoom.setVisible(false);
        btnDeleteRoom.setManaged(false);
        btnDeleteRoom.setTooltip(new Tooltip("Permanently delete this room"));
        btnDeleteRoom.setOnAction(e -> confirmDeleteCurrentRoom());

        statusLabel = new Label("⬤ Offline");
        statusLabel.getStyleClass().add("status-text");
        statusLabel.setTextFill(Color.web(RED));
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(200);

        HBox row = new HBox(10);
        row.getChildren().addAll(watchPartyBtn, leaveRoomBtn, btnDeleteRoom, statusLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("hud-panel");
        row.setPickOnBounds(false);
        row.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        return row;
    }

    /**
     * Compact square tool toggle: {@code glyph} is a single icon character; {@code name} / {@code shortcut}
     * are shown only in the tooltip.
     */
    private ToggleButton toolToggle(String glyph, String name, String shortcut, ToggleGroup group, boolean selected) {
        ToggleButton btn = new ToggleButton();
        btn.setToggleGroup(group);
        btn.setSelected(selected);
        btn.setPrefSize(40, 40);
        btn.setMinSize(40, 40);
        btn.setMaxSize(40, 40);
        btn.setAlignment(Pos.CENTER);
        btn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        btn.setGraphicTextGap(0);
        btn.setText(null);
        Label glyphLabel = new Label(glyph);
        glyphLabel.getStyleClass().add("tool-btn-icon");
        glyphLabel.setFont(Font.font(null, FontWeight.NORMAL, 18));
        StackPane iconWrap = new StackPane(glyphLabel);
        iconWrap.getStyleClass().add("tool-dock-icon-wrap");
        StackPane.setAlignment(glyphLabel, Pos.CENTER);
        iconWrap.setMinSize(20, 20);
        iconWrap.setPrefSize(20, 20);
        iconWrap.setMaxSize(20, 20);
        btn.setGraphic(iconWrap);
        btn.getStyleClass().add("tool-btn");
        btn.setFocusTraversable(false);
        btn.setMnemonicParsing(false);
        Tooltip.install(btn, createTooltip(name, shortcut));
        return btn;
    }

    /**
     * Glass-styled tooltip text "{@code Name (shortcut)}" with snappy show/hide delays.
     * When {@code shortcut} is blank, only {@code name} is shown.
     */
    private Tooltip createTooltip(String name, String shortcut) {
        Tooltip tooltip = new Tooltip();
        if (shortcut == null || shortcut.isBlank()) {
            tooltip.setText(name);
        } else {
            tooltip.setText(name + " (" + shortcut + ")");
        }
        tooltip.setShowDelay(Duration.millis(200));
        tooltip.setHideDelay(Duration.millis(100));
        return tooltip;
    }

    private void activateCanvasTool(Tool tool, ToggleButton toggle) {
        if (toggle == null || canvasToolGroup == null) {
            return;
        }
        dismissActiveTextField();
        activeTool = tool;
        canvasToolGroup.selectToggle(toggle);
    }

    // =========================================================================
    // Lobby scene
    // =========================================================================

    /**
     * Wires the lobby refresh control: each successful activation runs {@code fetchLobbyAction}
     * once, disables the button for 1s, then restores label and enabled state.
     * Package-private for unit tests ({@code WhiteboardAppLobbyTest}).
     */
    static void wireLobbyRefreshButton(Button refreshBtn, Runnable fetchLobbyAction) {
        Objects.requireNonNull(refreshBtn, "refreshBtn");
        Objects.requireNonNull(fetchLobbyAction, "fetchLobbyAction");
        if (!refreshBtn.getStyleClass().contains("refresh-btn")) {
            refreshBtn.getStyleClass().add("refresh-btn");
        }
        AtomicBoolean inCooldown = new AtomicBoolean(false);
        refreshBtn.setText("Refresh ↻");
        refreshBtn.setOnAction(e -> {
            if (!inCooldown.compareAndSet(false, true)) {
                return;
            }
            fetchLobbyAction.run();
            refreshBtn.setDisable(true);
            refreshBtn.setText("Refreshing...");
            PauseTransition cooldown = new PauseTransition(Duration.millis(1000));
            cooldown.setOnFinished(ev -> {
                inCooldown.set(false);
                refreshBtn.setDisable(false);
                refreshBtn.setText("Refresh ↻");
            });
            cooldown.play();
        });
    }

    private Parent buildLobbyRoot() {
        StackPane root = new StackPane();
        root.getStyleClass().add("lobby-root");
        root.setPrefSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);

        VBox container = new VBox();
        container.setFillWidth(true);
        container.setPrefSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
        container.setMaxWidth(Double.MAX_VALUE);
        container.setMaxHeight(Double.MAX_VALUE);
        container.getStyleClass().add("lobby-container");

        Label header = new Label("DistriSync Lobby");
        header.getStyleClass().add("lobby-header");
        header.setMaxWidth(Double.MAX_VALUE);

        newRoomField = new TextField();
        newRoomField.setPromptText("New room name…");
        newRoomField.getStyleClass().add("lobby-textfield");
        newRoomField.setMinWidth(160);
        newRoomField.setMaxWidth(Double.MAX_VALUE);

        Button createBtn = new Button("Create Room");
        createBtn.getStyleClass().add("tool-button");
        createBtn.setOnAction(e -> joinRoomFromLobby(newRoomField.getText()));
        newRoomField.setOnAction(e -> joinRoomFromLobby(newRoomField.getText()));

        HBox createRow = new HBox(12);
        createRow.setAlignment(Pos.CENTER_LEFT);
        createRow.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(newRoomField, Priority.ALWAYS);
        createRow.getChildren().addAll(newRoomField, createBtn);
        VBox.setMargin(createRow, new Insets(0, 0, 32, 0));

        Label availableRoomsLabel = new Label("Available Rooms");
        availableRoomsLabel.getStyleClass().add("lobby-room-title");
        availableRoomsLabel.setWrapText(true);
        availableRoomsLabel.setMaxWidth(Double.MAX_VALUE);

        Button refreshBtn = new Button("Refresh ↻");
        wireLobbyRefreshButton(refreshBtn, () -> {
            if (networkClient != null && networkClient.isRunning()) {
                networkClient.sendFetchLobby();
            }
        });

        FlowPane lobbyRoomsHeaderRow = new FlowPane(10, 10, availableRoomsLabel, refreshBtn);
        lobbyRoomsHeaderRow.setOrientation(Orientation.HORIZONTAL);
        lobbyRoomsHeaderRow.setAlignment(Pos.CENTER_LEFT);
        lobbyRoomsHeaderRow.prefWrapLengthProperty().bind(Bindings.createDoubleBinding(
                () -> Math.max(200, container.getWidth() - 8),
                container.widthProperty()));

        lobbyRoomList = new VBox(10);
        lobbyRoomList.setFillWidth(true);

        lobbyEmptyStateLabel = new Label("No active rooms. Create one to get started.");
        lobbyEmptyStateLabel.getStyleClass().add("empty-state-text");
        lobbyEmptyStateLabel.setWrapText(true);
        lobbyEmptyStateLabel.setMaxWidth(Double.MAX_VALUE);
        lobbyEmptyStateLabel.setVisible(true);
        lobbyEmptyStateLabel.setManaged(true);

        VBox roomsSection = new VBox(10);
        roomsSection.setFillWidth(true);
        roomsSection.setPrefSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
        roomsSection.getChildren().addAll(lobbyRoomsHeaderRow, lobbyEmptyStateLabel, lobbyRoomList);
        VBox.setVgrow(lobbyRoomList, Priority.ALWAYS);

        ScrollPane scroll = new ScrollPane(roomsSection);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollBarPolicy.NEVER);
        scroll.setMinHeight(220);
        scroll.getStyleClass().add("lobby-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        lobbyStatusLabel = new Label("Connecting…");
        lobbyStatusLabel.setWrapText(true);
        lobbyStatusLabel.setMaxWidth(560);
        lobbyStatusLabel.setMouseTransparent(true);

        lobbyToastShell = new StackPane(lobbyStatusLabel);
        lobbyToastShell.setId(LOBBY_TOAST_SHELL_ID);
        lobbyToastShell.getStyleClass().add("toast-notification");
        lobbyToastShell.setPickOnBounds(false);
        lobbyToastShell.setMouseTransparent(true);
        lobbyToastShell.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(lobbyToastShell, Pos.BOTTOM_CENTER);
        StackPane.setMargin(lobbyToastShell, new Insets(0, 0, 32, 0));

        container.getChildren().addAll(header, createRow, scroll);

        VBox lobbyModalCard = new VBox(container);
        lobbyModalCard.setId(LOBBY_MODAL_CARD_ID);
        lobbyModalCard.getStyleClass().addAll("lobby-floating-card", "floating-panel");
        lobbyModalCard.setFillWidth(true);
        lobbyModalCard.setMaxWidth(600);
        StackPane.setAlignment(lobbyModalCard, Pos.CENTER);

        root.getChildren().addAll(lobbyModalCard, lobbyToastShell);
        setLobbyStickyStatus("Connecting…", "lobby-status-muted");
        return root;
    }

    private void cancelLobbyToastAnimation() {
        if (lobbyToastSequence != null) {
            lobbyToastSequence.stop();
            lobbyToastSequence = null;
        }
    }

    /**
     * Lobby status that stays visible (connection line) until replaced.
     */
    private void setLobbyStickyStatus(String message, String styleClass) {
        cancelLobbyToastAnimation();
        if (lobbyStatusLabel == null || lobbyToastShell == null) {
            return;
        }
        lobbyStatusLabel.setText(message);
        lobbyStatusLabel.getStyleClass().clear();
        lobbyStatusLabel.getStyleClass().add(styleClass);
        lobbyToastShell.setVisible(true);
        lobbyToastShell.setOpacity(1);
    }

    /**
     * Short-lived toast: fade in 200 ms, hold 2 s, fade out 200 ms. Pass-through for mouse events.
     */
    void showToast(String message) {
        showToast(message, "lobby-status-muted");
    }

    void showToast(String message, String labelStyleClass) {
        if (lobbyStatusLabel == null || lobbyToastShell == null) {
            return;
        }
        cancelLobbyToastAnimation();
        lobbyStatusLabel.setText(message);
        lobbyStatusLabel.getStyleClass().clear();
        lobbyStatusLabel.getStyleClass().add(labelStyleClass);
        lobbyToastShell.setVisible(true);
        lobbyToastShell.setOpacity(0);

        FadeTransition fadeIn = new FadeTransition(Duration.millis(200), lobbyToastShell);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);
        PauseTransition hold = new PauseTransition(Duration.seconds(2));
        FadeTransition fadeOut = new FadeTransition(Duration.millis(200), lobbyToastShell);
        fadeOut.setFromValue(1);
        fadeOut.setToValue(0);
        SequentialTransition seq = new SequentialTransition(fadeIn, hold, fadeOut);
        seq.setOnFinished(e -> {
            lobbyToastSequence = null;
            lobbyToastShell.setVisible(false);
        });
        lobbyToastSequence = seq;
        seq.play();
    }

    /**
     * Joins or creates a room by id (lobby UI). No-op if disconnected or blank.
     */
    private void joinRoomFromLobby(String roomIdOrName) {
        String name = roomIdOrName != null ? roomIdOrName.strip() : "";
        if (name.isBlank()) {
            return;
        }
        if (networkClient == null || !networkClient.isRunning()) {
            showToast("Not connected — start the server or wait for connection.", "lobby-status-disconnected");
            return;
        }
        long now = System.currentTimeMillis();
        if (name.equals(lastLobbyJoinRoomId) && (now - lastLobbyJoinMillis) < 500) {
            return;
        }
        lastLobbyJoinMillis = now;
        lastLobbyJoinRoomId = name;
        showToast("Joining room \"" + name + "\"…");
        networkClient.sendJoinRoom(name);
        scheduleLobbyJoinWatchdog();
    }

    /** True when speak permission was removed between two server-provided bitmasks. */
    static boolean lostSpeakPermission(int oldPerms, int newPerms) {
        return RoomPermissions.canSpeak(oldPerms) && !RoomPermissions.canSpeak(newPerms);
    }

    /**
     * Applies a {@link com.distrisync.protocol.MessageType#ROLE_UPDATE} on the FX thread.
     */
    private void applyRoleUpdate(MessageCodec.RoleUpdatePayload payload) {
        if (networkClient == null || payload == null) {
            return;
        }
        String affectedId = payload.newHostClientId();
        int newPerms = payload.newPermissions();
        ParticipantManager manager = networkClient.getParticipantManager();
        // Roster crowns and moderation UI follow permissions for every affected client, not only local.
        manager.updatePermissions(affectedId, newPerms);
        boolean isLocal = affectedId.equals(networkClient.getClientId());
        int oldPerms = isLocal ? manager.getLocalPermissions() : 0;
        if (payload.roomHostClientId() != null && !payload.roomHostClientId().isBlank()) {
            manager.setHostClientId(payload.roomHostClientId());
        } else if (RoomPermissions.canDeleteRoom(newPerms)) {
            manager.setHostClientId(affectedId);
        }
        if (isLocal) {
            manager.setLocalPermissions(newPerms);
            if (lostSpeakPermission(oldPerms, newPerms)) {
                enforceAdminMicRevoke();
            }
            bindPermissionsToUI();
        }
    }

    /**
     * Hard-stops capture hardware and locks the mic toggle after {@code PERM_SPEAK} is revoked.
     */
    private void enforceAdminMicRevoke() {
        if (canvasSceneRoot != null) {
            ToastNotification.show(canvasSceneRoot,
                    "An Admin has revoked your microphone access.",
                    "lobby-status-disconnected");
        }
        if (networkClient != null) {
            AudioEngine audio = networkClient.getAudioEngine();
            audio.setMicMuted(true);
            networkClient.sendVoiceState(true);
        }
        if (micToggleBtn != null) {
            micToggleBtn.setDisable(true);
        }
    }

    /**
     * Mutes local mic when room media resumes to prevent YouTube ↔ VoIP feedback loops.
     */
    private void enforcePlaybackEchoGuard() {
        if (networkClient == null) {
            return;
        }
        AudioEngine audio = networkClient.getAudioEngine();
        audio.setMicMuted(true);
        networkClient.sendVoiceState(true);
        Participant local = networkClient.getParticipantManager().get(networkClient.getClientId());
        if (local != null) {
            local.setMuted(true);
        }
        if (canvasSceneRoot != null) {
            ToastNotification.show(canvasSceneRoot,
                    "Mic muted to prevent audio feedback during playback.");
        }
    }

    private void handleMediaStateUpdate(MessageCodec.MediaStatePayload state) {
        if (state == null) {
            return;
        }
        String newState = state.state() != null ? state.state().strip().toUpperCase() : "";
        if ("STOP".equals(newState)) {
            teardownYoutubePlayer();
            if (canvasSceneRoot != null) {
                ToastNotification.show(canvasSceneRoot, "Watch Party ended by Admin.");
            }
            bindPermissionsToUI();
            return;
        }
        if ("PAUSED".equals(lastMediaState) && "PLAYING".equals(newState)) {
            enforcePlaybackEchoGuard();
        }
        lastMediaState = newState;

        String videoId = state.videoId() != null ? state.videoId() : "";
        boolean videoIdChanged = false;
        if (videoId.isBlank()) {
            if (!lastActiveRoomVideoId.isBlank()) {
                lastActiveRoomVideoId = "";
                videoIdChanged = true;
            }
            if (videoIdChanged) {
                bindPermissionsToUI();
            }
            return;
        }
        boolean newActiveVideo = !videoId.equals(lastActiveRoomVideoId);
        ensureYoutubePlayer();
        if (youtubePlayer != null) {
            if (newActiveVideo) {
                lastActiveRoomVideoId = videoId;
                youtubePlayer.setVisible(true);
                youtubePlayer.toFront();
                bindPermissionsToUI();
            }
            youtubePlayer.applyServerState(state);
        }
    }

    private void toggleWatchPartyUI() {
        ensureYoutubePlayer();
        if (youtubePlayer == null) {
            return;
        }
        boolean wasVisible = youtubePlayer.isVisible();
        youtubePlayer.setVisible(!wasVisible);
        if (wasVisible) {
            youtubePlayer.pauseLocalPlayback();
        }
    }

    private void ensureYoutubePlayer() {
        if (spatialOverlayLayer == null) {
            return;
        }
        if (youtubePlayer == null) {
            youtubePlayer = new YoutubePlayerNode(networkClient, networkClient.getParticipantManager());
            youtubePlayer.setVisible(false);
            spatialOverlayLayer.getChildren().add(youtubePlayer);
            youtubePlayer.toFront();
            centerYoutubePlayerOnce();
        }
    }

    /**
     * Centers the PiP once via translate (not layout bind) so later drags are preserved on resize.
     */
    private void centerYoutubePlayerOnce() {
        if (youtubePlayer == null || spatialOverlayLayer == null) {
            return;
        }
        if (youtubeCenterWidthWeakListener != null) {
            spatialOverlayLayer.widthProperty().removeListener(youtubeCenterWidthWeakListener);
            youtubeCenterWidthListener = null;
            youtubeCenterWidthWeakListener = null;
        }
        double w = spatialOverlayLayer.getWidth();
        double h = spatialOverlayLayer.getHeight();
        if (w > 0 && h > 0) {
            youtubePlayer.setTranslateX(Math.max(0, (w - youtubePlayer.getPrefWidth()) / 2.0));
            youtubePlayer.setTranslateY(Math.max(0, (h - youtubePlayer.getPrefHeight()) / 2.0));
            return;
        }
        youtubeCenterWidthListener = (obs, oldVal, newVal) -> {
            double width = spatialOverlayLayer.getWidth();
            double height = spatialOverlayLayer.getHeight();
            if (width <= 0 || height <= 0 || youtubePlayer == null) {
                return;
            }
            youtubePlayer.setTranslateX(Math.max(0, (width - youtubePlayer.getPrefWidth()) / 2.0));
            youtubePlayer.setTranslateY(Math.max(0, (height - youtubePlayer.getPrefHeight()) / 2.0));
            if (youtubeCenterWidthWeakListener != null) {
                spatialOverlayLayer.widthProperty().removeListener(youtubeCenterWidthWeakListener);
            }
        };
        youtubeCenterWidthWeakListener = new WeakChangeListener<>(youtubeCenterWidthListener);
        spatialOverlayLayer.widthProperty().addListener(youtubeCenterWidthWeakListener);
    }

    private void teardownYoutubePlayer() {
        lastMediaState = null;
        lastActiveRoomVideoId = "";
        if (spatialOverlayLayer != null && youtubeCenterWidthWeakListener != null) {
            spatialOverlayLayer.widthProperty().removeListener(youtubeCenterWidthWeakListener);
        }
        youtubeCenterWidthListener = null;
        youtubeCenterWidthWeakListener = null;
        if (youtubePlayer != null) {
            youtubePlayer.dispose();
        }
        if (spatialOverlayLayer != null && youtubePlayer != null) {
            spatialOverlayLayer.getChildren().remove(youtubePlayer);
        }
        youtubePlayer = null;
    }

    /**
     * Applies {@link ParticipantManager#getLocalPermissions()} to canvas chrome. FX thread only.
     */
    private void bindPermissionsToUI() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::bindPermissionsToUI);
            return;
        }
        int perms = networkClient != null
                ? networkClient.getParticipantManager().getLocalPermissions()
                : RoomPermissions.SPECTATOR;
        boolean canDraw = RoomPermissions.canDraw(perms);
        boolean canSpeak = RoomPermissions.canSpeak(perms);
        boolean canDelete = RoomPermissions.canDeleteRoom(perms);
        boolean canManage = RoomPermissions.canManageUsers(perms);
        boolean canManageRoom = RoomPermissions.canManageRoom(perms);
        boolean canManageMedia = RoomPermissions.canManageMedia(perms);
        boolean hasActiveRoomVideo = !lastActiveRoomVideoId.isBlank();
        boolean showWatchParty = canManageMedia || hasActiveRoomVideo;

        if (drawingToolbar != null) {
            drawingToolbar.setDisable(!canDraw);
        }
        if (workspacePropertiesBar != null) {
            workspacePropertiesBar.setDisable(!canDraw);
        }
        if (canvasToolDock != null) {
            for (Node child : canvasToolDock.getChildren()) {
                child.setDisable(!canDraw);
            }
        }
        if (watchPartyBtn != null) {
            watchPartyBtn.setVisible(showWatchParty);
            watchPartyBtn.setManaged(showWatchParty);
            watchPartyBtn.setDisable(false);
        }
        if (btnDeleteRoom != null) {
            btnDeleteRoom.setVisible(canDelete);
            btnDeleteRoom.setManaged(canDelete);
        }
        if (micToggleBtn != null) {
            micToggleBtn.setDisable(!canSpeak);
        }
        if (collaborationRoster != null) {
            collaborationRoster.setModerationEnabled(canManage);
            collaborationRoster.setBoardLockToggleVisible(canManageRoom);
        }
        bindBoardCreationLockToNewBoardCard();
    }

    private void bindBoardCreationLockToNewBoardCard() {
        if (newBoardSwitcherCard == null || networkClient == null) {
            return;
        }
        ParticipantManager manager = networkClient.getParticipantManager();
        RoomState roomState = networkClient.getRoomState();
        BooleanBinding blocked = Bindings.and(
                roomState.boardCreationLockedProperty(),
                Bindings.not(Bindings.createBooleanBinding(
                        () -> RoomPermissions.canManageRoom(manager.getLocalPermissions()),
                        manager.localPermissionsProperty())));
        newBoardSwitcherCard.disableProperty().bind(blocked);
        if (collaborationRoster != null) {
            collaborationRoster.syncBoardLockCheckbox(roomState.isBoardCreationLocked());
        }
    }

    private void confirmDeleteCurrentRoom() {
        if (networkClient == null || !networkClient.isRunning()) {
            return;
        }
        String rid = roomId != null ? roomId.strip() : "";
        if (rid.isBlank()) {
            rid = networkClient.getActiveRoomId();
        }
        if (rid == null || rid.isBlank()) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Room");
        confirm.setHeaderText("Delete room '" + rid + "'?");
        confirm.setContentText(
                "This will permanently delete all boards and drawings. Everyone will be removed.");
        confirm.initModality(Modality.APPLICATION_MODAL);
        if (primaryStage != null) {
            confirm.initOwner(primaryStage);
        }
        Optional<ButtonType> response = confirm.showAndWait();
        if (response.isPresent() && response.get() == ButtonType.OK) {
            networkClient.sendDeleteRoom(rid);
        }
    }

    private void confirmDeleteBoard(String boardId) {
        if (networkClient == null || !networkClient.isRunning()) {
            return;
        }
        String bid = boardId != null ? boardId.strip() : "";
        if (bid.isBlank() || bid.equals(MessageCodec.DEFAULT_INITIAL_BOARD_ID)) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete board");
        confirm.setHeaderText("Delete board '" + bid + "'?");
        confirm.setContentText(
                "This permanently removes the board and its drawings for everyone in the room.");
        confirm.initModality(Modality.APPLICATION_MODAL);
        if (primaryStage != null) {
            confirm.initOwner(primaryStage);
        }
        Optional<ButtonType> response = confirm.showAndWait();
        if (response.isPresent() && response.get() == ButtonType.OK) {
            networkClient.sendDeleteBoard(bid);
        }
    }

    private void confirmKickParticipant(String targetClientId, String displayName) {
        if (networkClient == null || !networkClient.isRunning()) {
            return;
        }
        String label = displayName != null && !displayName.isBlank() ? displayName : targetClientId;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Remove participant");
        confirm.setHeaderText("Remove '" + label + "' from this room?");
        confirm.setContentText("They will be disconnected immediately.");
        confirm.initModality(Modality.APPLICATION_MODAL);
        if (primaryStage != null) {
            confirm.initOwner(primaryStage);
        }
        Optional<ButtonType> response = confirm.showAndWait();
        if (response.isPresent() && response.get() == ButtonType.OK) {
            networkClient.sendModerationKick(targetClientId, "removed by moderator");
        }
    }

    private void revokeSpeakParticipant(String targetClientId, String displayName) {
        if (networkClient == null || !networkClient.isRunning()) {
            return;
        }
        String label = displayName != null && !displayName.isBlank() ? displayName : targetClientId;
        networkClient.sendModerationRevokeSpeak(targetClientId, "microphone revoked by moderator");
        if (canvasSceneRoot != null) {
            ToastNotification.show(canvasSceneRoot, "Revoked microphone for " + label + ".");
        }
    }

    private void grantSpeakParticipant(Participant p) {
        if (networkClient == null || !networkClient.isRunning() || p == null) {
            return;
        }
        String targetClientId = p.getClientId();
        String displayName = p.getName();
        String label = displayName != null && !displayName.isBlank() ? displayName : targetClientId;
        networkClient.sendModerationGrantSpeak(targetClientId, "microphone granted by moderator");
        if (canvasSceneRoot != null) {
            ToastNotification.show(canvasSceneRoot, "Granted microphone for " + label + ".");
        }
    }

    private void onSessionRevoked(String reason) {
        if (!sessionRevokedOverlayShown.compareAndSet(false, true)) {
            return;
        }
        teardownYoutubePlayer();
        if (remoteCursorManager != null) {
            remoteCursorManager.stop();
        }
        if (networkClient != null) {
            networkClient.getAudioEngine().close();
        }
        if (canvasSceneRoot == null) {
            finishSessionRevokedReturn();
            return;
        }
        if (isBoardSwitcherShowing()) {
            hideBoardSwitcher();
        }
        removeSessionRevokedOverlay();

        Label title = new Label("Session Revoked");
        title.getStyleClass().add("session-revoked-title");
        title.setWrapText(true);
        title.setMaxWidth(480);

        Label subtitle = new Label("You have been removed from this room by an administrator.");
        subtitle.getStyleClass().add("session-revoked-subtitle");
        subtitle.setWrapText(true);
        subtitle.setMaxWidth(480);
        VBox.setMargin(subtitle, new Insets(0, 0, 16, 0));

        VBox card = new VBox(12, title, subtitle);
        card.setAlignment(Pos.CENTER);
        card.getStyleClass().add("session-revoked-card");
        card.setMaxWidth(480);

        if (reason != null && !reason.isBlank()) {
            Label reasonLine = new Label(reason);
            reasonLine.getStyleClass().addAll("session-revoked-reason", "lobby-status-muted");
            reasonLine.setWrapText(true);
            reasonLine.setMaxWidth(480);
            card.getChildren().add(reasonLine);
        }

        Button returnBtn = new Button("Return to Lobby");
        returnBtn.getStyleClass().add("session-revoked-return-btn");
        returnBtn.setDefaultButton(true);
        returnBtn.setMnemonicParsing(false);
        returnBtn.setOnAction(e -> finishSessionRevokedReturn());
        VBox.setMargin(returnBtn, new Insets(8, 0, 0, 0));
        card.getChildren().add(returnBtn);

        sessionRevokedOverlay = new StackPane(card);
        sessionRevokedOverlay.getStyleClass().add("session-revoked-overlay");
        sessionRevokedOverlay.setPickOnBounds(true);
        sessionRevokedOverlay.setStyle("-fx-background-color: transparent;");
        StackPane.setAlignment(card, Pos.CENTER);

        if (workspaceContentLayer != null) {
            workspaceContentLayer.setEffect(sessionRevokedWorkspaceBlur);
        }
        canvasSceneRoot.getChildren().add(sessionRevokedOverlay);
        sessionRevokedOverlay.toFront();
    }

    private void removeSessionRevokedOverlay() {
        if (workspaceContentLayer != null) {
            workspaceContentLayer.setEffect(null);
        }
        if (sessionRevokedOverlay != null && canvasSceneRoot != null) {
            canvasSceneRoot.getChildren().remove(sessionRevokedOverlay);
        }
        sessionRevokedOverlay = null;
    }

    private void finishSessionRevokedReturn() {
        removeSessionRevokedOverlay();
        sessionRevokedOverlayShown.set(false);
        leaveCanvasRoom(false);
        if (networkClient != null) {
            networkClient.reinitializeAudioEngine();
            micToggleHudWired = false;
            wireMicToggleHud(networkClient.getAudioEngine());
            rewireLocalParticipantMicHud(networkClient.getAudioEngine());
            networkClient.getParticipantManager().setLocalPermissions(RoomPermissions.SPECTATOR);
            bindPermissionsToUI();
            networkClient.resumeAfterSessionRevoked();
        }
    }

    /**
     * Lobby-only destructive action: confirms then sends {@link MessageType#DELETE_ROOM}.
     */
    private void confirmDeleteRoomFromLobby(String roomId, Label roomNameLabel, Button joinBtn, Button deleteBtn) {
        if (networkClient == null || !networkClient.isRunning()) {
            return;
        }
        String rid = roomId != null ? roomId.strip() : "";
        if (rid.isBlank()) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Room");
        confirm.setHeaderText("Are you sure you want to delete '" + rid + "'?");
        confirm.setContentText(
                "This will permanently delete all boards and drawings. Active users will be kicked out.");
        confirm.initModality(Modality.APPLICATION_MODAL);
        if (primaryStage != null) {
            confirm.initOwner(primaryStage);
        }
        Optional<ButtonType> response = confirm.showAndWait();
        if (response.isPresent() && response.get() == ButtonType.OK) {
            networkClient.sendDeleteRoom(rid);
            applyLobbyRoomDeletingState(rid, roomNameLabel, joinBtn, deleteBtn);
        }
    }

    /**
     * Optimistically locks a lobby room row after delete confirmation and request dispatch.
     * Package-private for UI logic tests ({@code WhiteboardAppOptimisticUITest}).
     */
    static void applyLobbyRoomDeletingState(String roomId, Label roomNameLabel, Button joinBtn, Button deleteBtn) {
        String rid = roomId != null ? roomId.strip() : "";
        if (!rid.isBlank() && roomNameLabel != null) {
            roomNameLabel.setText(rid + " (Deleting...)");
            roomNameLabel.setOpacity(0.5);
        }
        if (joinBtn != null) {
            joinBtn.setDisable(true);
        }
        if (deleteBtn != null) {
            deleteBtn.setDisable(true);
        }
    }

    private void cancelLobbyJoinWatchdog() {
        if (lobbyJoinWatchdog != null) {
            lobbyJoinWatchdog.stop();
            lobbyJoinWatchdog = null;
        }
    }

    /**
     * If no {@code SNAPSHOT} arrives, replace the stuck “Joining…” line with a concrete hint.
     */
    private void scheduleLobbyJoinWatchdog() {
        cancelLobbyJoinWatchdog();
        lobbyJoinWatchdog = new PauseTransition(Duration.seconds(12));
        lobbyJoinWatchdog.setOnFinished(e -> {
            lobbyJoinWatchdog = null;
            if (primaryStage == null || lobbyScene == null || lobbyStatusLabel == null) {
                return;
            }
            if (primaryStage.getScene() != lobbyScene) {
                return;
            }
            String t = lobbyStatusLabel.getText();
            if (t == null || !t.startsWith("Joining room")) {
                return;
            }
            showToast(
                    "Join timed out. Run WhiteboardServer on " + networkHost + ":" + networkPort
                            + " (same project), then try Create Room again.",
                    "lobby-status-disconnected");
        });
        lobbyJoinWatchdog.play();
    }

    private void refreshLobbyRooms(List<RoomInfo> rooms) {
        if (lobbyRoomList == null) {
            return;
        }
        lobbyRoomList.getChildren().clear();
        boolean empty = rooms == null || rooms.isEmpty();
        if (lobbyEmptyStateLabel != null) {
            lobbyEmptyStateLabel.setVisible(empty);
            lobbyEmptyStateLabel.setManaged(empty);
        }
        if (rooms == null) {
            return;
        }
        for (RoomInfo info : rooms) {
            FlowPane card = new FlowPane(10, 10);
            card.setOrientation(Orientation.HORIZONTAL);
            card.setAlignment(Pos.CENTER_LEFT);
            card.getStyleClass().add("room-card");
            card.prefWrapLengthProperty().bind(Bindings.createDoubleBinding(
                    () -> Math.max(180, lobbyRoomList.getWidth() - 16),
                    lobbyRoomList.widthProperty()));

            VBox textCol = new VBox(4);
            Label idLab = new Label(info.roomId());
            idLab.getStyleClass().add("lobby-room-title");
            idLab.setWrapText(true);
            idLab.maxWidthProperty().bind(Bindings.createDoubleBinding(
                    () -> Math.max(60, card.getWidth() - 8),
                    card.widthProperty()));
            Label countLab = new Label(
                    info.userCount() + (info.userCount() == 1 ? " user connected" : " users connected"));
            countLab.getStyleClass().add("lobby-meta");
            countLab.setWrapText(true);
            countLab.maxWidthProperty().bind(Bindings.createDoubleBinding(
                    () -> Math.max(60, card.getWidth() - 8),
                    card.widthProperty()));
            textCol.getChildren().addAll(idLab, countLab);

            Button joinBtn = new Button("Join");
            joinBtn.getStyleClass().add("tool-button");
            String rid = info.roomId();
            joinBtn.setOnAction(e -> joinRoomFromLobby(rid));

            Button deleteBtn = new Button("Delete");
            deleteBtn.getStyleClass().add("danger-btn");
            deleteBtn.setDisable(networkClient == null || !networkClient.isRunning());
            deleteBtn.setVisible(false);
            deleteBtn.setManaged(false);
            deleteBtn.setOnAction(e -> confirmDeleteRoomFromLobby(rid, idLab, joinBtn, deleteBtn));

            FlowPane actions = new FlowPane(10, 10, joinBtn, deleteBtn);
            actions.setOrientation(Orientation.HORIZONTAL);
            actions.setAlignment(Pos.CENTER_RIGHT);

            card.getChildren().addAll(textCol, actions);
            lobbyRoomList.getChildren().add(card);
        }
    }

    private void clearLocalCanvasState() {
        dismissActiveTextField();
        shapes.clear();
        shapeSpatialGrid.clear();
        undoHistory.clear();
        transientShapes.clear();
        for (VBox ghost : ghostTextNodes.values()) {
            cursorPane.getChildren().remove(ghost);
        }
        ghostTextNodes.clear();
        activeShapeId = null;
        lastSendTime = 0;
        isDragging = false;
        freehandPoints.clear();
        double rw = remoteTransientCanvas.getWidth();
        double rh = remoteTransientCanvas.getHeight();
        double tw = transientCanvas.getWidth();
        double th = transientCanvas.getHeight();
        remoteTransientGc.clearRect(0, 0, Math.max(rw, 1), Math.max(rh, 1));
        transientGc.clearRect(0, 0, Math.max(tw, 1), Math.max(th, 1));
        redrawBaseCanvas(shapes.values());
    }

    private void leaveCanvasRoom(boolean sendLeaveRoomToServer) {
        flushEraserBaseRedraw();
        stopPerformanceHud();
        removeSessionRevokedOverlay();
        sessionRevokedOverlayShown.set(false);
        cancelLobbyJoinWatchdog();
        hideBoardSwitcher();
        teardownYoutubePlayer();
        boardSnapshots.clear();
        unwireCanvasMouseEvents();
        if (remoteCursorManager != null) {
            remoteCursorManager.clear();
        }
        if (networkClient != null && sendLeaveRoomToServer) {
            networkClient.sendLeaveRoom();
        }
        if (networkClient != null) {
            networkClient.getParticipantManager().setLocalPermissions(RoomPermissions.SPECTATOR);
        }
        clearLocalCanvasState();
        bindPermissionsToUI();
        if (primaryStage != null && lobbyScene != null) {
            primaryStage.setScene(lobbyScene);
            primaryStage.setTitle("DistriSync – Lobby");
        }
        setStatus("⬤ In lobby", FG_MUTED);
    }

    private void handlePeerJoined(String clientId, String authorName) {
        if (networkClient == null || clientId == null || clientId.isBlank()) {
            return;
        }
        if (clientId.equals(networkClient.getClientId())) {
            return;
        }
        if (networkClient.getActiveRoomId() == null || networkClient.getActiveRoomId().isBlank()) {
            return;
        }
        String displayName = authorName != null && !authorName.isBlank() ? authorName : clientId;
        networkClient.getParticipantManager().putParticipant(clientId, displayName);
        if (primaryStage != null && primaryStage.getScene() == canvasScene && canvasSceneRoot != null) {
            ToastNotification.show(canvasSceneRoot, displayName + " joined the room.");
        }
    }

    private void handlePeerLeft(String clientId) {
        if (networkClient == null || clientId == null || clientId.isBlank()) {
            return;
        }
        if (clientId.equals(networkClient.getClientId())) {
            return;
        }
        ParticipantManager manager = networkClient.getParticipantManager();
        Participant peer = manager.get(clientId);
        String displayName = peer != null && !peer.getName().isBlank() ? peer.getName() : clientId;
        if (primaryStage != null && primaryStage.getScene() == canvasScene && canvasSceneRoot != null) {
            ToastNotification.show(canvasSceneRoot, displayName + " left the room.");
        }
        manager.remove(clientId);
        if (remoteCursorManager != null) {
            remoteCursorManager.removePeer(clientId);
        }
    }

    /**
     * Server destroyed the canvas room; return to lobby without sending {@code LEAVE_ROOM}
     * (session was already cleared on the wire).
     */
    private void onRoomDeletedByServer() {
        leaveCanvasRoom(false);
        Alert info = new Alert(Alert.AlertType.INFORMATION);
        info.setTitle("Room deleted");
        info.setHeaderText(null);
        info.setContentText("The room was deleted by an administrator.");
        if (primaryStage != null) {
            info.initOwner(primaryStage);
        }
        info.show();
    }

    // =========================================================================
    // Canvas scene input — shift constraints & mic toggle HUD
    // =========================================================================

    /**
     * Binds {@link #micToggleBtn} to {@link AudioEngine} mute/speaking properties (once per session).
     */
    /** Re-binds local participant mute state after {@link NetworkClient#reinitializeAudioEngine()}. */
    private void rewireLocalParticipantMicHud(AudioEngine audio) {
        if (networkClient == null || audio == null) {
            return;
        }
        Participant local = networkClient.getParticipantManager().get(networkClient.getClientId());
        if (local != null) {
            local.setMuted(audio.isMicMuted());
            audio.isMicMutedProperty().addListener((obs, was, muted) ->
                    local.setMuted(Boolean.TRUE.equals(muted)));
        }
    }

    private void recreateRemoteCursorManager() {
        if (cursorPane == null) {
            return;
        }
        if (remoteCursorManager != null) {
            remoteCursorManager.stop();
        }
        remoteCursorManager = new RemoteCursorManager(cursorPane, clientId);
    }

    private void wireParticipantHud(ParticipantManager manager) {
        if (collaborationRoster == null || manager == null || networkClient == null) {
            return;
        }
        collaborationRoster.bindTo(manager);
        collaborationRoster.setLocalClientId(networkClient.getClientId());
        collaborationRoster.setKickHandler(this::confirmKickParticipant);
        collaborationRoster.setRevokeSpeakHandler(this::revokeSpeakParticipant);
        collaborationRoster.setGrantSpeakHandler(this::grantSpeakParticipant);
        collaborationRoster.setBoardLockToggleHandler(networkClient::sendBoardLockState);
        networkClient.getRoomState().boardCreationLockedProperty().addListener((obs, was, locked) ->
                collaborationRoster.syncBoardLockCheckbox(Boolean.TRUE.equals(locked)));
        String displayName = networkClient.getAuthorName();
        if (displayName == null || displayName.isBlank()) {
            displayName = "You";
        }
        manager.putParticipant(networkClient.getClientId(), displayName);
        manager.setCurrentBoardId(networkClient.getClientId(), networkClient.getCurrentBoardId());
        if (RoomPermissions.canManageRoom(manager.getLocalPermissions())
                || RoomPermissions.canDeleteRoom(manager.getLocalPermissions())) {
            manager.setHostClientId(networkClient.getClientId());
        }
        bindBoardCreationLockToNewBoardCard();
        AudioEngine audio = networkClient.getAudioEngine();
        Participant local = manager.get(networkClient.getClientId());
        if (local != null && audio != null) {
            local.setMuted(audio.isMicMuted());
            audio.isMicMutedProperty().addListener((obs, was, muted) ->
                    local.setMuted(Boolean.TRUE.equals(muted)));
        }
    }

    private void wireMicToggleHud(AudioEngine audio) {
        if (micToggleBtn == null || audio == null || micToggleHudWired) {
            return;
        }
        micToggleHudWired = true;

        Runnable refresh = () -> {
            boolean muted = audio.isMicMuted();
            boolean speaking = audio.isSpeaking();
            Runnable apply = () -> applyMicToggleVisual(muted, speaking);
            if (Platform.isFxApplicationThread()) {
                apply.run();
            } else {
                Platform.runLater(apply);
            }
        };

        audio.isMicMutedProperty().addListener((obs, was, now) -> refresh.run());
        audio.isSpeakingProperty().addListener((obs, was, now) -> refresh.run());
        refresh.run();
    }

    private void applyMicToggleVisual(boolean muted, boolean speaking) {
        if (micToggleBtn == null) {
            return;
        }
        micToggleBtn.getStyleClass().removeAll("muted", "speaking");
        if (muted) {
            micToggleBtn.setText(MIC_OFF_GLYPH);
            micToggleBtn.getStyleClass().add("muted");
        } else {
            micToggleBtn.setText(MIC_ON_GLYPH);
            if (speaking) {
                micToggleBtn.getStyleClass().add("speaking");
            }
        }
        updateMicToggleTooltip(muted, speaking);
    }

    private void updateMicToggleTooltip(boolean muted, boolean speaking) {
        if (micToggleTooltip == null) {
            return;
        }
        if (muted) {
            micToggleTooltip.setText("Unmute mic");
        } else if (speaking) {
            micToggleTooltip.setText("Mute mic (live)");
        } else {
            micToggleTooltip.setText("Mute mic");
        }
    }

    private void attachShiftKeyTracking(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.SHIFT) {
                shiftKeyHeld = true;
            }
        });
        scene.addEventFilter(KeyEvent.KEY_RELEASED, e -> {
            if (e.getCode() != KeyCode.SHIFT) {
                return;
            }
            shiftKeyHeld = false;
            if (!isDragging || transientGc == null || transientCanvas == null || !isRubberBandDragTool()) {
                return;
            }
            transientGc.clearRect(0, 0, transientCanvas.getWidth(), transientCanvas.getHeight());
            drawRubberBandPreview(false);
        });
    }

    private boolean isRubberBandDragTool() {
        return switch (activeTool) {
            case LINE, CIRCLE, RECTANGLE, ELLIPSE, ARROW -> true;
            default -> false;
        };
    }

    private boolean isShiftHeld(MouseEvent e) {
        return shiftKeyHeld || e.isShiftDown();
    }

    private static double segmentLength(ShapeMathUtils.LineSegment seg) {
        return Math.hypot(seg.x2() - seg.x1(), seg.y2() - seg.y1());
    }

    private void strokeArrow(
            GraphicsContext gc, double x1, double y1, double x2, double y2, double strokeWidth, Color color) {
        gc.setStroke(color);
        gc.setLineWidth(strokeWidth);
        gc.setLineDashes((double[]) null);
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setLineJoin(StrokeLineJoin.ROUND);
        gc.strokeLine(x1, y1, x2, y2);
        double angle = Math.atan2(y2 - y1, x2 - x1);
        double headLen = 8.0 + strokeWidth * 1.5;
        double wing = Math.PI / 6.0;
        gc.strokeLine(x2, y2,
                x2 - headLen * Math.cos(angle - wing), y2 - headLen * Math.sin(angle - wing));
        gc.strokeLine(x2, y2,
                x2 - headLen * Math.cos(angle + wing), y2 - headLen * Math.sin(angle + wing));
    }

    /** Subtle 1.02× scale on pointer enter (150 ms) for the telemetry HUD strip. */
    private void wireTelemetryHoverScale() {
        if (telemetryHudRoot != null) {
            telemetryHudRoot.addEventHandler(MouseEvent.MOUSE_ENTERED, e -> {
                if (telemetryHoverScaleTransition != null) {
                    telemetryHoverScaleTransition.stop();
                }
                double sx = telemetryHudRoot.getScaleX();
                double sy = telemetryHudRoot.getScaleY();
                ScaleTransition up = new ScaleTransition(Duration.millis(150), telemetryHudRoot);
                telemetryHoverScaleTransition = up;
                up.setFromX(sx);
                up.setFromY(sy);
                up.setToX(1.02);
                up.setToY(1.02);
                up.play();
            });
            telemetryHudRoot.addEventHandler(MouseEvent.MOUSE_EXITED, e -> {
                if (telemetryHoverScaleTransition != null) {
                    telemetryHoverScaleTransition.stop();
                }
                double sx = telemetryHudRoot.getScaleX();
                double sy = telemetryHudRoot.getScaleY();
                ScaleTransition down = new ScaleTransition(Duration.millis(150), telemetryHudRoot);
                telemetryHoverScaleTransition = down;
                down.setFromX(sx);
                down.setFromY(sy);
                down.setToX(1.0);
                down.setToY(1.0);
                down.setOnFinished(ev2 -> {
                    if (telemetryHoverScaleTransition == down) {
                        telemetryHoverScaleTransition = null;
                    }
                });
                down.play();
            });
        }
    }

    // =========================================================================
    // Mouse events
    // =========================================================================

    /**
     * Wires all drawing interactions to the {@link StackPane} so events are
     * captured regardless of which canvas layer is topmost.
     *
     * <p>Rubber-band shapes (LINE, CIRCLE) draw their preview to
     * {@code transientCanvas} on every drag update and clear it on release.
     * Freehand / eraser paths accumulate points incrementally on
     * {@code transientCanvas}; on release the path is committed as a series
     * of {@link Line} mutations.
     */
    /**
     * Clears drawing / hover handlers on the canvas stack while in the lobby so
     * transient input cannot fire against an invisible scene; handlers are
     * re-attached from {@link #onSnapshotReceived} when the user re-enters a room.
     */
    private void unwireCanvasMouseEvents() {
        if (canvasContainer == null) {
            return;
        }
        canvasContainer.setOnMousePressed(null);
        canvasContainer.setOnMouseDragged(null);
        canvasContainer.setOnMouseReleased(null);
        canvasContainer.setOnMouseMoved(null);
        canvasContainer.setOnMouseEntered(null);
        canvasContainer.setOnMouseExited(null);
    }

    private void wireMouseEvents(StackPane target) {
        target.setOnMousePressed(e -> {
            ownerTooltip.hide();

            // Text tool: place a floating TextField at the click point and bail out
            // before the drag-drawing machinery initialises.
            if (activeTool == Tool.TEXT) {
                placeTextField(e.getX(), e.getY());
                return;
            }

            if (activeTool == Tool.ERASER) {
                erasedInGesture.clear();
                isDragging = true;
                performEraserAt(e.getX(), e.getY());
                return;
            }

            dragStartX   = e.getX();
            dragStartY   = e.getY();
            dragCurrentX = e.getX();
            dragCurrentY = e.getY();
            isDragging   = true;

            if (activeTool == Tool.FREEHAND) {
                freehandPoints.clear();
                freehandPoints.add(new double[]{e.getX(), e.getY()});
                lastFreehandX = e.getX();
                lastFreehandY = e.getY();
            }

            // Assign a fresh shape identity for this gesture and broadcast SHAPE_START.
            activeShapeId = UUID.randomUUID();
            if (networkClient != null) {
                try {
                    networkClient.sendShapeStart(
                            activeShapeId,
                            activeTool.name(),
                            globalCanvasContext.activeColorHex(),
                            globalCanvasContext.getActiveStrokeWidth(),
                            e.getX(), e.getY());
                } catch (Exception ex) {
                    log.warn("sendShapeStart failed: {}", ex.getMessage());
                }
            }
        });

        target.setOnMouseDragged(e -> {
            dragCurrentX = e.getX();
            dragCurrentY = e.getY();

            switch (activeTool) {
                case LINE, CIRCLE, RECTANGLE, ELLIPSE, ARROW -> {
                    transientGc.clearRect(0, 0,
                            transientCanvas.getWidth(), transientCanvas.getHeight());
                    drawRubberBandPreview(isShiftHeld(e));
                }
                case FREEHAND -> {
                    double dx = e.getX() - lastFreehandX;
                    double dy = e.getY() - lastFreehandY;
                    if (Math.sqrt(dx * dx + dy * dy) >= MIN_FREEHAND_STEP) {
                        drawFreehandSegment(lastFreehandX, lastFreehandY,
                                            e.getX(),       e.getY());
                        freehandPoints.add(new double[]{e.getX(), e.getY()});
                        lastFreehandX = e.getX();
                        lastFreehandY = e.getY();
                    }
                }
                case ERASER -> {
                    lastEraserX = e.getX();
                    lastEraserY = e.getY();
                    ensureEraserTimerRunning();
                }
            }

            // Throttled SHAPE_UPDATE: send at most once every 40 ms to keep bandwidth low.
            long now = System.currentTimeMillis();
            if (activeTool != Tool.ERASER
                    && networkClient != null && activeShapeId != null && now - lastSendTime > 40) {
                lastSendTime = now;
                try {
                    networkClient.sendShapeUpdate(activeShapeId, e.getX(), e.getY());
                } catch (Exception ex) {
                    log.warn("sendShapeUpdate failed: {}", ex.getMessage());
                }
            }

            notifyUdpMove(e.getX(), e.getY());
        });

        target.setOnMouseReleased(e -> {
            if (!isDragging) return;
            isDragging = false;

            if (activeTool == Tool.ERASER) {
                lastEraserX = -1;
                lastEraserY = -1;
                flushEraserBaseRedraw();
                erasedInGesture.clear();
                return;
            }

            // Always clear the local transient layer before committing.
            transientGc.clearRect(0, 0,
                    transientCanvas.getWidth(), transientCanvas.getHeight());

            // Tell remote peers to flush their transient preview for this gesture.
            if (networkClient != null && activeShapeId != null) {
                try {
                    networkClient.sendShapeCommit(activeShapeId);
                } catch (Exception ex) {
                    log.warn("sendShapeCommit failed: {}", ex.getMessage());
                }
            }

            commitShape(e.getX(), e.getY(), isShiftHeld(e));
        });

        target.setOnMouseMoved(e -> {
            notifyUdpMove(e.getX(), e.getY());
            // Hover-ownership: find topmost shape under the cursor and show
            // a tooltip attributing it to its author.
            if (!isDragging) {
                Shape hit = findShapeAt(e.getX(), e.getY());
                if (hit != null && !hit.authorName().isBlank()) {
                    ownerTooltip.setText("Drawn by: " + hit.authorName());
                    ownerTooltip.show(target, e.getScreenX() + 14, e.getScreenY() + 14);
                } else {
                    ownerTooltip.hide();
                }
            }
        });

        target.setOnMouseExited(e -> ownerTooltip.hide());
    }

    // ── Transient-canvas drawing ──────────────────────────────────────────────

    /** Draws a dashed rubber-band ghost for vector drag tools. */
    private void drawRubberBandPreview(boolean shiftHeld) {
        double stroke = globalCanvasContext.getActiveStrokeWidth();
        Color  color  = globalCanvasContext.getActiveColor();

        transientGc.save();
        transientGc.setGlobalAlpha(0.60);
        transientGc.setStroke(color);
        transientGc.setLineWidth(stroke);
        transientGc.setLineDashes(7, 5);

        switch (activeTool) {
            case LINE ->
                transientGc.strokeLine(dragStartX, dragStartY, dragCurrentX, dragCurrentY);
            case CIRCLE -> {
                double dx = dragCurrentX - dragStartX;
                double dy = dragCurrentY - dragStartY;
                if (shiftHeld) {
                    double[] d = ShapeMathUtils.constrainAxisDelta(dx, dy, true);
                    dx = d[0];
                    dy = d[1];
                }
                double r = Math.sqrt(dx * dx + dy * dy);
                transientGc.strokeOval(dragStartX - r, dragStartY - r, r * 2, r * 2);
            }
            case RECTANGLE -> {
                ShapeMathUtils.NormalizedRect rect = ShapeMathUtils.rectangleFromDrag(
                        dragStartX, dragStartY, dragCurrentX, dragCurrentY, shiftHeld);
                transientGc.strokeRect(rect.x(), rect.y(), rect.width(), rect.height());
            }
            case ELLIPSE -> {
                ShapeMathUtils.NormalizedRect bounds = ShapeMathUtils.ellipseFromDrag(
                        dragStartX, dragStartY, dragCurrentX, dragCurrentY, shiftHeld);
                transientGc.strokeOval(bounds.x(), bounds.y(), bounds.width(), bounds.height());
            }
            case ARROW -> {
                ShapeMathUtils.LineSegment seg = ShapeMathUtils.arrowFromDrag(
                        dragStartX, dragStartY, dragCurrentX, dragCurrentY, shiftHeld);
                strokeArrow(transientGc, seg.x1(), seg.y1(), seg.x2(), seg.y2(), stroke, color);
            }
            default -> { /* not used */ }
        }

        transientGc.restore();
    }

    /** Draws one incremental segment of a freehand / eraser path. */
    private void drawFreehandSegment(double x1, double y1, double x2, double y2) {
        transientGc.save();

        transientGc.setStroke(globalCanvasContext.getActiveColor());
        transientGc.setLineWidth(globalCanvasContext.getActiveStrokeWidth());
        transientGc.setLineCap(StrokeLineCap.ROUND);

        transientGc.setLineDashes((double[]) null);
        transientGc.setLineJoin(StrokeLineJoin.ROUND);
        transientGc.strokeLine(x1, y1, x2, y2);

        transientGc.restore();
    }

    /**
     * Draws a single new segment for a remote FREEHAND or ERASER gesture directly
     * onto {@code remoteTransientGc} <em>without</em> clearing the canvas first.
     *
     * <p>Bridging consecutive 40 ms network ticks this way produces a continuous,
     * gap-free stroke instead of the dotted-line artefact seen when the layer is
     * cleared and fully redrawn on every update.  Because the accumulated
     * {@link TransientShapeEntry#points} list is still maintained, a subsequent
     * call to {@link #renderTransient()} (e.g. on LINE update from another peer)
     * will reconstruct the full path correctly.
     *
     * @param entry the in-progress gesture whose latest segment should be appended
     * @param x1    X of the previous tip (before this update)
     * @param y1    Y of the previous tip (before this update)
     * @param x2    X of the new tip (after this update)
     * @param y2    Y of the new tip (after this update)
     */
    private void drawRemoteSegmentIncremental(TransientShapeEntry entry,
                                              double x1, double y1, double x2, double y2) {
        remoteTransientGc.save();
        remoteTransientGc.setGlobalAlpha(0.75);
        remoteTransientGc.setLineDashes((double[]) null);
        remoteTransientGc.setLineJoin(StrokeLineJoin.ROUND);
        if ("ERASER".equals(entry.tool)) {
            remoteTransientGc.setStroke(Color.WHITE);
            remoteTransientGc.setLineWidth(entry.strokeWidth);
            remoteTransientGc.setLineCap(StrokeLineCap.SQUARE);
        } else {
            remoteTransientGc.setStroke(parseColor(entry.color));
            remoteTransientGc.setLineWidth(entry.strokeWidth);
            remoteTransientGc.setLineCap(StrokeLineCap.ROUND);
        }
        remoteTransientGc.strokeLine(x1, y1, x2, y2);
        remoteTransientGc.restore();
    }

    /**
     * Builds the floating JavaFX overlay node used to display a remote peer's
     * live-typing session on {@code cursorPane}.  The node is positioned and
     * updated in-place by the {@link CanvasUpdateListener#onTextUpdate} callback.
     *
     * <p>Visual structure (top→bottom):
     * <pre>
     *  ┌───┬──────────────┐  ← HBox badge
     *  │ █ │  AuthorName  │    (colored stripe + dark name pill)
     *  └───┴──────────────┘
     *  ┌────────────────────┐ ← Label textPreview
     *  │  current text▏     │   (accent-colored, dark translucent bg)
     *  └────────────────────┘
     * </pre>
     *
     * @param authorName display name of the typing peer
     * @param clientId   session identifier — hashed to produce a deterministic accent colour
     * @return a mouse-transparent {@link VBox} ready to be added to {@code cursorPane}
     */
    private VBox buildGhostTextNode(String authorName, String clientId) {
        // Derive a stable, visually distinct hue from clientId so the same peer
        // always gets the same colour across all observers.
        int    hash   = clientId.hashCode();
        double hue    = (hash & 0x7FFF_FFFF) % 360.0;
        Color  accent = Color.hsb(hue, 0.65, 0.95);
        String hex    = toHexString(accent);

        // Left accent stripe (mirrors the drawing-cursor badge style in renderTransient)
        Region stripe = new Region();
        stripe.setPrefWidth(3);
        stripe.setPrefHeight(16);
        stripe.getStyleClass().add("ghost-author-stripe");
        stripe.setBackground(new Background(new BackgroundFill(accent, new CornerRadii(2, 0, 0, 2, false), Insets.EMPTY)));

        // Author name pill
        Label nameTag = new Label(authorName.isBlank() ? "typing…" : authorName);
        nameTag.getStyleClass().add("ghost-author-tag");

        HBox badge = new HBox(0, stripe, nameTag);
        badge.setAlignment(Pos.CENTER_LEFT);

        // Live-text preview: shows the in-progress content with a thin block cursor
        Label textPreview = new Label("\u258f");   // initial cursor glyph
        textPreview.getStyleClass().add("ghost-text-preview");
        textPreview.setTextFill(accent);

        VBox box = new VBox(0, badge, textPreview);
        box.setMouseTransparent(true);
        return box;
    }

    // ── Shape commit ─────────────────────────────────────────────────────────

    /**
     * Builds the final {@link Shape}(s) from the completed drag gesture,
     * stores them locally, and enqueues them for network broadcast.
     */
    private void commitShape(double endX, double endY, boolean shiftHeld) {
        switch (activeTool) {
            case LINE -> {
                double dx = endX - dragStartX;
                double dy = endY - dragStartY;
                if (Math.sqrt(dx * dx + dy * dy) < MIN_DRAG_DIST) return;
                addAndSend(shapeFactory.line(dragStartX, dragStartY, endX, endY, authorName, clientId));
            }
            case CIRCLE -> {
                double[] d = ShapeMathUtils.constrainAxisDelta(endX - dragStartX, endY - dragStartY, shiftHeld);
                double r = Math.sqrt(d[0] * d[0] + d[1] * d[1]);
                if (r < MIN_DRAG_DIST) return;
                addAndSend(shapeFactory.circle(dragStartX, dragStartY, Math.max(1.0, r),
                        authorName, clientId));
            }
            case RECTANGLE -> {
                ShapeMathUtils.NormalizedRect rect = ShapeMathUtils.rectangleFromDrag(
                        dragStartX, dragStartY, endX, endY, shiftHeld);
                if (rect.width() < MIN_DRAG_DIST || rect.height() < MIN_DRAG_DIST) return;
                addAndSend(shapeFactory.rectangle(
                        rect.x(), rect.y(), rect.width(), rect.height(), authorName, clientId));
            }
            case ELLIPSE -> {
                ShapeMathUtils.NormalizedRect bounds = ShapeMathUtils.ellipseFromDrag(
                        dragStartX, dragStartY, endX, endY, shiftHeld);
                if (bounds.width() < MIN_DRAG_DIST || bounds.height() < MIN_DRAG_DIST) return;
                addAndSend(shapeFactory.ellipse(
                        bounds.x(), bounds.y(), bounds.width(), bounds.height(), authorName, clientId));
            }
            case ARROW -> {
                ShapeMathUtils.LineSegment seg = ShapeMathUtils.arrowFromDrag(
                        dragStartX, dragStartY, endX, endY, shiftHeld);
                if (segmentLength(seg) < MIN_DRAG_DIST) return;
                addAndSend(shapeFactory.arrow(seg.x1(), seg.y1(), seg.x2(), seg.y2(), authorName, clientId));
            }
            case FREEHAND -> {
                if (freehandPoints.size() < 2) return;
                List<Shape> segments = new ArrayList<>(freehandPoints.size());
                for (int i = 1; i < freehandPoints.size(); i++) {
                    double[] p0 = freehandPoints.get(i - 1);
                    double[] p1 = freehandPoints.get(i);
                    segments.add(shapeFactory.line(p0[0], p0[1], p1[0], p1[1], authorName, clientId));
                }
                freehandPoints.clear();
                addAndSendBatch(segments);
            }
            case ERASER -> { /* object eraser — deletions handled in performEraserAt */ }
        }
    }

    /**
     * Eraser pass: spatial hit-test against committed shapes; queue and apply
     * {@code SHAPE_DELETE} (via {@link NetworkClient#sendUndoRequest(UUID)}).
     */
    private void indexShape(Shape shape) {
        shapeSpatialGrid.insert(shape);
    }

    private void unindexShape(Shape shape) {
        shapeSpatialGrid.remove(shape);
    }

    private void reindexAllShapes() {
        shapeSpatialGrid.clear();
        shapes.values().forEach(shapeSpatialGrid::insert);
    }

    private void performEraserAt(double x, double y) {
        if (eraserIntersection == null) {
            return;
        }
        double eraserSize = globalCanvasContext.getActiveStrokeWidth();
        EraserType eraserType = globalCanvasContext.getActiveEraserType();
        eraserIntersection.eraseAt(x, y, eraserSize, eraserType).ifPresent(shapeId -> {
            if (!erasedInGesture.add(shapeId)) {
                return;
            }
            Shape removed = shapes.remove(shapeId);
            if (removed != null) {
                unindexShape(removed);
            }
            undoHistory.remove(shapeId);
            if (networkClient != null) {
                try {
                    networkClient.sendUndoRequest(shapeId);
                } catch (Exception ex) {
                    log.warn("sendUndoRequest (eraser) failed: {}", ex.getMessage());
                }
            }
            scheduleEraserBaseRedraw();
        });
    }

    /**
     * Keeps {@link #eraserRedrawTimer} running so drag samples are hit-tested at ~30 Hz.
     * Must be called on the FX Application Thread.
     */
    private void ensureEraserTimerRunning() {
        if (!eraserRedrawTimerActive) {
            eraserRedrawLastNanos = 0;
            eraserRedrawTimerActive = true;
            eraserRedrawTimer.start();
        }
    }

    /**
     * Schedules a throttled full repaint of the base canvas after eraser deletions.
     * Must be called on the FX Application Thread.
     */
    private void scheduleEraserBaseRedraw() {
        needsBaseRedraw = true;
        ensureEraserTimerRunning();
    }

    /**
     * Stops the eraser repaint timer and paints immediately if a redraw was pending.
     * Must be called on the FX Application Thread.
     */
    private void flushEraserBaseRedraw() {
        eraserRedrawTimer.stop();
        eraserRedrawTimerActive = false;
        eraserRedrawLastNanos = 0;
        if (needsBaseRedraw) {
            needsBaseRedraw = false;
            redrawBaseCanvas(shapes.values());
        }
    }

    private void addAndSend(Shape shape) {
        shapes.put(shape.objectId(), shape);
        indexShape(shape);
        undoHistory.addLast(shape.objectId());
        // Server excludes the sender from MUTATION broadcast; paint locally in O(1).
        paintShapeOnBaseCanvas(shape);
        if (networkClient != null) {
            try {
                networkClient.sendMutation(shape);
            } catch (Exception ex) {
                log.warn("sendMutation failed: {}", ex.getMessage());
            }
        }
    }

    /**
     * Commits multiple shapes locally (incremental paint) and sends them as one or
     * few {@code MUTATION_BATCH} frames (freehand stroke segments).
     */
    private void addAndSendBatch(List<Shape> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        for (Shape shape : batch) {
            shapes.put(shape.objectId(), shape);
            indexShape(shape);
            undoHistory.addLast(shape.objectId());
            paintShapeOnBaseCanvas(shape);
        }
        if (networkClient != null) {
            try {
                networkClient.sendMutationBatch(batch);
            } catch (Exception ex) {
                log.warn("sendMutationBatch failed: {}", ex.getMessage());
            }
        }
    }

    /**
     * Removes all shapes owned by this client, notifies the server so all peers
     * receive a {@code CLEAR_USER_SHAPES} broadcast, and empties the local undo
     * history.  Only this user's shapes are affected; other users' shapes remain.
     */
    private void clearBoard() {
        // Send the scoped clear to the server first; peers receive the broadcast
        // and remove only this user's shapes via onUserShapesCleared().
        if (networkClient != null) {
            try {
                networkClient.sendClearUserShapes();
            } catch (Exception ex) {
                log.warn("sendClearUserShapes failed: {}", ex.getMessage());
            }
        }
        // Offline fallback: remove only this user's shapes so the canvas reflects
        // the scoped semantics even without a server echo.
        shapes.values().removeIf(s -> s.clientId().equals(clientId));
        undoHistory.clear();
        redrawBaseCanvas(shapes.values());
    }

    /**
     * Removes the most-recently-committed local shape from the canvas and
     * sends an {@code UNDO_REQUEST} to the server so peers also remove it.
     * If no undoable shape exists this is a silent no-op.
     */
    private void undoLastShape() {
        UUID lastId = undoHistory.pollLast();
        if (lastId == null) {
            log.debug("Nothing to undo");
            return;
        }
        Shape removed = shapes.remove(lastId);
        if (removed != null) {
            unindexShape(removed);
        }
        redrawBaseCanvas(shapes.values());
        if (networkClient != null) {
            try {
                networkClient.sendUndoRequest(lastId);
            } catch (Exception ex) {
                log.warn("sendUndoRequest failed: {}", ex.getMessage());
            }
        }
        log.debug("Undo applied locally shapeId={}", lastId);
    }

    private void notifyUdpMove(double x, double y) {
        if (networkClient != null) {
            networkClient.sendCursorSync(x, y);
        }
    }

    // ── Text Tool ─────────────────────────────────────────────────────────────

    /**
     * Places a floating {@link TextField} on {@code controlPane} at the given
     * canvas coordinates.  The field uses the currently selected color and a
     * matching font size.
     *
     * <ul>
     *   <li><b>Enter</b> — commits the text as a {@link TextNode}, sends it to
     *       the network, and removes the field.</li>
     *   <li><b>Escape</b> — cancels without committing.</li>
     * </ul>
     *
     * Any previously active TextField is silently dismissed before the new one
     * is added.
     */
    private void placeTextField(double x, double y) {
        dismissActiveTextField();

        // Stable identity for this typing session — used as the TEXT_UPDATE objectId
        // and also sent via SHAPE_COMMIT when the user confirms or cancels, so remote
        // peers know to dismiss the ghost overlay.
        activeTextId = UUID.randomUUID();
        final UUID textId = activeTextId;

        String hexColor = globalCanvasContext.activeColorHex();
        int    fontSize = TextNode.create(hexColor, 0, 0, "x").fontSize(); // canonical size (14)
        // Canvas baseline: fillText() renders from the bottom of the glyph, so
        // we offset by fontSize to align the top of the text with the click point.
        final double textBase = y + fontSize;

        TextField textField = new TextField();
        textField.setLayoutX(x);
        textField.setLayoutY(y);
        textField.setPrefWidth(220);
        textField.getStyleClass().add("canvas-text-input");
        textField.setBackground(new Background(
                new BackgroundFill(Color.rgb(30, 30, 46, 0.55), CornerRadii.EMPTY, Insets.EMPTY)));
        textField.setBorder(new Border(new BorderStroke(
                Paint.valueOf(hexColor), BorderStrokeStyle.SOLID, CornerRadii.EMPTY, new BorderWidths(0, 0, 2, 0))));
        textField.setFont(Font.font("System", fontSize));
        textField.setPadding(new Insets(2, 4, 2, 4));

        controlPane.getChildren().add(textField);
        controlPane.setMouseTransparent(false);
        textField.requestFocus();

        // Throttled TEXT_UPDATE: broadcast at most once every 50 ms so remote peers
        // see live keystrokes almost instantly without flooding the network.
        final long[] lastTextSend = {0L};
        textField.textProperty().addListener((obs, oldVal, newVal) -> {
            long now = System.currentTimeMillis();
            if (networkClient != null && now - lastTextSend[0] > 50) {
                lastTextSend[0] = now;
                try {
                    networkClient.sendTextUpdate(textId, x, textBase, newVal);
                } catch (Exception ex) {
                    log.warn("sendTextUpdate failed: {}", ex.getMessage());
                }
            }
        });

        textField.setOnKeyPressed(keyEvent -> {
            switch (keyEvent.getCode()) {
                case ENTER -> {
                    String text = textField.getText().strip();
                    if (!text.isEmpty()) {
                        // fillText() baseline is at y; offset by fontSize so the
                        // rendered text aligns with the top of the TextField.
                        TextNode node = shapeFactory.text(x, textBase, text, authorName, clientId);
                        addAndSend(node);
                    }
                    // Signal remote peers to dismiss the ghost overlay for this session.
                    if (networkClient != null) {
                        try { networkClient.sendShapeCommit(textId); }
                        catch (Exception ex) { log.warn("sendShapeCommit(text) failed: {}", ex.getMessage()); }
                    }
                    controlPane.getChildren().remove(textField);
                    controlPane.setMouseTransparent(true);
                    activeTextId = null;
                    keyEvent.consume();
                }
                case ESCAPE -> {
                    // Cancel: still dismiss the ghost so peers don't see a stale overlay.
                    if (networkClient != null) {
                        try { networkClient.sendShapeCommit(textId); }
                        catch (Exception ex) { log.warn("sendShapeCommit(text cancel) failed: {}", ex.getMessage()); }
                    }
                    controlPane.getChildren().remove(textField);
                    controlPane.setMouseTransparent(true);
                    activeTextId = null;
                    keyEvent.consume();
                }
                default -> { /* let the TextField handle normal typing */ }
            }
        });
    }

    /**
     * Removes any active floating {@link TextField} from {@code controlPane},
     * restores the pane to its default mouse-transparent state, and sends a
     * {@code SHAPE_COMMIT} for the current text session so remote peers dismiss
     * their ghost overlay.  Safe to call when no field is present.
     */
    private void dismissActiveTextField() {
        if (controlPane != null && !controlPane.getChildren().isEmpty()) {
            if (activeTextId != null && networkClient != null) {
                final UUID id = activeTextId;
                try { networkClient.sendShapeCommit(id); }
                catch (Exception ex) { log.warn("sendShapeCommit(dismiss) failed: {}", ex.getMessage()); }
            }
            activeTextId = null;
            controlPane.getChildren().clear();
            controlPane.setMouseTransparent(true);
        }
    }

    // =========================================================================
    // Networking
    // =========================================================================

    private void initNetworking() {
        Parameters   params = getParameters();
        List<String> raw    = params.getRaw();
        String host = raw.size() > 0 ? raw.get(0) : DEFAULT_HOST;
        int    port = raw.size() > 1 ? parseInt(raw.get(1), DEFAULT_PORT) : DEFAULT_PORT;
        networkHost = host;
        networkPort = port;

        networkClient = new NetworkClient(host, port, authorName, clientId);
        networkClient.getParticipantManager().putParticipant(
                networkClient.getClientId(),
                networkClient.getAuthorName());
        wirePerformanceHud(networkClient);
        wireMicToggleHud(networkClient.getAudioEngine());
        wireParticipantHud(networkClient.getParticipantManager());
        networkClient.addLobbyListener(rooms ->
                Platform.runLater(() -> {
                    lastLobbyRooms = rooms != null ? List.copyOf(rooms) : List.of();
                    refreshLobbyRooms(lastLobbyRooms);
                }));
        networkClient.addRoomDeletedListener(() ->
                Platform.runLater(this::onRoomDeletedByServer));
        networkClient.addBoardDeletedListener(deletedBoardId ->
                Platform.runLater(() -> {
                    if (deletedBoardId != null && !deletedBoardId.isBlank()) {
                        boardSnapshots.remove(deletedBoardId);
                    }
                    if (isBoardSwitcherShowing()) {
                        refreshSwitcherBoardGrid();
                    }
                    if (canvasSceneRoot != null && deletedBoardId != null) {
                        ToastNotification.show(canvasSceneRoot,
                                "Board " + deletedBoardId + " was deleted by an Admin.",
                                "lobby-status-disconnected");
                    }
                }));
        networkClient.addSessionRevokedListener(reason ->
                Platform.runLater(() -> onSessionRevoked(reason)));
        networkClient.addRoleUpdateListener(payload ->
                Platform.runLater(() -> applyRoleUpdate(payload)));
        networkClient.addMediaStateListener(state ->
                Platform.runLater(() -> handleMediaStateUpdate(state)));
        networkClient.addRoomMembershipListener(new RoomMembershipListener() {
            @Override
            public void onPeerJoined(String clientId, String authorName) {
                Platform.runLater(() -> handlePeerJoined(clientId, authorName));
            }

            @Override
            public void onPeerLeft(String clientId) {
                Platform.runLater(() -> handlePeerLeft(clientId));
            }
        });
        networkClient.addBoardPresenceListener((clientId, boardId) ->
                Platform.runLater(() -> {
                    if (networkClient != null) {
                        networkClient.getParticipantManager().setCurrentBoardId(clientId, boardId);
                    }
                }));

        // Callbacks arrive on distrisync-read; marshal to FX thread before touching state
        networkClient.addListener(new CanvasUpdateListener() {

            @Override
            public void onSnapshotReceived(List<Shape> incoming) {
                Platform.runLater(() -> {
                    cancelLobbyJoinWatchdog();
                    if (canvasContainer != null) {
                        wireMouseEvents(canvasContainer);
                    }
                    if (networkClient != null) {
                        networkClient.getParticipantManager().setCurrentBoardId(
                                networkClient.getClientId(),
                                networkClient.getCurrentBoardId());
                    }
                    List<Shape> list = incoming != null ? incoming : List.of();
                    roomId = networkClient != null ? networkClient.getActiveRoomId() : "";
                    Scene cur = primaryStage != null ? primaryStage.getScene() : null;
                    // Switch scenes before redraw: while the lobby is showing, the canvas may
                    // still be size 0 / not laid out; drawing first could throw and block the switch.
                    if (primaryStage != null && canvasScene != null && cur != null
                            && cur != canvasScene && (loginScene == null || cur != loginScene)) {
                        recreateRemoteCursorManager();
                        primaryStage.setScene(canvasScene);
                        bindPermissionsToUI();
                        primaryStage.setTitle("DistriSync – " + authorName + "  [" + roomId + "]");
                        controlPane.toFront();
                        setStatus("⬤ Connected", GREEN);
                        startPerformanceHud();
                        Platform.runLater(WhiteboardApp.this::updateToolsDrawerClipAndHostWidth);
                    }

                    if (canvasContainer == null) {
                        shapes.clear();
                        for (Shape s : list) {
                            if (s != null) {
                                shapes.put(s.objectId(), s);
                            }
                        }
                        reindexAllShapes();
                        try {
                            redrawBaseCanvas(shapes.values());
                        } catch (RuntimeException ex) {
                            log.error("redrawBaseCanvas after SNAPSHOT failed", ex);
                        }
                        log.info("Snapshot applied — {} shape(s) on canvas", shapes.size());
                        return;
                    }

                    stopSnapshotHydrationAndResetOpacity();
                    final long hydrationToken = ++snapshotHydrationToken;
                    FadeTransition fadeOut = new FadeTransition(Duration.millis(150), canvasContainer);
                    fadeOut.setFromValue(1.0);
                    fadeOut.setToValue(0.0);
                    fadeOut.setOnFinished(ev -> {
                        if (hydrationToken != snapshotHydrationToken) {
                            return;
                        }
                        shapes.clear();
                        for (Shape s : list) {
                            if (s != null) {
                                shapes.put(s.objectId(), s);
                            }
                        }
                        reindexAllShapes();
                        try {
                            redrawBaseCanvas(shapes.values());
                        } catch (RuntimeException ex) {
                            log.error("redrawBaseCanvas after SNAPSHOT failed", ex);
                        }
                        log.info("Snapshot applied — {} shape(s) on canvas", shapes.size());
                        FadeTransition fadeIn = new FadeTransition(Duration.millis(150), canvasContainer);
                        fadeIn.setFromValue(0.0);
                        fadeIn.setToValue(1.0);
                        fadeIn.setOnFinished(e2 -> {
                            if (hydrationToken != snapshotHydrationToken) {
                                return;
                            }
                            if (snapshotHydrationAnimation == fadeIn) {
                                snapshotHydrationAnimation = null;
                            }
                        });
                        snapshotHydrationAnimation = fadeIn;
                        fadeIn.play();
                    });
                    snapshotHydrationAnimation = fadeOut;
                    fadeOut.play();
                });
            }

            @Override
            public void onMutationReceived(Shape shape) {
                Platform.runLater(() -> {
                    boolean isAddition = !shapes.containsKey(shape.objectId());
                    Shape previous = shapes.put(shape.objectId(), shape);
                    if (previous != null) {
                        unindexShape(previous);
                    }
                    indexShape(shape);
                    if (isAddition) {
                        paintShapeOnBaseCanvas(shape);
                    } else {
                        redrawBaseCanvas(shapes.values());
                    }
                });
            }

            // ── Live-drawing callbacks ─────────────────────────────────────────

            @Override
            public void onShapeStart(UUID shapeId, String tool, String color,
                                     double strokeWidth, double x, double y, String authorName) {
                Platform.runLater(() -> {
                    transientShapes.put(shapeId,
                            new TransientShapeEntry(shapeId, tool, color, strokeWidth, x, y, authorName));
                    renderTransient();
                });
            }

            @Override
            public void onShapeUpdate(UUID shapeId, double x, double y) {
                Platform.runLater(() -> {
                    TransientShapeEntry entry = transientShapes.get(shapeId);
                    if (entry != null) {
                        if ("FREEHAND".equals(entry.tool) || "ERASER".equals(entry.tool)) {
                            // Incremental path: draw only the new segment directly onto
                            // remoteTransientGc without clearing the canvas.  This bridges
                            // the ~40 ms gaps between network ticks and produces a smooth,
                            // gap-free stroke instead of the previous dotted-line artefact.
                            double prevX = entry.lastX;
                            double prevY = entry.lastY;
                            entry.update(x, y);
                            drawRemoteSegmentIncremental(entry, prevX, prevY, x, y);
                        } else {
                            // Rubber-band tools (LINE / CIRCLE) must clear and redraw the
                            // entire transient layer because the shape bounding box changes
                            // on every tick.
                            entry.update(x, y);
                            renderTransient();
                        }
                    }
                });
            }

            @Override
            public void onShapeCommit(UUID shapeId) {
                Platform.runLater(() -> {
                    transientShapes.remove(shapeId);

                    // shapeId doubles as the objectId for live-text sessions: remove
                    // any ghost overlay that was tracking this typing session.
                    VBox ghostNode = ghostTextNodes.remove(shapeId);
                    if (ghostNode != null) {
                        cursorPane.getChildren().remove(ghostNode);
                    }

                    renderTransient();
                    // Committed segments arrive via onMutationReceived and paint incrementally.
                });
            }

            @Override
            public void onUserShapesCleared(String targetClientId) {
                Platform.runLater(() -> {
                    shapes.values().removeIf(s -> s.clientId().equals(targetClientId));
                    reindexAllShapes();
                    // Only discard this client's undo history when the clear is for us.
                    if (targetClientId.equals(clientId)) {
                        undoHistory.clear();
                    }
                    // redrawBaseCanvas fills white then repaints all remaining shapes;
                    // no need for a separate clearRect/fillRect call.
                    redrawBaseCanvas(shapes.values());
                    renderTransient();
                    log.info("User shapes cleared — clientId='{}'", targetClientId);
                });
            }

            @Override
            public void onShapeDeleted(UUID shapeId) {
                Platform.runLater(() -> {
                    Shape removed = shapes.remove(shapeId);
                    if (removed != null) {
                        unindexShape(removed);
                    }
                    redrawBaseCanvas(shapes.values());
                    log.debug("Shape deleted by remote peer shapeId={}", shapeId);
                });
            }

            @Override
            public void onTextUpdate(UUID objectId, String clientId, String authorName,
                                     double x, double y, String currentText) {
                Platform.runLater(() -> {
                    VBox ghost = ghostTextNodes.get(objectId);
                    if (ghost == null) {
                        ghost = buildGhostTextNode(authorName, clientId);
                        ghostTextNodes.put(objectId, ghost);
                        cursorPane.getChildren().add(ghost);
                    }
                    // Position the overlay just above the text insertion point.
                    ghost.setLayoutX(x);
                    ghost.setLayoutY(y - 34);
                    // Update the live-text preview label (second child of the VBox).
                    Label textPreview = (Label) ghost.getChildren().get(1);
                    textPreview.setText(currentText.isEmpty() ? "\u258f" : currentText + "\u258f");
                });
            }
        });

        // Connect asynchronously — UI is never blocked
        Thread connectThread = new Thread(() -> {
            try {
                networkClient.connect();
                Platform.runLater(() -> setLobbyStickyStatus("Connected to " + host + ":" + port, "lobby-status-connected"));
            } catch (IOException e) {
                log.warn("Could not reach server at {}:{} — offline mode active", host, port);
                Platform.runLater(() -> {
                    setLobbyStickyStatus("Offline — start the server or check host/port", "lobby-status-disconnected");
                    setStatus("⬤ Offline", RED);
                });
            }
        }, "distrisync-connect");
        connectThread.setDaemon(true);
        connectThread.start();

        remoteCursorManager = new RemoteCursorManager(cursorPane, clientId);
        networkClient.addCursorSyncListener((peerId, peerName, cx, cy) ->
                Platform.runLater(() -> {
                    if (remoteCursorManager != null) {
                        remoteCursorManager.updateTarget(peerId, peerName, cx, cy);
                    }
                }));
    }

    private void setStatus(String text, String colorHex) {
        statusLabel.setText(text);
        statusLabel.setTextFill(Color.web(colorHex));
    }

    private void wirePerformanceHud(NetworkClient client) {
        if (telemetryHudWired || performanceHud == null) {
            return;
        }
        telemetryHudWired = true;
    }

    private void startPerformanceHud() {
        stopPerformanceHud();
        performanceFps = 0;
        performanceFrameCount = 0;
        performanceFpsLastSecondNanos = 0;

        performanceFpsTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                performanceFrameCount++;
                if (performanceFpsLastSecondNanos == 0) {
                    performanceFpsLastSecondNanos = now;
                    return;
                }
                long elapsed = now - performanceFpsLastSecondNanos;
                if (elapsed >= 1_000_000_000L) {
                    performanceFps = (int) Math.round(
                            performanceFrameCount * 1_000_000_000.0 / elapsed);
                    performanceFrameCount = 0;
                    performanceFpsLastSecondNanos = now;
                }
            }
        };
        performanceFpsTimer.start();

        performanceHudTimeline = new Timeline(
                new KeyFrame(Duration.seconds(1), e -> updatePerformanceHudText()));
        performanceHudTimeline.setCycleCount(Timeline.INDEFINITE);
        performanceHudTimeline.play();
        updatePerformanceHudText();
    }

    private void stopPerformanceHud() {
        if (performanceFpsTimer != null) {
            performanceFpsTimer.stop();
            performanceFpsTimer = null;
        }
        if (performanceHudTimeline != null) {
            performanceHudTimeline.stop();
            performanceHudTimeline = null;
        }
    }

    private void updatePerformanceHudText() {
        if (performanceHud == null) {
            return;
        }
        long ms = networkClient != null ? networkClient.pingProperty().get() : -1L;
        String pingText = ms < 0L ? "—" : String.valueOf(ms);
        performanceHud.setText("Ping: " + pingText + "ms | FPS: " + performanceFps);
    }

    private boolean isViewportDrawable() {
        return CanvasViewportResizeHandler.isViewportDrawable(canvasContainer);
    }

    /** At least 1px so {@link javafx.scene.canvas.Canvas} never enters Prism with a null RTTexture. */
    private static javafx.beans.value.ObservableDoubleValue canvasDimensionBinding(
            javafx.beans.value.ObservableNumberValue source) {
        return Bindings.createDoubleBinding(
                () -> Math.max(source.doubleValue(), 1.0),
                source);
    }

    /**
     * Canonical base-canvas repaint.  Clears the canvas, fills the white
     * background, then draws every shape in causal (Lamport timestamp) order
     * so eraser strokes always paint over earlier shapes.
     *
     * <p>Must be called on the FX Application Thread.  Invoke via
     * {@link Platform#runLater} from any background callback — e.g. inside
     * {@code onSnapshotReceived}, LWW {@code onMutationReceived} updates,
     * {@code onShapeDeleted}, and {@code onUserShapesCleared} — to guarantee the
     * canvas reflects the latest committed state immediately.  New-shape
     * {@code onMutationReceived} additions use {@link #paintShapeOnBaseCanvas} instead.
     *
     * @param shapesToDraw the committed shapes to render; must not be {@code null}
     */
    private void redrawBaseCanvas(Collection<Shape> shapesToDraw) {
        if (canvasContainer == null || canvasContainer.getScene() == null || !isViewportDrawable()) {
            return;
        }
        double w = baseCanvas.getWidth();
        double h = baseCanvas.getHeight();

        baseGc.setFill(Color.WHITE);
        baseGc.fillRect(0, 0, w, h);

        shapesToDraw.stream()
                    .sorted(Comparator.comparingLong(Shape::timestamp))
                    .forEach(s -> drawShape(baseGc, s));
    }

    /**
     * Appends one committed shape onto {@code baseCanvas} without clearing the
     * layer.  Must be called on the FX Application Thread.
     */
    private void paintShapeOnBaseCanvas(Shape shape) {
        if (!isViewportDrawable()) {
            return;
        }
        drawShape(baseGc, shape);
    }

    /**
     * Clears and redraws {@code remoteTransientCanvas} with all currently
     * tracked in-progress shapes from remote peers.  Must be called on the
     * FX Application Thread.
     *
     * <p>This canvas sits between the base layer and the local transient layer,
     * so clearing it never disturbs the local rubber-band / freehand preview.
     */
    private void renderTransient() {
        if (canvasContainer == null || canvasContainer.getScene() == null || !isViewportDrawable()) {
            return;
        }
        double w = remoteTransientCanvas.getWidth();
        double h = remoteTransientCanvas.getHeight();
        remoteTransientGc.clearRect(0, 0, w, h);

        for (TransientShapeEntry entry : transientShapes.values()) {
            remoteTransientGc.save();
            remoteTransientGc.setGlobalAlpha(0.75);
            remoteTransientGc.setLineDashes((double[]) null);
            remoteTransientGc.setLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
            remoteTransientGc.setLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);

            switch (entry.tool) {
                case "LINE" -> {
                    remoteTransientGc.setStroke(parseColor(entry.color));
                    remoteTransientGc.setLineWidth(entry.strokeWidth);
                    remoteTransientGc.strokeLine(entry.startX, entry.startY,
                                                  entry.lastX,  entry.lastY);
                }
                case "CIRCLE" -> {
                    double dx = entry.lastX - entry.startX;
                    double dy = entry.lastY - entry.startY;
                    double r  = Math.sqrt(dx * dx + dy * dy);
                    remoteTransientGc.setStroke(parseColor(entry.color));
                    remoteTransientGc.setLineWidth(entry.strokeWidth);
                    remoteTransientGc.strokeOval(entry.startX - r, entry.startY - r, r * 2, r * 2);
                }
                case "RECTANGLE" -> {
                    ShapeMathUtils.NormalizedRect rect = ShapeMathUtils.rectangleFromDrag(
                            entry.startX, entry.startY, entry.lastX, entry.lastY, false);
                    remoteTransientGc.setStroke(parseColor(entry.color));
                    remoteTransientGc.setLineWidth(entry.strokeWidth);
                    remoteTransientGc.strokeRect(rect.x(), rect.y(), rect.width(), rect.height());
                }
                case "ELLIPSE" -> {
                    ShapeMathUtils.NormalizedRect bounds = ShapeMathUtils.ellipseFromDrag(
                            entry.startX, entry.startY, entry.lastX, entry.lastY, false);
                    remoteTransientGc.setStroke(parseColor(entry.color));
                    remoteTransientGc.setLineWidth(entry.strokeWidth);
                    remoteTransientGc.strokeOval(bounds.x(), bounds.y(), bounds.width(), bounds.height());
                }
                case "ARROW" -> {
                    ShapeMathUtils.LineSegment seg = ShapeMathUtils.arrowFromDrag(
                            entry.startX, entry.startY, entry.lastX, entry.lastY, false);
                    remoteTransientGc.setStroke(parseColor(entry.color));
                    remoteTransientGc.setLineWidth(entry.strokeWidth);
                    strokeArrow(remoteTransientGc, seg.x1(), seg.y1(), seg.x2(), seg.y2(),
                            entry.strokeWidth, parseColor(entry.color));
                }
                case "FREEHAND" -> {
                    remoteTransientGc.setStroke(parseColor(entry.color));
                    remoteTransientGc.setLineWidth(entry.strokeWidth);
                    List<double[]> pts = entry.points;
                    for (int i = 1; i < pts.size(); i++) {
                        double[] p0 = pts.get(i - 1);
                        double[] p1 = pts.get(i);
                        remoteTransientGc.strokeLine(p0[0], p0[1], p1[0], p1[1]);
                    }
                }
                case "ERASER" -> {
                    remoteTransientGc.setStroke(Color.WHITE);
                    remoteTransientGc.setLineWidth(entry.strokeWidth);
                    remoteTransientGc.setLineCap(javafx.scene.shape.StrokeLineCap.SQUARE);
                    List<double[]> pts = entry.points;
                    for (int i = 1; i < pts.size(); i++) {
                        double[] p0 = pts.get(i - 1);
                        double[] p1 = pts.get(i);
                        remoteTransientGc.strokeLine(p0[0], p0[1], p1[0], p1[1]);
                    }
                }
                default -> { /* unknown tool — skip */ }
            }

            // ── Figma-style author attribution label ──────────────────────────
            // Rendered at the tip of the in-progress shape so every observer
            // can see which peer is drawing at a glance.
            if (entry.authorName != null && !entry.authorName.isBlank()) {
                String label   = entry.authorName;
                double lx      = entry.lastX + 10;
                double ly      = entry.lastY - 10;
                double approxW = label.length() * 6.8;

                remoteTransientGc.save();
                remoteTransientGc.setGlobalAlpha(0.88);
                // Dark pill background
                remoteTransientGc.setFill(Color.color(0.08, 0.08, 0.12, 0.78));
                remoteTransientGc.fillRoundRect(lx - 3, ly - 13, approxW + 10, 17, 5, 5);
                // Coloured left accent stripe
                remoteTransientGc.setFill(parseColor(entry.color));
                remoteTransientGc.fillRoundRect(lx - 3, ly - 13, 3, 17, 2, 2);
                // Label text in white
                remoteTransientGc.setFont(Font.font("System", FontWeight.BOLD, 11));
                remoteTransientGc.setFill(Color.WHITE);
                remoteTransientGc.fillText(label, lx + 4, ly);
                remoteTransientGc.restore();
            }

            remoteTransientGc.restore();
        }
    }

    /**
     * Full repaint after the layered workspace changes size (JavaFX clears bitmap canvases on resize).
     * Replays committed shapes, remote transient previews, and any in-progress local gesture.
     */
    private void redrawAllLayersAfterViewportChange() {
        if (baseCanvas == null || baseGc == null) {
            return;
        }
        redrawBaseCanvas(shapes.values());
        renderTransient();
        redrawLocalTransientAfterViewportResize();
    }

    /** Rebuilds local rubber-band / freehand ink if a resize occurs mid-gesture. */
    private void redrawLocalTransientAfterViewportResize() {
        if (transientCanvas == null || transientGc == null || !isDragging || !isViewportDrawable()) {
            return;
        }
        double w = transientCanvas.getWidth();
        double h = transientCanvas.getHeight();
        transientGc.clearRect(0, 0, w, h);
        switch (activeTool) {
            case LINE, CIRCLE, RECTANGLE, ELLIPSE, ARROW -> drawRubberBandPreview(shiftKeyHeld);
            case FREEHAND -> {
                for (int i = 1; i < freehandPoints.size(); i++) {
                    double[] p0 = freehandPoints.get(i - 1);
                    double[] p1 = freehandPoints.get(i);
                    drawFreehandSegment(p0[0], p0[1], p1[0], p1[1]);
                }
            }
            default -> { /* TEXT uses controlPane */ }
        }
    }

    // =========================================================================
    // Shape rendering
    // =========================================================================

    private void drawShape(GraphicsContext gc, Shape shape) {
        gc.save();

        switch (shape) {
            case Line l -> {
                gc.setStroke(parseColor(l.color()));
                gc.setLineWidth(l.strokeWidth());
                gc.setLineDashes((double[]) null);
                gc.setLineCap(StrokeLineCap.ROUND);
                gc.setLineJoin(StrokeLineJoin.ROUND);
                gc.strokeLine(l.x1(), l.y1(), l.x2(), l.y2());
            }
            case Circle c -> {
                Color color = parseColor(c.color());
                double d    = c.radius() * 2;
                double ox   = c.x() - c.radius();
                double oy   = c.y() - c.radius();
                if (c.filled()) {
                    gc.setFill(color);
                    gc.fillOval(ox, oy, d, d);
                } else {
                    gc.setStroke(color);
                    gc.setLineWidth(c.strokeWidth());
                    gc.setLineDashes((double[]) null);
                    gc.strokeOval(ox, oy, d, d);
                }
            }
            case TextNode t -> {
                Font font = Font.font(
                    t.fontFamily(),
                    t.bold()   ? FontWeight.BOLD   : FontWeight.NORMAL,
                    t.italic() ? FontPosture.ITALIC : FontPosture.REGULAR,
                    t.fontSize()
                );
                gc.setFont(font);
                gc.setFill(parseColor(t.color()));
                gc.fillText(t.content(), t.x(), t.y());
            }
            case RectangleNode r -> {
                gc.setStroke(parseColor(r.color()));
                gc.setLineWidth(r.strokeWidth());
                gc.setLineDashes((double[]) null);
                gc.strokeRect(r.x(), r.y(), r.width(), r.height());
            }
            case EllipseNode e -> {
                gc.setStroke(parseColor(e.color()));
                gc.setLineWidth(e.strokeWidth());
                gc.setLineDashes((double[]) null);
                gc.strokeOval(e.x(), e.y(), e.width(), e.height());
            }
            case ArrowNode a ->
                strokeArrow(gc, a.x1(), a.y1(), a.x2(), a.y2(), a.strokeWidth(), parseColor(a.color()));
            case EraserPath ep -> {
                // Render as a white stroke with a square brush.
                // Because redrawBaseCanvas always fills white before painting shapes in
                // Lamport-timestamp order, this white path pixel-perfectly overwrites all
                // shapes with an earlier timestamp — functionally identical to BlendMode.ERASE
                // on a white-background canvas (JavaFX's BlendMode enum has no ERASE value).
                gc.setStroke(Color.WHITE);
                gc.setLineWidth(ep.strokeWidth());
                gc.setLineDashes((double[]) null);
                gc.setLineCap(StrokeLineCap.SQUARE);
                gc.setLineJoin(StrokeLineJoin.ROUND);
                double[] xs = ep.xs();
                double[] ys = ep.ys();
                for (int i = 1; i < xs.length; i++) {
                    gc.strokeLine(xs[i - 1], ys[i - 1], xs[i], ys[i]);
                }
            }
        }

        gc.restore();
    }

    // =========================================================================
    // Hit-testing (hover ownership)
    // =========================================================================

    /**
     * Returns the topmost committed {@link Shape} whose geometry intersects
     * the given canvas point, or {@code null} if none does.
     * "Topmost" is defined as the shape with the highest Lamport timestamp
     * (i.e. the most recently drawn).
     *
     * @param x canvas X coordinate
     * @param y canvas Y coordinate
     */
    private Shape findShapeAt(double x, double y) {
        if (activeTool == Tool.ERASER) {
            return shapes.values().stream()
                    .filter(s -> ShapeSpatialQuery.intersectsEraser(
                            x, y,
                            globalCanvasContext.getActiveStrokeWidth(),
                            globalCanvasContext.getActiveEraserType(),
                            s))
                    .max(Comparator.comparingLong(Shape::timestamp))
                    .orElse(null);
        }
        return shapes.values().stream()
                .filter(s -> ShapeSpatialQuery.intersectsEraser(x, y, 5.0, EraserType.CIRCLE, s))
                .max(Comparator.comparingLong(Shape::timestamp))
                .orElse(null);
    }

    // =========================================================================
    // Eraser tool cursor
    // =========================================================================

    private void setupEraserTool() {
        globalCanvasContext.activeStrokeWidthProperty().addListener((obs, oldW, newW) -> {
            if (activeTool == Tool.ERASER) {
                applyEraserCursorToCanvas();
            }
        });
        globalCanvasContext.activeEraserTypeProperty().addListener((obs, oldT, newT) -> {
            if (activeTool == Tool.ERASER) {
                applyEraserCursorToCanvas();
                syncEraserTypeToggleButtons();
            }
        });
    }

    private void syncEraserTypeToggleButtons() {
        if (eraserTypeCircleBtn == null || eraserTypeSquareBtn == null) {
            return;
        }
        EraserType type = globalCanvasContext.getActiveEraserType();
        eraserTypeCircleBtn.setSelected(type == EraserType.CIRCLE);
        eraserTypeSquareBtn.setSelected(type == EraserType.SQUARE);
    }

    private void applyEraserCursorToCanvas() {
        ImageCursor cursor = EraserCursorFactory.createImageCursor(
                globalCanvasContext.getActiveStrokeWidth(),
                globalCanvasContext.getActiveEraserType());
        if (canvasContainer != null) {
            canvasContainer.setCursor(cursor);
        }
        if (baseCanvas != null) {
            baseCanvas.setCursor(cursor);
        }
        if (transientCanvas != null) {
            transientCanvas.setCursor(cursor);
        }
        if (cursorPane != null) {
            cursorPane.setCursor(cursor);
        }
    }

    private void applyDrawingCursor(Cursor cursor) {
        if (canvasContainer != null) {
            canvasContainer.setCursor(cursor);
        }
        if (baseCanvas != null) {
            baseCanvas.setCursor(cursor);
        }
        if (transientCanvas != null) {
            transientCanvas.setCursor(cursor);
        }
        if (cursorPane != null) {
            cursorPane.setCursor(cursor);
        }
    }

    // =========================================================================
    // Shutdown
    // =========================================================================

    private void shutdown() {
        if (remoteCursorManager != null) {
            Platform.runLater(remoteCursorManager::stop);
        }
        if (networkClient != null) networkClient.close();
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    private Color parseColor(String css) {
        try {
            return Color.web(css);
        } catch (IllegalArgumentException e) {
            return Color.BLACK;
        }
    }

    private static String toHexString(Color c) {
        return String.format("#%02X%02X%02X",
            (int) Math.round(c.getRed()   * 255),
            (int) Math.round(c.getGreen() * 255),
            (int) Math.round(c.getBlue()  * 255));
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    public static void main(String[] args) {
        launch(args);
    }

    // =========================================================================
    // TransientShapeEntry — per-peer mutable in-progress drawing record
    // (FX Application Thread only — always accessed inside Platform.runLater)
    // =========================================================================

    /**
     * Holds the mutable state for a remote peer's in-progress drawing gesture.
     * Instances are created on {@code SHAPE_START} and removed on
     * {@code SHAPE_COMMIT}.  All reads and writes occur on the FX Application
     * Thread, so no synchronization is needed.
     */
    private static final class TransientShapeEntry {

        final UUID         shapeId;
        final String       tool;
        final String       color;
        final double       strokeWidth;
        final double       startX;
        final double       startY;
        /** Display name of the remote peer who owns this in-progress gesture. */
        final String       authorName;
        double             lastX;
        double             lastY;
        /** Accumulated points — only populated for FREEHAND / ERASER gestures. */
        final List<double[]> points = new ArrayList<>();

        TransientShapeEntry(UUID shapeId, String tool, String color,
                            double strokeWidth, double x, double y, String authorName) {
            this.shapeId     = shapeId;
            this.tool        = tool;
            this.color       = color;
            this.strokeWidth = strokeWidth;
            this.startX      = x;
            this.startY      = y;
            this.authorName  = authorName != null ? authorName : "";
            this.lastX       = x;
            this.lastY       = y;
            if ("FREEHAND".equals(tool) || "ERASER".equals(tool)) {
                points.add(new double[]{x, y});
            }
        }

        /** Updates the tip position; appends to the point list for path-based tools. */
        void update(double x, double y) {
            lastX = x;
            lastY = y;
            if ("FREEHAND".equals(tool) || "ERASER".equals(tool)) {
                points.add(new double[]{x, y});
            }
        }
    }
}
