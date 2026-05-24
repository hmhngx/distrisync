package com.distrisync.server;

import com.distrisync.model.Circle;
import com.distrisync.model.Shape;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShapeAuthorityTest {

    @Test
    void stampClientId_replacesOnlyClientIdOnCircle() {
        Circle original = Circle.create("#000000", 1.0, 2.0, 10.0, "Alice", "spoofed-id");
        Shape stamped = ShapeAuthority.stampClientId(original, "trusted-session");

        assertThat(stamped).isInstanceOf(Circle.class);
        Circle circle = (Circle) stamped;
        assertThat(circle.clientId()).isEqualTo("trusted-session");
        assertThat(circle.authorName()).isEqualTo("Alice");
        assertThat(circle.objectId()).isEqualTo(original.objectId());
        assertThat(circle.x()).isEqualTo(original.x());
        assertThat(circle.y()).isEqualTo(original.y());
        assertThat(circle.radius()).isEqualTo(original.radius());
    }
}
