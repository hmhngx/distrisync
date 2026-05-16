package com.distrisync.server;

import com.distrisync.model.ArrowNode;
import com.distrisync.model.EllipseNode;
import com.distrisync.model.RectangleNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShapeCodecNewShapesTest {

    @Test
    void rectangleEllipseArrowRoundTripOverWireEnvelope() {
        RectangleNode rect = RectangleNode.create("#FF0000", 10, 20, 80, 60, 2.0, "Alice", "c1");
        EllipseNode ellipse = EllipseNode.create("#00FF00", 5, 15, 90, 70, 3.0, "Bob", "c2");
        ArrowNode arrow = ArrowNode.create("#0000FF", 0, 0, 120, 40, 2.5, "Carol", "c3");

        assertThat(ShapeCodec.decodeMutation(ShapeCodec.encodeMutation(rect))).isEqualTo(rect);
        assertThat(ShapeCodec.decodeMutation(ShapeCodec.encodeMutation(ellipse))).isEqualTo(ellipse);
        assertThat(ShapeCodec.decodeMutation(ShapeCodec.encodeMutation(arrow))).isEqualTo(arrow);
    }
}
