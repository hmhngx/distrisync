package com.distrisync.client;

import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteCursorManagerTest {

    @BeforeAll
    static void initJavaFxToolkit() {
        try {
            Platform.startup(() -> Platform.setImplicitExit(false));
        } catch (IllegalStateException ignored) {
            // Toolkit already initialized by another test class
        }
    }

    @Test
    void testCursorLerpAnimation() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        double[] translateX = new double[1];

        Platform.runLater(() -> {
            try {
                Pane pane = new Pane();
                RemoteCursorManager manager = new RemoteCursorManager(pane, "self");
                manager.updateTarget("peer", "Alice", 100, 100);
                manager.applyLerpTick();

                Group node = pane.getChildren().stream()
                        .filter(Group.class::isInstance)
                        .map(Group.class::cast)
                        .findFirst()
                        .orElseThrow();
                translateX[0] = node.getTranslateX();

                manager.stop();
            } finally {
                done.countDown();
            }
        });

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(translateX[0]).isGreaterThan(0.0).isLessThan(100.0);
    }

    @Test
    void lerpStep_movesTowardTarget() {
        assertThat(RemoteCursorManager.lerpStep(0, 100, 0.3)).isEqualTo(30.0);
        assertThat(RemoteCursorManager.lerpStep(30, 100, 0.3)).isEqualTo(51.0);
    }

    @Test
    void updateTarget_ignoresLocalClientId() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        int[] childCount = new int[1];

        Platform.runLater(() -> {
            try {
                Pane pane = new Pane();
                RemoteCursorManager manager = new RemoteCursorManager(pane, "self");
                manager.updateTarget("self", "Me", 50, 50);
                childCount[0] = pane.getChildren().size();
                manager.stop();
            } finally {
                done.countDown();
            }
        });

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(childCount[0]).isZero();
    }
}
