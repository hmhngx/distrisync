package com.distrisync.model;

import java.util.UUID;

/**
 * Directed arrow segment from ({@code x1}, {@code y1}) to ({@code x2}, {@code y2}) with a triangular head.
 */
public record ArrowNode(
        UUID   objectId,
        long   timestamp,
        String color,
        double x1,
        double y1,
        double x2,
        double y2,
        double strokeWidth,
        String authorName,
        String clientId
) implements Shape {

    public ArrowNode {
        if (objectId == null) throw new IllegalArgumentException("objectId must not be null");
        if (color == null || color.isBlank()) throw new IllegalArgumentException("color must not be blank");
        if (strokeWidth <= 0) throw new IllegalArgumentException("strokeWidth must be positive");
        if (authorName == null) authorName = "";
        if (clientId == null) clientId = "";
    }

    public static ArrowNode create(
            String color, double x1, double y1, double x2, double y2, double strokeWidth,
            String authorName, String clientId) {
        return new ArrowNode(
                UUID.randomUUID(), System.currentTimeMillis(),
                color, x1, y1, x2, y2, strokeWidth, authorName, clientId);
    }
}
