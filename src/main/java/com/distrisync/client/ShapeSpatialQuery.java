package com.distrisync.client;

import com.distrisync.model.ArrowNode;
import com.distrisync.model.Circle;
import com.distrisync.model.EllipseNode;
import com.distrisync.model.EraserPath;
import com.distrisync.model.Line;
import com.distrisync.model.RectangleNode;
import com.distrisync.model.Shape;
import com.distrisync.model.TextNode;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;

/**
 * Fast spatial queries for eraser hit-testing: axis-aligned bounding-box rejection,
 * then precise geometry for circular or square brushes.
 */
public final class ShapeSpatialQuery {

    private ShapeSpatialQuery() {}

    /** Inclusive bounds of the shape's ink in canvas space. */
    public static Bounds boundsOf(Shape shape) {
        return switch (shape) {
            case Line l -> {
                double pad = l.strokeWidth() / 2.0;
                double minX = Math.min(l.x1(), l.x2()) - pad;
                double minY = Math.min(l.y1(), l.y2()) - pad;
                double maxX = Math.max(l.x1(), l.x2()) + pad;
                double maxY = Math.max(l.y1(), l.y2()) + pad;
                yield new BoundingBox(minX, minY, maxX - minX, maxY - minY);
            }
            case Circle c -> {
                double pad = c.filled() ? 0.0 : c.strokeWidth() / 2.0;
                double r = c.radius() + pad;
                yield new BoundingBox(c.x() - r, c.y() - r, r * 2, r * 2);
            }
            case TextNode t -> {
                double w = t.content().length() * t.fontSize() * 0.6;
                yield new BoundingBox(t.x(), t.y() - t.fontSize(), w, t.fontSize());
            }
            case EraserPath ep -> new BoundingBox(0, 0, 0, 0);
            case RectangleNode r -> {
                double pad = r.strokeWidth() / 2.0;
                yield new BoundingBox(r.x() - pad, r.y() - pad, r.width() + pad * 2, r.height() + pad * 2);
            }
            case EllipseNode e -> {
                double pad = e.strokeWidth() / 2.0;
                yield new BoundingBox(e.x() - pad, e.y() - pad, e.width() + pad * 2, e.height() + pad * 2);
            }
            case ArrowNode a -> {
                double pad = a.strokeWidth() / 2.0;
                double minX = Math.min(a.x1(), a.x2()) - pad;
                double minY = Math.min(a.y1(), a.y2()) - pad;
                double maxX = Math.max(a.x1(), a.x2()) + pad;
                double maxY = Math.max(a.y1(), a.y2()) + pad;
                yield new BoundingBox(minX, minY, maxX - minX, maxY - minY);
            }
        };
    }

    /**
     * {@code true} when an eraser brush at {@code (centerX, centerY)} with edge length
     * {@code eraserSize} intersects the shape.
     */
    public static boolean intersectsEraser(
            double centerX, double centerY, double eraserSize, EraserType eraserType, Shape shape) {
        if (shape instanceof EraserPath) {
            return false;
        }
        return switch (eraserType) {
            case CIRCLE -> intersectsCircularEraser(centerX, centerY, eraserSize, shape);
            case SQUARE -> intersectsSquareEraser(centerX, centerY, eraserSize, shape);
        };
    }

    private static boolean intersectsCircularEraser(double centerX, double centerY, double diameter, Shape shape) {
        double radius = diameter / 2.0;
        Bounds eraserBounds = new BoundingBox(
                centerX - radius, centerY - radius, diameter, diameter);
        if (!boundsOf(shape).intersects(eraserBounds)) {
            return false;
        }
        return preciseCircularEraserIntersects(centerX, centerY, radius, shape);
    }

    private static boolean intersectsSquareEraser(double centerX, double centerY, double edgeLength, Shape shape) {
        Rectangle2D eraserRect = eraserSquare(centerX, centerY, edgeLength);
        Bounds eraserBounds = new BoundingBox(
                eraserRect.getMinX(), eraserRect.getMinY(), eraserRect.getWidth(), eraserRect.getHeight());
        if (!boundsOf(shape).intersects(eraserBounds)) {
            return false;
        }
        return preciseSquareEraserIntersects(eraserRect, shape);
    }

