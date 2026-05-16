package com.distrisync.client;

import com.distrisync.model.Line;
import com.distrisync.server.CanvasStateManager;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Viewport resize coalescing: authoritative shape store is {@link CanvasStateManager} on the server;
 * the client mirrors that state into canvases — this test proves one logical redraw per resize pulse.
 */
class CanvasViewportResizeHandlerTest {

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
    void testCanvasResizingTriggersRedraw() throws Exception {
        CanvasStateManager csm = new CanvasStateManager();
        Line line = Line.create("#111111", 0, 0, 40, 40, 2);
        assertThat(csm.applyMutation(line)).isTrue();

        AtomicInteger redrawCalls = new AtomicInteger();
        Runnable redraw = () -> {
            assertThat(csm.snapshot()).hasSize(1);
            redrawCalls.incrementAndGet();
        };

        AtomicReference<Stage> stageRef = new AtomicReference<>();
        CountDownLatch setup = new CountDownLatch(1);
        Platform.runLater(() -> {
            StackPane workspace = new StackPane();
            Stage stage = new Stage();
            stageRef.set(stage);
            stage.setScene(new Scene(workspace, 400, 300));
            stage.show();
            workspace.applyCss();
            workspace.layout();
            new CanvasViewportResizeHandler(redraw).attachTo(workspace);
            setup.countDown();
        });
        assertThat(setup.await(5, TimeUnit.SECONDS)).isTrue();

        redrawCalls.set(0);

        CountDownLatch resizePosted = new CountDownLatch(1);
        Platform.runLater(() -> {
            Stage st = stageRef.get();
            assertThat(st).isNotNull();
            st.setWidth(820);
            resizePosted.countDown();
        });
        assertThat(resizePosted.await(5, TimeUnit.SECONDS)).isTrue();

        await().atMost(3, TimeUnit.SECONDS)
                .pollInterval(20, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(redrawCalls.get()).isEqualTo(1));

        CountDownLatch hidden = new CountDownLatch(1);
        Platform.runLater(() -> {
            stageRef.get().hide();
            hidden.countDown();
        });
        assertThat(hidden.await(5, TimeUnit.SECONDS)).isTrue();
    }
}
