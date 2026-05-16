package com.distrisync.client;

import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class WhiteboardAppFloatingLayoutTest extends ApplicationTest {

    private WhiteboardApp app;

    @Override
    public void start(Stage stage) throws Exception {
        app = new WhiteboardApp();
        app.start(stage);
        Scene canvasScene = getField(app, "canvasScene", Scene.class);
        WhiteboardAppTestFxSupport.showCanvasScene(stage, canvasScene);
    }

    @Test
    void testFloatingPanelsRespect16pxPerimeter() {
        StackPane workspaceRoot = lookup("#" + WhiteboardApp.WORKSPACE_ROOT_ID).queryAs(StackPane.class);
        StackPane boardsLayer = lookup("#" + WhiteboardApp.WORKSPACE_TOOLBAR_LAYER_ID).queryAs(StackPane.class);
        StackPane propertiesBarLayer = lookup("#" + WhiteboardApp.WORKSPACE_PROPERTIES_LAYER_ID)
                .queryAs(StackPane.class);
        Button boardsButton = lookup("#" + WhiteboardApp.BOARDS_BUTTON_ID).queryAs(Button.class);
        ToggleButton penButton = lookup("#" + WhiteboardApp.TOOL_DOCK_PEN_BUTTON_ID).queryAs(ToggleButton.class);
        interact(penButton::fire);
        interact(() -> {
            workspaceRoot.applyCss();
            workspaceRoot.layout();
        });
        HBox propertiesBar = lookup("#" + WhiteboardApp.WORKSPACE_PROPERTIES_BAR_ID).queryAs(HBox.class);
        assertThat(StackPane.getMargin(boardsLayer)).isEqualTo(new Insets(16, 0, 0, 16));
        assertThat(StackPane.getMargin(propertiesBarLayer)).isEqualTo(new Insets(16, 0, 0, 0));
        Bounds boardsInRoot = workspaceRoot.sceneToLocal(boardsButton.localToScene(boardsButton.getLayoutBounds()));
        Bounds propsInRoot = workspaceRoot.sceneToLocal(propertiesBar.localToScene(propertiesBar.getLayoutBounds()));
        assertThat(boardsInRoot.getMinX()).isCloseTo(16.0, within(0.5));
        assertThat(propsInRoot.getMinY()).isCloseTo(16.0, within(0.5));
    }

    @Test
    void testFloatingPanelsMaintainEdgeClearance() {
        StackPane workspaceRoot = lookup("#" + WhiteboardApp.WORKSPACE_ROOT_ID).queryAs(StackPane.class);
        StackPane boardsLayer = lookup("#" + WhiteboardApp.WORKSPACE_TOOLBAR_LAYER_ID).queryAs(StackPane.class);
        StackPane dockLayer = lookup("#" + WhiteboardApp.WORKSPACE_DOCK_LAYER_ID).queryAs(StackPane.class);
        VBox toolDock = lookup(".tool-dock").queryAs(VBox.class);
        interact(() -> {
            workspaceRoot.applyCss();
            workspaceRoot.layout();
        });
        assertThat(StackPane.getMargin(boardsLayer)).isEqualTo(new Insets(16, 0, 0, 16));
        assertThat(StackPane.getMargin(dockLayer)).isEqualTo(new Insets(64, 0, 0, 16));
        /* Tool dock's immediate parent is the clip StackPane; use scene space for true edge clearance. */
        double workspaceLeft = workspaceRoot.localToScene(0, 0).getX();
        Bounds dockLayout = toolDock.getLayoutBounds();
        Point2D dockTopLeftScene = toolDock.localToScene(dockLayout.getMinX(), dockLayout.getMinY());
        assertThat(dockTopLeftScene.getX() - workspaceLeft).isGreaterThanOrEqualTo(16.0 - 0.5);
        Bounds boardsLayout = boardsLayer.getLayoutBounds();
        Point2D boardsTopLeftScene = boardsLayer.localToScene(boardsLayout.getMinX(), boardsLayout.getMinY());
        assertThat(boardsTopLeftScene.getX() - workspaceLeft).isGreaterThanOrEqualTo(16.0 - 0.5);
        assertThat(boardsTopLeftScene.getY() - workspaceRoot.localToScene(0, 0).getY()).isGreaterThanOrEqualTo(16.0 - 0.5);
    }

    @Test
    void testCanvasUIMarginsAreConsistent() {
        Button boardsButton = lookup("#" + WhiteboardApp.BOARDS_BUTTON_ID).queryAs(Button.class);
        StackPane dockLayer = lookup("#" + WhiteboardApp.WORKSPACE_DOCK_LAYER_ID).queryAs(StackPane.class);
        interact(() -> {
            boardsButton.getScene().getRoot().applyCss();
            boardsButton.getScene().getRoot().layout();
        });
        Bounds boardsScene = boardsButton.localToScene(boardsButton.getLayoutBounds());
        Bounds dockScene = dockLayer.localToScene(dockLayer.getLayoutBounds());
        assertThat(boardsScene.getMinX()).isEqualTo(dockScene.getMinX(), within(2.0));
    }

    @Test
    void canvasTracksWorkspaceRootAndToolbarRendersAboveCanvasLayer() {
        StackPane workspaceRoot = lookup("#" + WhiteboardApp.WORKSPACE_ROOT_ID).queryAs(StackPane.class);
        StackPane canvasLayer = lookup("#" + WhiteboardApp.WORKSPACE_CANVAS_LAYER_ID).queryAs(StackPane.class);
        Node toolbarLayer = lookup("#" + WhiteboardApp.WORKSPACE_TOOLBAR_LAYER_ID).query();
        Canvas baseCanvas = getField(app, "baseCanvas", Canvas.class);

        assertThat(baseCanvas.widthProperty().isBound()).isTrue();
        assertThat(baseCanvas.heightProperty().isBound()).isTrue();
        assertThat(canvasLayer.prefWidthProperty().isBound()).isTrue();
        assertThat(canvasLayer.prefHeightProperty().isBound()).isTrue();
        assertThat(baseCanvas.getWidth()).isCloseTo(canvasLayer.getWidth(), within(1.0));
        assertThat(baseCanvas.getHeight()).isCloseTo(canvasLayer.getHeight(), within(1.0));

        int canvasIndex = workspaceRoot.getChildren().indexOf(canvasLayer);
        int toolbarIndex = workspaceRoot.getChildren().indexOf(toolbarLayer);
        assertThat(canvasIndex).isEqualTo(0);
        assertThat(toolbarIndex).isGreaterThan(canvasIndex);
    }

    @Test
    void testFloatingPanelsHaveCorrectEffects() {
        VBox leftDock = lookup(".tool-dock").queryAs(VBox.class);
        assertThat(leftDock.getEffect()).isInstanceOf(DropShadow.class);
    }

    @Test
    void testLeaveRoomButtonContrastState() {
        Button leave = lookup("#" + WhiteboardApp.LEAVE_ROOM_BUTTON_ID).queryAs(Button.class);
        assertThat(leave.getTextFill()).isEqualTo(Color.web("#8B93A0"));
    }

    @Test
    void testToolButtonsArePerfectlySquare() {
        ToggleGroup group = getField(app, "canvasToolGroup", ToggleGroup.class);
        assertThat(group.getToggles()).isNotEmpty();
        for (Toggle t : group.getToggles()) {
            assertThat(t).isInstanceOf(ToggleButton.class);
            ToggleButton tb = (ToggleButton) t;
            assertThat(tb.getPrefWidth()).isEqualTo(40.0);
            assertThat(tb.getPrefHeight()).isEqualTo(40.0);
        }
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
