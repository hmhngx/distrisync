package com.distrisync.model;

import java.util.UUID;

/**
 * Axis-aligned rectangle defined by top-left corner and size.
 */
public record RectangleNode(
        UUID   objectId,
        long   timestamp,
        String color,
        double x,
        double y,
        double width,
        double height,
        double strokeWidth,
        String authorName,
        String clientId
) implements Shape {

    public RectangleNode {
        if (objectId == null) throw new IllegalArgumentException("objectId must not be null");
        if (color == null || color.isBlank()) throw new IllegalArgumentException("color must not be blank");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("width and height must be positive");
        if (strokeWidth <= 0) throw new IllegalArgumentException("strokeWidth must be positive");
        if (authorName == null) authorName = "";
        if (clientId == null) clientId = "";
    }

    public static RectangleNode create(
            String color, double x, double y, double width, double height, double strokeWidth,
            String authorName, String clientId) {
        return new RectangleNode(
                UUID.randomUUID(), System.currentTimeMillis(),
                color, x, y, width, height, strokeWidth, authorName, clientId);
    }
}
