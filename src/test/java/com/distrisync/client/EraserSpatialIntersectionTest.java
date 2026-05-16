package com.distrisync.client;

import com.distrisync.model.Line;
import com.distrisync.server.CanvasStateManager;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EraserSpatialIntersectionTest {

    @Test
    void testEraserSpatialIntersection() {
        CanvasStateManager manager = new CanvasStateManager();
        Line line = Line.create("#000000", 100, 100, 200, 200, 2.0);
        manager.applyMutation(line);

        EraserSpatialIntersection eraser = EraserSpatialIntersection.forStateManager(manager);
        eraser.eraseAt(150, 150, 10, EraserType.CIRCLE);

        assertThat(eraser.getPendingDeletes())
                .hasSize(1)
                .first()
                .satisfies(op -> {
                    assertThat(op.operation()).isEqualTo(EraserSpatialIntersection.Operation.SHAPE_DELETE);
                    assertThat(op.shapeId()).isEqualTo(line.objectId());
                });
    }

    @Test
    void eraseAtMissDoesNotQueueDelete() {
        CanvasStateManager manager = new CanvasStateManager();
        manager.applyMutation(Line.create("#000000", 100, 100, 200, 200, 2.0));

        EraserSpatialIntersection eraser = EraserSpatialIntersection.forStateManager(manager);
        eraser.eraseAt(10, 10, 10, EraserType.CIRCLE);

        assertThat(eraser.getPendingDeletes()).isEmpty();
    }

    @Test
    void eraseAtSelectsTopmostShapeByTimestamp() {
        CanvasStateManager manager = new CanvasStateManager();
        UUID olderId = UUID.randomUUID();
        UUID newerId = UUID.randomUUID();
        manager.applyMutation(new Line(olderId, 1L, "#FF0000", 100, 100, 200, 200, 4.0, "", ""));
        manager.applyMutation(new Line(newerId, 2L, "#00FF00", 100, 100, 200, 200, 4.0, "", ""));

        EraserSpatialIntersection eraser = EraserSpatialIntersection.forStateManager(manager);
        eraser.eraseAt(150, 150, 10, EraserType.CIRCLE);

        assertThat(eraser.getPendingDeletes()).hasSize(1);
        assertThat(eraser.getPendingDeletes().getFirst().shapeId()).isEqualTo(newerId);
    }

    @Test
    void squareEraserDeletesLineInsideBlock() {
        CanvasStateManager manager = new CanvasStateManager();
        Line line = Line.create("#000000", 100, 100, 200, 200, 2.0);
        manager.applyMutation(line);

        EraserSpatialIntersection eraser = EraserSpatialIntersection.forStateManager(manager);
        eraser.eraseAt(150, 150, 10, EraserType.SQUARE);

        assertThat(eraser.getPendingDeletes()).hasSize(1);
        assertThat(eraser.getPendingDeletes().getFirst().shapeId()).isEqualTo(line.objectId());
    }
}
