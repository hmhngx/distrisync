package com.distrisync.client;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.lang.reflect.Field;

import org.assertj.core.data.Offset;

import static org.assertj.core.api.Assertions.assertThat;

class WhiteboardAppToolDockTest extends ApplicationTest {

    private WhiteboardApp app;

    @Override
    public void start(Stage stage) throws Exception {
        app = new WhiteboardApp();
        app.start(stage);
        Scene canvasScene = getField(app, "canvasScene", Scene.class);
        WhiteboardAppTestFxSupport.showCanvasScene(stage, canvasScene);
    }

    @Test
    void testCanvasBackgroundIsDarkSlate() {
        StackPane root = lookup("#" + WhiteboardApp.WORKSPACE_ROOT_ID).queryAs(StackPane.class);
        interact(() -> {
            root.applyCss();
            root.layout();
        });
        assertThat(root.getBackground()).isNotNull();
        assertThat(root.getBackground().getFills()).isNotEmpty();
        assertThat(root.getBackground().getFills().getFirst().getFill()).isInstanceOf(Color.class);
        Color fill = (Color) root.getBackground().getFills().getFirst().getFill();
        String hex = String.format("#%02X%02X%02X",
                (int) Math.round(fill.getRed() * 255),
                (int) Math.round(fill.getGreen() * 255),
                (int) Math.round(fill.getBlue() * 255));
        assertThat(hex).isEqualToIgnoringCase("#0E1015");
    }

    @Test
    void testActiveToolHasRoundedCornersAndAccentColor() {
        ToggleButton penButton = lookup("#" + WhiteboardApp.TOOL_DOCK_PEN_BUTTON_ID).queryAs(ToggleButton.class);
        interact(penButton::fire);
        interact(() -> {
            penButton.applyCss();
            penButton.layout();
        });
        assertThat(penButton.isSelected()).isTrue();
        assertThat(penButton.getBackground()).isNotNull();
        assertThat(penButton.getBackground().getFills()).isNotEmpty();
        CornerRadii radii = penButton.getBackground().getFills().getFirst().getRadii();
        assertThat(radii.getTopLeftHorizontalRadius()).isEqualTo(6.0, Offset.offset(0.05));
        assertThat(radii.getTopRightHorizontalRadius()).isEqualTo(6.0, Offset.offset(0.05));
        assertThat(radii.getBottomRightHorizontalRadius()).isEqualTo(6.0, Offset.offset(0.05));
        assertThat(radii.getBottomLeftHorizontalRadius()).isEqualTo(6.0, Offset.offset(0.05));

        Node graphic = penButton.getGraphic();
        assertThat(graphic).isInstanceOf(StackPane.class);
        StackPane iconWrap = (StackPane) graphic;
        assertThat(iconWrap.getChildren().getFirst()).isInstanceOf(Label.class);
        Label icon = (Label) iconWrap.getChildren().getFirst();
        interact(() -> {
            icon.applyCss();
            icon.layout();
        });
        assertThat(icon.getTextFill()).isEqualTo(Color.web("#5C6CFF"));
    }

    @Test
    void clickingPenSelectsPenAndUnselectsSelectTool() {
        Scene canvasScene = getField(app, "canvasScene", Scene.class);
        Parent root = canvasScene.getRoot();
        ToggleButton selectButton = (ToggleButton) root.lookup("#" + WhiteboardApp.TOOL_DOCK_SELECT_BUTTON_ID);
        ToggleButton penButton = (ToggleButton) root.lookup("#" + WhiteboardApp.TOOL_DOCK_PEN_BUTTON_ID);

        assertThat(selectButton.isSelected()).isTrue();
        assertThat(penButton.isSelected()).isFalse();

        interact(penButton::fire);

        Object activeTool = getField(app, "activeTool", Object.class);
        assertThat(String.valueOf(activeTool)).isEqualTo("FREEHAND");
        assertThat(penButton.isSelected()).isTrue();
        assertThat(selectButton.isSelected()).isFalse();
    }

    @Test
    void testDockIconsAreProperlyScaled() {
        ToggleButton penButton = lookup("#" + WhiteboardApp.TOOL_DOCK_PEN_BUTTON_ID).queryAs(ToggleButton.class);
        interact(() -> {
            penButton.applyCss();
            penButton.layout();
        });
        Node graphic = penButton.getGraphic();
        assertThat(graphic).isNotNull().isInstanceOf(StackPane.class);
        StackPane iconWrap = (StackPane) graphic;
        assertThat(iconWrap.getPrefWidth()).isEqualTo(20.0);
        assertThat(iconWrap.getPrefHeight()).isEqualTo(20.0);
    }

    @Test
    void toolDockToggleCollapsesAndExpandsDrawer() {
        Scene canvasScene = getField(app, "canvasScene", Scene.class);
        interact(() -> {
            canvasScene.getRoot().applyCss();
            canvasScene.getRoot().layout();
        });
        Button toggle = lookup("#" + WhiteboardApp.TOOL_DOCK_TOGGLE_BUTTON_ID).queryAs(Button.class);
        ToolsDrawerToggleModel model = getField(app, "toolsDrawerToggleModel", ToolsDrawerToggleModel.class);
        assertThat(model.isToolsOpen()).isTrue();

        interact(toggle::fire);
        WaitForAsyncUtils.waitForFxEvents();
        sleep(300);
        assertThat(model.isToolsOpen()).isFalse();

        interact(toggle::fire);
        WaitForAsyncUtils.waitForFxEvents();
        sleep(300);
        assertThat(model.isToolsOpen()).isTrue();
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
