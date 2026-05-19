package com.distrisync.client;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

/**
 * Short-lived top-center toast overlay for the whiteboard scene.
 */
public final class ToastNotification {

    private static final String TOAST_STYLE_CLASS = "toast-notification";
    private static final String LABEL_STYLE_CLASS = "lobby-status-muted";
    private static final double SLIDE_OFFSET = 40.0;
    private static final Duration SLIDE_IN_MS = Duration.millis(200);
    private static final Duration HOLD_MS = Duration.seconds(3);
    private static final Duration FADE_MS = Duration.millis(200);

    private static StackPane activeShell;
    private static SequentialTransition activeSequence;

    private ToastNotification() {}

    /**
     * Shows a toast on {@code host}. Must run on the JavaFX application thread.
     * Replaces any in-flight toast on the same host.
     */
    public static void show(StackPane host, String message) {
        show(host, message, LABEL_STYLE_CLASS);
    }

    /**
     * Shows a toast with a custom label style class (e.g. {@code lobby-status-disconnected} for danger).
     */
    public static void show(StackPane host, String message, String labelStyleClass) {
        if (host == null || message == null) {
            return;
        }
        cancelActive();

        String style = labelStyleClass != null && !labelStyleClass.isBlank()
                ? labelStyleClass
                : LABEL_STYLE_CLASS;
        Label label = new Label(message);
        label.getStyleClass().add(style);
        label.setWrapText(true);
        label.setMaxWidth(360);

        StackPane shell = new StackPane(label);
        shell.getStyleClass().add(TOAST_STYLE_CLASS);
        shell.setPickOnBounds(false);
        shell.setMouseTransparent(true);
        shell.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(shell, Pos.TOP_CENTER);
        StackPane.setMargin(shell, new Insets(24, 0, 0, 0));
        shell.setOpacity(0);
        shell.setTranslateY(-SLIDE_OFFSET);

        host.getChildren().add(shell);
        activeShell = shell;

        TranslateTransition slideIn = new TranslateTransition(SLIDE_IN_MS, shell);
        slideIn.setFromY(-SLIDE_OFFSET);
        slideIn.setToY(0);

        FadeTransition fadeIn = new FadeTransition(SLIDE_IN_MS, shell);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);

        PauseTransition hold = new PauseTransition(HOLD_MS);

        FadeTransition fadeOut = new FadeTransition(FADE_MS, shell);
        fadeOut.setFromValue(1);
        fadeOut.setToValue(0);

        SequentialTransition seq = new SequentialTransition(slideIn, fadeIn, hold, fadeOut);
        seq.setOnFinished(e -> {
            host.getChildren().remove(shell);
            if (activeShell == shell) {
                activeShell = null;
                activeSequence = null;
            }
        });
        activeSequence = seq;
        seq.play();
    }

    private static void cancelActive() {
        if (activeSequence != null) {
            activeSequence.stop();
            activeSequence = null;
        }
        if (activeShell != null) {
            StackPane parent = activeShell.getParent() instanceof StackPane sp ? sp : null;
            if (parent != null) {
                parent.getChildren().remove(activeShell);
            }
            activeShell = null;
        }
    }
}
