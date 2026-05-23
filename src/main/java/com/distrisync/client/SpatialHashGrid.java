package com.distrisync.client;

import com.distrisync.model.Shape;

import javafx.geometry.Bounds;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Uniform spatial hash for eraser hit-testing: shapes are bucketed by grid cell
 * from their axis-aligned bounding box. {@link #query(double, double)} returns
 * only shapes registered in the cell containing {@code (x, y)}.
 */
public final class SpatialHashGrid {

    public static final double CELL_SIZE = 200.0;

    private final Map<String, List<Shape>> grid = new HashMap<>();

    public void insert(Shape shape) {
        Objects.requireNonNull(shape, "shape");
        CellRange range = cellRangeForBounds(ShapeSpatialQuery.boundsOf(shape));
        for (int cx = range.minCellX; cx <= range.maxCellX; cx++) {
            for (int cy = range.minCellY; cy <= range.maxCellY; cy++) {
                grid.computeIfAbsent(cellKey(cx, cy), k -> new ArrayList<>()).add(shape);
            }
        }
    }

    public void remove(Shape shape) {
        Objects.requireNonNull(shape, "shape");
        UUID id = shape.objectId();
        CellRange range = cellRangeForBounds(ShapeSpatialQuery.boundsOf(shape));
        for (int cx = range.minCellX; cx <= range.maxCellX; cx++) {
            for (int cy = range.minCellY; cy <= range.maxCellY; cy++) {
                List<Shape> cell = grid.get(cellKey(cx, cy));
                if (cell != null) {
                    cell.removeIf(s -> s.objectId().equals(id));
                }
            }
        }
    }

    /**
     * Returns shapes indexed in the single cell containing {@code (x, y)}.
     * Never returns the live backing list.
     */
    public List<Shape> query(double x, double y) {
        List<Shape> cell = grid.get(cellKey(cellX(x), cellY(y)));
        if (cell == null || cell.isEmpty()) {
            return Collections.emptyList();
        }
        return List.copyOf(cell);
    }

    public void clear() {
        grid.clear();
    }

    static int cellX(double x) {
        return (int) Math.floor(x / CELL_SIZE);
    }

    static int cellY(double y) {
        return (int) Math.floor(y / CELL_SIZE);
    }

    static String cellKey(int cellX, int cellY) {
        return cellX + "," + cellY;
    }

    static CellRange cellRangeForBounds(Bounds bounds) {
        int minCellX = (int) Math.floor(bounds.getMinX() / CELL_SIZE);
        int maxCellX = (int) Math.floor(bounds.getMaxX() / CELL_SIZE);
        int minCellY = (int) Math.floor(bounds.getMinY() / CELL_SIZE);
        int maxCellY = (int) Math.floor(bounds.getMaxY() / CELL_SIZE);
        return new CellRange(minCellX, maxCellX, minCellY, maxCellY);
    }

    record CellRange(int minCellX, int maxCellX, int minCellY, int maxCellY) {}
}
