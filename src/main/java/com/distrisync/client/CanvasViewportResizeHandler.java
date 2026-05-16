package com.distrisync.client;

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.scene.layout.Region;

/**
 * Coalesces {@link Region#widthProperty()} and {@link Region#heightProperty()} invalidations
 * into a single {@link Platform#runLater} callback so a viewport resize triggers one logical
 * redraw (JavaFX clears {@link javafx.scene.canvas.Canvas} pixels when dimensions change).
 */
public final class CanvasViewportResizeHandler {

    private final Runnable onRedraw;
    private boolean redrawCoalesced;

    public CanvasViewportResizeHandler(Runnable onRedraw) {
        this.onRedraw = onRedraw;
    }

    /**
     * Registers listeners on the container's width and height. Safe to call from the
     * FX thread during {@code start}; the first layout pulse may schedule one redraw.
     */
    public void attachTo(Region canvasContainer) {
        InvalidationListener listener = obs -> scheduleRedraw(canvasContainer);
        canvasContainer.widthProperty().addListener(listener);
        canvasContainer.heightProperty().addListener(listener);
    }

    private void scheduleRedraw(Region canvasContainer) {
        if (!isViewportDrawable(canvasContainer)) {
            return;
        }
        if (redrawCoalesced) {
            return;
        }
        redrawCoalesced = true;
        Platform.runLater(() -> {
            redrawCoalesced = false;
            if (!isViewportDrawable(canvasContainer)) {
                return;
            }
            onRedraw.run();
        });
    }

    /**
     * JavaFX {@link javafx.scene.canvas.Canvas} must not paint at 0×0 — Prism throws
     * {@code RTTexture.createGraphics() NPE} during {@code NGCanvas.initCanvas}.
     */
    static boolean isViewportDrawable(Region region) {
        return region != null && region.getWidth() > 0 && region.getHeight() > 0;
    }
}
