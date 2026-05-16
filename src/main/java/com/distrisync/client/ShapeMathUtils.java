package com.distrisync.client;

/**
 * Drag-geometry helpers for rubber-band tools (SHIFT constraints for Paint/Figma-style shapes).
 */
public final class ShapeMathUtils {

    private static final double QUARTER_TURN = Math.PI / 4.0;

    private ShapeMathUtils() {}

    /** Normalized top-left anchor and positive width/height. */
    public record NormalizedRect(double x, double y, double width, double height) {}

    /** Directed segment from ({@code x1}, {@code y1}) to ({@code x2}, {@code y2}). */
    public record LineSegment(double x1, double y1, double x2, double y2) {}

    /**
     * Rectangle drag from corner {@code (x0, y0)} to {@code (x1, y1)}.
     * With SHIFT: forces {@code width == height == max(|dx|, |dy|)} preserving quadrant sign.
     */
    public static NormalizedRect rectangleFromDrag(
            double x0, double y0, double x1, double y1, boolean shiftHeld) {
        double[] delta = constrainAxisDelta(x1 - x0, y1 - y0, shiftHeld);
        return normalizeRect(x0, y0, x0 + delta[0], y0 + delta[1]);
    }

    /** Ellipse uses the same bounding-box constraint as a rectangle (SHIFT → circle). */
    public static NormalizedRect ellipseFromDrag(
            double x0, double y0, double x1, double y1, boolean shiftHeld) {
        return rectangleFromDrag(x0, y0, x1, y1, shiftHeld);
    }

    /**
     * Arrow drag; with SHIFT the angle snaps to the nearest 45° using {@link Math#atan2}.
     */
    public static LineSegment arrowFromDrag(
            double x0, double y0, double x1, double y1, boolean shiftHeld) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        if (shiftHeld) {
            double len = Math.hypot(dx, dy);
            if (len < 1e-9) {
                return new LineSegment(x0, y0, x0, y0);
            }
            double angle = Math.atan2(dy, dx);
            double snapped = Math.round(angle / QUARTER_TURN) * QUARTER_TURN;
            dx = Math.cos(snapped) * len;
            dy = Math.sin(snapped) * len;
        }
        return new LineSegment(x0, y0, x0 + dx, y0 + dy);
    }

    /**
     * Constrains a drag delta for square locking.
     *
     * @return {@code [dx, dy]} with both components equal to {@code max(|dx|, |dy|)} when
     *         {@code shiftHeld} is true (signs preserved)
     */
    public static double[] constrainAxisDelta(double dx, double dy, boolean shiftHeld) {
        if (!shiftHeld) {
            return new double[]{dx, dy};
        }
        double size = Math.max(Math.abs(dx), Math.abs(dy));
        double signX = dx == 0 ? (dy >= 0 ? 1.0 : -1.0) : Math.signum(dx);
        double signY = dy == 0 ? (dx >= 0 ? 1.0 : -1.0) : Math.signum(dy);
        return new double[]{signX * size, signY * size};
    }

    static NormalizedRect normalizeRect(double x0, double y0, double x1, double y1) {
        double minX = Math.min(x0, x1);
        double minY = Math.min(y0, y1);
        return new NormalizedRect(minX, minY, Math.abs(x1 - x0), Math.abs(y1 - y0));
    }
}