    private static Rectangle2D eraserSquare(double centerX, double centerY, double edgeLength) {
        double half = edgeLength / 2.0;
        return new Rectangle2D(centerX - half, centerY - half, edgeLength, edgeLength);
    }

    private static boolean preciseCircularEraserIntersects(double cx, double cy, double radius, Shape shape) {
        return switch (shape) {
            case Line l -> distanceToSegment(cx, cy, l.x1(), l.y1(), l.x2(), l.y2())
                    <= l.strokeWidth() / 2.0 + radius;
            case Circle c -> {
                double dist = Math.hypot(cx - c.x(), cy - c.y());
                if (c.filled()) {
                    yield dist <= c.radius() + radius;
                }
                yield Math.abs(dist - c.radius()) <= c.strokeWidth() / 2.0 + radius;
            }
            case TextNode t -> {
                double w = t.content().length() * t.fontSize() * 0.6;
                yield cx >= t.x() - radius
                        && cx <= t.x() + w + radius
                        && cy >= t.y() - t.fontSize() - radius
                        && cy <= t.y() + radius;
            }
            case EraserPath ep -> false;
            case RectangleNode r -> pointInExpandedRect(cx, cy, radius, r.x(), r.y(), r.width(), r.height(), r.strokeWidth());
            case EllipseNode e -> ellipseContains(cx, cy, radius, e);
            case ArrowNode a -> distanceToSegment(cx, cy, a.x1(), a.y1(), a.x2(), a.y2())
                    <= a.strokeWidth() / 2.0 + radius;
        };
    }

    private static boolean preciseSquareEraserIntersects(Rectangle2D eraser, Shape shape) {
        return switch (shape) {
            case Line l -> segmentIntersectsRect(
                    l.x1(), l.y1(), l.x2(), l.y2(),
                    expandRect(eraser, l.strokeWidth() / 2.0));
            case Circle c -> circleIntersectsRect(c, eraser);
            case TextNode t -> {
                double w = t.content().length() * t.fontSize() * 0.6;
                Rectangle2D textRect = new Rectangle2D(t.x(), t.y() - t.fontSize(), w, t.fontSize());
                Bounds textBounds = new BoundingBox(
                        textRect.getMinX(), textRect.getMinY(), textRect.getWidth(), textRect.getHeight());
                Bounds eraserBounds = new BoundingBox(
                        eraser.getMinX(), eraser.getMinY(), eraser.getWidth(), eraser.getHeight());
                yield textBounds.intersects(eraserBounds);
            }
            case EraserPath ep -> false;
            case RectangleNode r -> rectIntersectsRect(eraser, r.x(), r.y(), r.width(), r.height(), r.strokeWidth());
            case EllipseNode e -> ellipseIntersectsRect(eraser, e);
            case ArrowNode a -> segmentIntersectsRect(
                    a.x1(), a.y1(), a.x2(), a.y2(), expandRect(eraser, a.strokeWidth() / 2.0));
        };
    }

    private static boolean pointInExpandedRect(
            double px, double py, double pad,
            double x, double y, double w, double h, double strokeWidth) {
        double p = Math.max(pad, strokeWidth / 2.0);
        return px >= x - p && px <= x + w + p && py >= y - p && py <= y + h + p;
    }

    private static boolean rectIntersectsRect(
            Rectangle2D eraser, double x, double y, double w, double h, double strokeWidth) {
        Bounds shapeBounds = new BoundingBox(
                x - strokeWidth / 2, y - strokeWidth / 2, w + strokeWidth, h + strokeWidth);
        Bounds eraserBounds = new BoundingBox(
                eraser.getMinX(), eraser.getMinY(), eraser.getWidth(), eraser.getHeight());
        return shapeBounds.intersects(eraserBounds);
    }

