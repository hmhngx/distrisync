package com.distrisync.server;

import com.distrisync.model.ArrowNode;
import com.distrisync.model.Circle;
import com.distrisync.model.EllipseNode;
import com.distrisync.model.EraserPath;
import com.distrisync.model.Line;
import com.distrisync.model.RectangleNode;
import com.distrisync.model.Shape;
import com.distrisync.model.TextNode;

/**
 * Server-side attribution for {@link Shape} payloads — binds {@link Shape#clientId()}
 * to the authenticated TCP session identity.
 */
final class ShapeAuthority {

    private ShapeAuthority() {}

    /**
     * Returns a copy of {@code shape} with {@link Shape#clientId()} replaced by
     * {@code clientId}. All other fields are preserved.
     */
    static Shape stampClientId(Shape shape, String clientId) {
        if (shape == null) throw new IllegalArgumentException("shape must not be null");
        if (clientId == null) throw new IllegalArgumentException("clientId must not be null");
        return switch (shape) {
            case Line l -> new Line(
                    l.objectId(), l.timestamp(), l.color(),
                    l.x1(), l.y1(), l.x2(), l.y2(), l.strokeWidth(),
                    l.authorName(), clientId);
            case Circle c -> new Circle(
                    c.objectId(), c.timestamp(), c.color(),
                    c.x(), c.y(), c.radius(), c.filled(), c.strokeWidth(),
                    c.authorName(), clientId);
            case TextNode t -> new TextNode(
                    t.objectId(), t.timestamp(), t.color(),
                    t.x(), t.y(), t.content(), t.fontFamily(), t.fontSize(),
                    t.bold(), t.italic(), t.authorName(), clientId);
            case EraserPath e -> new EraserPath(
                    e.objectId(), e.timestamp(), e.color(),
                    e.xs(), e.ys(), e.strokeWidth(), e.authorName(), clientId);
            case RectangleNode r -> new RectangleNode(
                    r.objectId(), r.timestamp(), r.color(),
                    r.x(), r.y(), r.width(), r.height(), r.strokeWidth(),
                    r.authorName(), clientId);
            case EllipseNode el -> new EllipseNode(
                    el.objectId(), el.timestamp(), el.color(),
                    el.x(), el.y(), el.width(), el.height(), el.strokeWidth(),
                    el.authorName(), clientId);
            case ArrowNode a -> new ArrowNode(
                    a.objectId(), a.timestamp(), a.color(),
                    a.x1(), a.y1(), a.x2(), a.y2(), a.strokeWidth(),
                    a.authorName(), clientId);
        };
    }
}
