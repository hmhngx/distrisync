package com.distrisync.client;

import com.distrisync.model.ArrowNode;
import com.distrisync.model.Circle;
import com.distrisync.model.EllipseNode;
import com.distrisync.model.Line;
import com.distrisync.model.RectangleNode;
import com.distrisync.model.TextNode;

import java.util.Objects;

/**
 * Creates committed shapes using color and stroke width from {@link GlobalCanvasContext}.
 */
public final class GlobalCanvasShapeFactory {

    private final GlobalCanvasContext context;

    public GlobalCanvasShapeFactory(GlobalCanvasContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public Line line(double x1, double y1, double x2, double y2, String authorName, String clientId) {
        return Line.create(
                context.activeColorHex(),
                x1, y1, x2, y2,
                context.getActiveStrokeWidth(),
                authorName, clientId);
    }

    public Circle circle(double x, double y, double radius, String authorName, String clientId) {
        return Circle.create(
                context.activeColorHex(),
                x, y,
                radius,
                context.getActiveStrokeWidth(),
                authorName, clientId);
    }

    public TextNode text(double x, double y, String content, String authorName, String clientId) {
        return TextNode.create(
                context.activeColorHex(),
                x, y, content,
                authorName, clientId);
    }

    public RectangleNode rectangle(
            double x, double y, double width, double height, String authorName, String clientId) {
        return RectangleNode.create(
                context.activeColorHex(), x, y, width, height,
                context.getActiveStrokeWidth(), authorName, clientId);
    }

    public EllipseNode ellipse(
            double x, double y, double width, double height, String authorName, String clientId) {
        return EllipseNode.create(
                context.activeColorHex(), x, y, width, height,
                context.getActiveStrokeWidth(), authorName, clientId);
    }

    public ArrowNode arrow(
            double x1, double y1, double x2, double y2, String authorName, String clientId) {
        return ArrowNode.create(
                context.activeColorHex(), x1, y1, x2, y2,
                context.getActiveStrokeWidth(), authorName, clientId);
    }
}
