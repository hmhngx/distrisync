package com.distrisync.client;

import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class WhiteboardAppPropertiesBarTest extends ApplicationTest {

    private Scene canvasScene;

    @Override
    public void start(Stage stage) throws Exception {
        WhiteboardApp app = new WhiteboardApp();
        app.start(stage);
        canvasScene = getField(app, "canvasScene", Scene.class);
        WhiteboardAppTestFxSupport.showCanvasScene(stage, canvasScene);
    }

    @Test
    void propertiesBarRemainsVisibleWhenSwitchingDrawingTools() {
        Parent root = canvasScene.getRoot();
        Node propertiesBar = root.lookup("#" + WhiteboardApp.WORKSPACE_PROPERTIES_BAR_ID);
        ToggleButton eraser = (ToggleButton) root.lookup("#" + WhiteboardApp.TOOL_DOCK_ERASER_BUTTON_ID);
        ToggleButton pen = (ToggleButton) root.lookup("#" + WhiteboardApp.TOOL_DOCK_PEN_BUTTON_ID);

        assertThat(propertiesBar.isVisible()).isTrue();

        interact(eraser::fire);
        assertThat(propertiesBar.isVisible()).isTrue();

        interact(pen::fire);
        assertThat(propertiesBar.isVisible()).isTrue();
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
