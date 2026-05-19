package com.distrisync.client;

import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Renders remote multiplayer cursors with linear interpolation (LERP) on the JavaFX
 * Application Thread via a single {@link AnimationTimer}.
 */
public final class RemoteCursorManager {

    private static final double LERP_FACTOR = 0.3;
    private static final long STALE_TIMEOUT_MS = 2_000;
    private static final long FADE_DURATION_MS = 250;

    private final Pane cursorPane;
    private final String localClientId;
    private final Map<String, CursorTarget> targets = new HashMap<>();
    private final Map<String, Group> nodes = new HashMap<>();

    private final AnimationTimer animationTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            tickLerpAndStale();
        }
    };

    public RemoteCursorManager(Pane cursorPane, String localClientId) {
        this.cursorPane = Objects.requireNonNull(cursorPane, "cursorPane");
        this.localClientId = localClientId != null ? localClientId : "";
        animationTimer.start();
    }

    /**
     * Updates the target position for a remote peer. Must run on the FX Application Thread.
     */
    public void updateTarget(String clientId, String authorName, double x, double y) {
        if (clientId == null || clientId.isBlank() || clientId.equals(localClientId)) {
            return;
        }

        CursorTarget target = targets.get(clientId);
        if (target == null) {
            target = new CursorTarget(clientId, authorName);
            targets.put(clientId, target);
            Group node = buildCursorNode(clientId, authorName);
            nodes.put(clientId, node);
            cursorPane.getChildren().add(node);
        } else {
            target.authorName = authorName != null ? authorName : "";
        }

        target.targetX = x;
        target.targetY = y;
        target.lastUpdateTime = System.currentTimeMillis();

        Group node = nodes.get(clientId);
        if (node != null && target.fadingOut && target.activeFade != null) {
            target.activeFade.stop();
            target.activeFade = null;
            target.fadingOut = false;
            node.setOpacity(1.0);
        }
    }

    /**
     * Immediately removes one remote peer's cursor. Must run on the FX Application Thread.
     */
    public void removePeer(String clientId) {
        if (clientId == null || clientId.isBlank() || clientId.equals(localClientId)) {
            return;
        }
        CursorTarget target = targets.remove(clientId);
        Group node = nodes.remove(clientId);
        if (target != null && target.activeFade != null) {
            target.activeFade.stop();
        }
        if (node != null && node.getParent() != null) {
            cursorPane.getChildren().remove(node);
        }
    }

    /** Removes all remote cursor nodes. Must run on the FX Application Thread. */
    public void clear() {
        for (Group node : nodes.values()) {
            if (node.getParent() != null) {
                cursorPane.getChildren().remove(node);
            }
        }
        targets.clear();
        nodes.clear();
    }

    /** Stops the animation timer. Must run on the FX Application Thread. */
    public void stop() {
        animationTimer.stop();
        clear();
    }

    /**
     * One frame of LERP and stale eviction. Package-visible for tests.
     */
    void applyLerpTick() {
        tickLerpAndStale();
    }

    static double lerpStep(double current, double target, double factor) {
        return current + (target - current) * factor;
    }

    private void tickLerpAndStale() {
        long now = System.currentTimeMillis();

        for (Map.Entry<String, CursorTarget> entry : targets.entrySet()) {
            String clientId = entry.getKey();
            CursorTarget target = entry.getValue();
            Group node = nodes.get(clientId);
            if (node == null) {
                continue;
            }

            if (!target.fadingOut && now - target.lastUpdateTime > STALE_TIMEOUT_MS) {
                startFadeOut(clientId, target, node);
                continue;
            }

            if (target.fadingOut) {
                continue;
            }

            target.currentX = lerpStep(target.currentX, target.targetX, LERP_FACTOR);
            target.currentY = lerpStep(target.currentY, target.targetY, LERP_FACTOR);
            node.setTranslateX(target.currentX);
            node.setTranslateY(target.currentY);
        }
    }

    private void startFadeOut(String clientId, CursorTarget target, Group node) {
        target.fadingOut = true;
        FadeTransition ft = new FadeTransition(Duration.millis(FADE_DURATION_MS), node);
        ft.setFromValue(node.getOpacity());
        ft.setToValue(0.0);
        ft.setOnFinished(ev -> {
            cursorPane.getChildren().remove(node);
            nodes.remove(clientId);
            targets.remove(clientId);
        });
        target.activeFade = ft;
        ft.play();
    }

    private static Group buildCursorNode(String peerId, String displayName) {
        String label = displayName != null && !displayName.isBlank() ? displayName : peerId;

        int hash = peerId.hashCode();
        int r = clampBright((hash >> 16) & 0xFF);
        int g = clampBright((hash >> 8) & 0xFF);
        int b = clampBright(hash & 0xFF);

        Color fill = Color.rgb(r, g, b, 0.55);
        Color stroke = Color.rgb((int) (r * 0.75), (int) (g * 0.75), (int) (b * 0.75), 0.90);

        Circle dot = new Circle(9, fill);
        dot.setStroke(stroke);
        dot.setStrokeWidth(1.5);

        Label badge = new Label(label);
        badge.setLayoutX(13);
        badge.setLayoutY(-8);
        badge.setStyle(
                "-fx-text-fill: white; -fx-font-size: 10px; -fx-font-weight: bold;"
                        + "-fx-background-color: rgba(0,0,0,0.55); -fx-background-radius: 3;"
                        + "-fx-padding: 1 4;");

        return new Group(dot, badge);
    }

    private static int clampBright(int v) {
        return 80 + (v % 121);
    }

    private static final class CursorTarget {
        final String clientId;
        String authorName;
        double targetX;
        double targetY;
        double currentX;
        double currentY;
        long lastUpdateTime;
        boolean fadingOut;
        FadeTransition activeFade;

        CursorTarget(String clientId, String authorName) {
            this.clientId = clientId;
            this.authorName = authorName != null ? authorName : "";
            this.lastUpdateTime = System.currentTimeMillis();
        }
    }
}
