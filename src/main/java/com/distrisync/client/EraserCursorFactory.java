package com.distrisync.client;

import javafx.scene.Cursor;
import javafx.scene.ImageCursor;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;

/**
 * Builds eraser {@link ImageCursor} images matching {@link GlobalCanvasContext#getActiveStrokeWidth()}.
 */
public final class EraserCursorFactory {

    private EraserCursorFactory() {}

    public static Image createCursorImage(double activeStrokeWidth, EraserType eraserType) {
        return switch (eraserType) {
            case CIRCLE -> createCircleCursorImage(activeStrokeWidth);
            case SQUARE -> createSquareCursorImage(activeStrokeWidth);
        };
    }

    /** Transparent center, 1px black outline, diameter {@code activeStrokeWidth}. */
    public static Image createCircleCursorImage(double activeStrokeWidth) {
        double diameter = Math.max(activeStrokeWidth, 4.0);
        int borderPad = 2;
        int size = (int) Math.ceil(diameter) + borderPad * 2;
        Canvas canvas = new Canvas(size, size);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double center = size / 2.0;
        double radius = diameter / 2.0;
        gc.setStroke(Color.BLACK);
        gc.setLineWidth(1);
        gc.strokeOval(center - radius, center - radius, diameter, diameter);
        return snapshot(canvas);
    }

    /** Hollow square outline with 1px black border and side {@code activeStrokeWidth}. */
    public static Image createSquareCursorImage(double activeStrokeWidth) {
        double edge = Math.max(activeStrokeWidth, 4.0);
        int borderPad = 2;
        int size = (int) Math.ceil(edge) + borderPad * 2;
        Canvas canvas = new Canvas(size, size);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double origin = borderPad + 0.5;
        gc.setStroke(Color.BLACK);
        gc.setLineWidth(1);
        gc.strokeRect(origin, origin, edge - 1, edge - 1);
        return snapshot(canvas);
    }

    public static ImageCursor createImageCursor(double activeStrokeWidth, EraserType eraserType) {
        Image image = createCursorImage(activeStrokeWidth, eraserType);
        double hotspot = image.getWidth() / 2.0;
        return new ImageCursor(image, hotspot, hotspot);
    }

    private static Image snapshot(Canvas canvas) {
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        return canvas.snapshot(params, null);
    }
}
