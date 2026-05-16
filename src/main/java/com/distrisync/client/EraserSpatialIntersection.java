package com.distrisync.client;

import com.distrisync.model.EraserPath;
import com.distrisync.model.Shape;
import com.distrisync.server.CanvasStateManager;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Object eraser: spatial hit-test against committed shapes and queue
 * {@link Operation#SHAPE_DELETE} commands (satisfied on the wire via {@code UNDO_REQUEST}).
 */
public final class EraserSpatialIntersection {

    public enum Operation {
        SHAPE_DELETE
    }

    public record QueuedOperation(Operation operation, UUID shapeId) {}

    private final Supplier<Collection<Shape>> shapesView;
    private final Deque<QueuedOperation> pendingDeletes = new ArrayDeque<>();

    public EraserSpatialIntersection(Supplier<Collection<Shape>> shapesView) {
        this.shapesView = Objects.requireNonNull(shapesView, "shapesView");
    }

    /** Uses {@link CanvasStateManager#snapshot()} as the authoritative shape store. */
    public static EraserSpatialIntersection forStateManager(CanvasStateManager stateManager) {
        Objects.requireNonNull(stateManager, "stateManager");
        return new EraserSpatialIntersection(stateManager::snapshot);
    }

    /**
     * Hit-tests at {@code (x, y)} with brush size {@code eraserSize}
     * ({@link GlobalCanvasContext#getActiveStrokeWidth()}) and {@code eraserType}.
     *
     * @return the deleted shape id when a topmost intersecting shape was found
     */
    public Optional<UUID> eraseAt(double x, double y, double eraserSize, EraserType eraserType) {
        return findTopmostIntersecting(x, y, eraserSize, eraserType).map(shape -> {
            pendingDeletes.addLast(new QueuedOperation(Operation.SHAPE_DELETE, shape.objectId()));
            return shape.objectId();
        });
    }

    public List<QueuedOperation> getPendingDeletes() {
        return List.copyOf(pendingDeletes);
    }

    public void clearPendingDeletes() {
        pendingDeletes.clear();
    }

    private Optional<Shape> findTopmostIntersecting(
            double x, double y, double eraserSize, EraserType eraserType) {
        return shapesView.get().stream()
                .filter(s -> !(s instanceof EraserPath))
                .filter(s -> ShapeSpatialQuery.intersectsEraser(x, y, eraserSize, eraserType, s))
                .max(Comparator.comparingLong(Shape::timestamp));
    }
}
