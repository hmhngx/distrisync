package com.distrisync.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ShapeMathUtilsTest {

    @Test
    void testShapeMathUtilsShiftConstraint() {
        ShapeMathUtils.NormalizedRect square = ShapeMathUtils.rectangleFromDrag(0, 0, 100, 50, true);

        assertThat(square.width()).isEqualTo(100);
        assertThat(square.height()).isEqualTo(100);
    }

    @Test
    void arrowShiftSnapsToNearest45Degrees() {
        ShapeMathUtils.LineSegment seg = ShapeMathUtils.arrowFromDrag(0, 0, 100, 50, true);
        double angle = Math.atan2(seg.y2() - seg.y1(), seg.x2() - seg.x1());
        double snapped = Math.round(angle / (Math.PI / 4.0)) * (Math.PI / 4.0);
        assertThat(angle).isCloseTo(snapped, within(1e-9));
    }

    @Test
    void rectangleWithoutShiftKeepsAspect() {
        ShapeMathUtils.NormalizedRect rect = ShapeMathUtils.rectangleFromDrag(0, 0, 100, 50, false);
        assertThat(rect.width()).isEqualTo(100);
        assertThat(rect.height()).isEqualTo(50);
    }
}
