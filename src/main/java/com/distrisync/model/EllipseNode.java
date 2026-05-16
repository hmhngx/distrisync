package com.distrisync.model;

import java.util.UUID;

/**
 * Axis-aligned ellipse inscribed in the bounding box ({@code x}, {@code y}, {@code width}, {@code height}).
 */
public record EllipseNode(
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

    public EllipseNode {
        if (objectId == null) throw new IllegalArgumentException("objectId must not be null");
        if (color == null || color.isBlank()) throw new IllegalArgumentException("color must not be blank");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("width and height must be positive");
        if (strokeWidth <= 0) throw new IllegalArgumentException("strokeWidth must be positive");
        if (authorName == null) authorName = "";
        if (clientId == null) clientId = "";
    }

    public static EllipseNode create(
            String color, double x, double y, double width, double height, double strokeWidth,
            String authorName, String clientId) {
        return new EllipseNode(
                UUID.randomUUID(), System.currentTimeMillis(),
                color, x, y, width, height, strokeWidth, authorName, clientId);
    }
}