    private static boolean ellipseContains(double px, double py, double pad, EllipseNode e) {
        double cx = e.x() + e.width() / 2.0;
        double cy = e.y() + e.height() / 2.0;
        double rx = e.width() / 2.0 + pad + e.strokeWidth() / 2.0;
        double ry = e.height() / 2.0 + pad + e.strokeWidth() / 2.0;
        if (rx <= 0 || ry <= 0) {
            return false;
        }
        double nx = (px - cx) / rx;
        double ny = (py - cy) / ry;
        return nx * nx + ny * ny <= 1.0;
    }

    private static boolean ellipseIntersectsRect(Rectangle2D eraser, EllipseNode e) {
        Bounds eraserBounds = new BoundingBox(
                eraser.getMinX(), eraser.getMinY(), eraser.getWidth(), eraser.getHeight());
        return boundsOf(e).intersects(eraserBounds);
    }

    private static Rectangle2D expandRect(Rectangle2D rect, double pad) {
        return new Rectangle2D(
                rect.getMinX() - pad,
                rect.getMinY() - pad,
                rect.getWidth() + pad * 2,
                rect.getHeight() + pad * 2);
    }

    private static boolean circleIntersectsRect(Circle c, Rectangle2D eraser) {
        if (c.filled()) {
            double closestX = clamp(c.x(), eraser.getMinX(), eraser.getMaxX());
            double closestY = clamp(c.y(), eraser.getMinY(), eraser.getMaxY());
            return Math.hypot(c.x() - closestX, c.y() - closestY) <= c.radius();
        }
        double closestX = clamp(c.x(), eraser.getMinX(), eraser.getMaxX());
        double closestY = clamp(c.y(), eraser.getMinY(), eraser.getMaxY());
        double dist = Math.hypot(c.x() - closestX, c.y() - closestY);
        return Math.abs(dist - c.radius()) <= c.strokeWidth() / 2.0;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean segmentIntersectsRect(
            double x1, double y1, double x2, double y2, Rectangle2D rect) {
        if (rect.contains(x1, y1) || rect.contains(x2, y2)) {
            return true;
        }
        double minX = rect.getMinX();
        double minY = rect.getMinY();
        double maxX = rect.getMaxX();
        double maxY = rect.getMaxY();
        return segmentsIntersect(x1, y1, x2, y2, minX, minY, maxX, minY)
                || segmentsIntersect(x1, y1, x2, y2, maxX, minY, maxX, maxY)
                || segmentsIntersect(x1, y1, x2, y2, maxX, maxY, minX, maxY)
                || segmentsIntersect(x1, y1, x2, y2, minX, maxY, minX, minY);
    }

    private static boolean segmentsIntersect(
            double x1, double y1, double x2, double y2,
            double x3, double y3, double x4, double y4) {
        double d1 = direction(x3, y3, x4, y4, x1, y1);
        double d2 = direction(x3, y3, x4, y4, x2, y2);
        double d3 = direction(x1, y1, x2, y2, x3, y3);
        double d4 = direction(x1, y1, x2, y2, x4, y4);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
                && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }
        if (d1 == 0 && onSegment(x3, y3, x4, y4, x1, y1)) return true;
        if (d2 == 0 && onSegment(x3, y3, x4, y4, x2, y2)) return true;
        if (d3 == 0 && onSegment(x1, y1, x2, y2, x3, y3)) return true;
        return d4 == 0 && onSegment(x1, y1, x2, y2, x4, y4);
    }

    private static double direction(double xi, double yi, double xj, double yj, double xk, double yk) {
        return (xk - xi) * (yj - yi) - (xj - xi) * (yk - yi);
    }

    private static boolean onSegment(double xi, double yi, double xj, double yj, double xk, double yk) {
        return Math.min(xi, xj) <= xk && xk <= Math.max(xi, xj)
                && Math.min(yi, yj) <= yk && yk <= Math.max(yi, yj);
    }

    private static double distanceToSegment(double px, double py,
                                            double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double lenSq = dx * dx + dy * dy;
        if (lenSq == 0) {
            return Math.hypot(px - x1, py - y1);
        }
        double t = Math.max(0, Math.min(1, ((px - x1) * dx + (py - y1) * dy) / lenSq));
        double projX = x1 + t * dx;
        double projY = y1 + t * dy;
        return Math.hypot(px - projX, py - projY);
    }
}
