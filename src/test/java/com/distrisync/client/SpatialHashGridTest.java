package com.distrisync.client;

import com.distrisync.model.Line;
import com.distrisync.model.Shape;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SpatialHashGridTest {

    private SpatialHashGrid grid;

    @BeforeEach
    void setUp() {
        grid = new SpatialHashGrid();
    }

    @Test
    void queryReturnsShapeInSameCellAndEmptyInAdjacentCell() {
        Line line = Line.create("#000000", 50, 50, 80, 80, 2.0);
        grid.insert(line);

        assertThat(grid.query(60, 60)).extracting(Shape::objectId).containsExactly(line.objectId());
        assertThat(grid.query(250, 250)).isEmpty();
    }

    @Test
    void wideLineIsQueryableFromEachOverlappedCell() {
        Line line = Line.create("#000000", 50, 50, 350, 50, 2.0);
        grid.insert(line);

        assertThat(grid.query(60, 60)).extracting(Shape::objectId).contains(line.objectId());
        assertThat(grid.query(300, 60)).extracting(Shape::objectId).contains(line.objectId());
    }

    @Test
    void removeClearsShapeFromAllCells() {
        Line line = Line.create("#000000", 50, 50, 350, 50, 2.0);
        grid.insert(line);
        grid.remove(line);

        assertThat(grid.query(60, 60)).isEmpty();
        assertThat(grid.query(300, 60)).isEmpty();
    }

    @Test
    void clearRemovesAllIndexedShapes() {
        grid.insert(Line.create("#000000", 50, 50, 80, 80, 2.0));
        grid.clear();
        assertThat(grid.query(60, 60)).isEmpty();
    }

    @Test
    void updateRemovesPreviousBoundsBeforeReindexing() {
        UUID id = UUID.randomUUID();
        Line original = new Line(id, 1L, "#FF0000", 50, 50, 80, 80, 2.0, "", "");
        Line moved = new Line(id, 2L, "#00FF00", 450, 450, 480, 480, 2.0, "", "");
        grid.insert(original);
        grid.remove(original);
        grid.insert(moved);

        assertThat(grid.query(60, 60)).isEmpty();
        assertThat(grid.query(460, 460)).extracting(Shape::objectId).containsExactly(id);
    }

    @Test
    void queryReturnsCopyNotLiveBackingList() {
        Line line = Line.create("#000000", 50, 50, 80, 80, 2.0);
        grid.insert(line);
        assertThat(grid.query(60, 60)).isNotSameAs(grid.query(60, 60));
    }
}
