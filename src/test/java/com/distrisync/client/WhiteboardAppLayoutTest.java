package com.distrisync.client;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class WhiteboardAppLayoutTest {

    @BeforeAll
    static void initJavaFxToolkit() {
        try {
            Platform.startup(() -> Platform.setImplicitExit(false));
        } catch (IllegalStateException ignored) {
            // Toolkit already started by another test class in the same JVM.
        }
        Platform.setImplicitExit(false);
    }

    @Test
    void testWindowCannotShrinkBelowMinimumBounds() throws Exception {
        AtomicReference<Double> widthRef = new AtomicReference<>(0.0);
        AtomicReference<Double> heightRef = new AtomicReference<>(0.0);
        CountDownLatch done = new CountDownLatch(1);

        Platform.runLater(() -> {
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(), 960, 720));
            WhiteboardApp.enforceMinimumStageBounds(stage);
            stage.show();

            stage.setWidth(400);
            stage.setHeight(300);

            widthRef.set(stage.getWidth());
            heightRef.set(stage.getHeight());
            stage.hide();
            done.countDown();
        });

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(widthRef.get()).isGreaterThanOrEqualTo(800.0);
        assertThat(heightRef.get()).isGreaterThanOrEqualTo(600.0);
    }

    @Test
    void testToolbarButtonsRemainVisibleOnShrink() throws Exception {
        AtomicReference<Double> maxXRef = new AtomicReference<>();
        AtomicReference<Double> sceneWidthRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Platform.runLater(() -> {
            WhiteboardApp app = new WhiteboardApp();
            VBox toolDrawer = app.buildToolDrawer();
            StackPane root = new StackPane(toolDrawer);
            StackPane.setAlignment(toolDrawer, Pos.CENTER_LEFT);

            Stage stage = new Stage();
            Scene scene = new Scene(root, 800, 600);
            stage.setScene(scene);
            WhiteboardApp.enforceMinimumStageBounds(stage);
            stage.setWidth(800);
            stage.setHeight(600);
            stage.show();
            root.applyCss();
            root.layout();

            Node clearBtn = scene.lookup("#" + WhiteboardApp.TOOLBAR_CLEAR_BUTTON_ID);
            assertThat(clearBtn).isNotNull();
            maxXRef.set(Objects.requireNonNull(clearBtn).localToScene(clearBtn.getBoundsInLocal()).getMaxX());
            sceneWidthRef.set(scene.getWidth());
            stage.hide();
            done.countDown();
        });

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(maxXRef.get()).isNotNull();
        assertThat(sceneWidthRef.get()).isNotNull();
        assertThat(maxXRef.get()).isLessThanOrEqualTo(sceneWidthRef.get() + 0.5);
    }

    @Test
    void testTelemetryHudStaysWithinSceneWidthOnSmallWindow() throws Exception {
        AtomicReference<Double> telemetryMaxXRef = new AtomicReference<>();
        AtomicReference<Double> sceneWidthRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                WhiteboardApp app = new WhiteboardApp();
                Stage stage = new Stage();
                app.start(stage);
                Scene scene = getField(app, "canvasScene", Scene.class);
                stage.setScene(scene);
                WhiteboardApp.enforceMinimumStageBounds(stage);
                stage.setWidth(800);
                stage.setHeight(600);
                stage.show();
                scene.getRoot().applyCss();
                scene.getRoot().layout();

                Node telemetryHud = getField(app, "telemetryHudRoot", Node.class);
                /* Layout bounds exclude -fx-effect; boundsInLocal includes drop-shadow outsets and
                   fails after softer/larger shadows while the panel itself still fits the scene. */
                Bounds layout = telemetryHud.getLayoutBounds();
                telemetryMaxXRef.set(telemetryHud.localToScene(layout).getMaxX());
                sceneWidthRef.set(scene.getWidth());
                stage.hide();
            } finally {
                done.countDown();
            }
        });

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(telemetryMaxXRef.get()).isNotNull();
        assertThat(sceneWidthRef.get()).isNotNull();
        assertThat(telemetryMaxXRef.get()).isLessThanOrEqualTo(sceneWidthRef.get() + 0.5);
    }

    private static <T> T getField(Object target, String fieldName, Class<T> type) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to read field: " + fieldName, ex);
        }
    }
}
