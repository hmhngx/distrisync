package com.distrisync.client;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.paint.Color;

/**
 * Shared drawing properties for the whiteboard (color and stroke width).
 * Instantiated once at the application root and passed to UI controls and shape factories.
 */
public final class GlobalCanvasContext {

    private final ObjectProperty<Color> activeColor =
            new SimpleObjectProperty<>(Color.web("#89b4fa"));
    private final DoubleProperty activeStrokeWidth = new SimpleDoubleProperty(2.0);
    private final ObjectProperty<EraserType> activeEraserType =
            new SimpleObjectProperty<>(EraserType.CIRCLE);

    public ObjectProperty<EraserType> activeEraserTypeProperty() {
        return activeEraserType;
    }

    public EraserType getActiveEraserType() {
        return activeEraserType.get();
    }

    public void setActiveEraserType(EraserType type) {
        activeEraserType.set(type);
    }

    public ObjectProperty<Color> activeColorProperty() {
        return activeColor;
    }

    public DoubleProperty activeStrokeWidthProperty() {
        return activeStrokeWidth;
    }

    public Color getActiveColor() {
        return activeColor.get();
    }

    public void setActiveColor(Color color) {
        activeColor.set(color);
    }

    public double getActiveStrokeWidth() {
        return activeStrokeWidth.get();
    }

    public void setActiveStrokeWidth(double width) {
        activeStrokeWidth.set(width);
    }

    /** Stroke color as {@code #RRGGBB} for model / network payloads. */
    public String activeColorHex() {
        return toHexString(getActiveColor());
    }

    static String toHexString(Color c) {
        return String.format("#%02X%02X%02X",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }
}
