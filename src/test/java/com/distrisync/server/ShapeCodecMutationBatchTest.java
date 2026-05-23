package com.distrisync.server;

import com.distrisync.model.Line;
import com.distrisync.model.Shape;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShapeCodecMutationBatchTest {

    @Test
    void mutationBatchRoundTripPreservesAllLines() {
        Line a = Line.create("#89b4fa", 0, 0, 10, 10, 2.0, "Alice", "c1");
        Line b = Line.create("#89b4fa", 10, 10, 20, 20, 2.0, "Alice", "c1");
        Line c = Line.create("#89b4fa", 20, 20, 30, 30, 2.0, "Alice", "c1");

        String payload = ShapeCodec.encodeMutationBatch(List.of(a, b, c));
        List<com.distrisync.model.Shape> decoded = ShapeCodec.decodeMutationBatch(payload);

        assertThat(decoded).containsExactly(a, b, c);
    }

    @Test
    void chunkMutationBatchPayloads_splitsAtMaxShapesPerBatch() {
        List<Shape> shapes = new ArrayList<>(45);
        for (int i = 0; i < 45; i++) {
            shapes.add(Line.create("#89b4fa", i, i, i + 1, i + 1, 2.0, "Alice", "c1"));
        }

        List<String> payloads = ShapeCodec.chunkMutationBatchPayloads(shapes);

        assertThat(payloads).hasSize(3);
        assertThat(ShapeCodec.decodeMutationBatch(payloads.get(0))).hasSize(20);
        assertThat(ShapeCodec.decodeMutationBatch(payloads.get(1))).hasSize(20);
        assertThat(ShapeCodec.decodeMutationBatch(payloads.get(2))).hasSize(5);

        int total = payloads.stream()
                .mapToInt(p -> ShapeCodec.decodeMutationBatch(p).size())
                .sum();
        assertThat(total).isEqualTo(45);
    }
}
