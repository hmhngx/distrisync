package com.distrisync.client;

import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ToggleButton;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class WhiteboardAppGlobalCanvasContextTest extends ApplicationTest {

    private WhiteboardApp app;
    private Scene canvasScene;

    @Override
    public void start(Stage stage) throws Exception {
        app = new WhiteboardApp();
        app.start(stage);
        canvasScene = getField(app, "canvasScene", Scene.class);
        WhiteboardAppTestFxSupport.showCanvasScene(stage, canvasScene);
    }

    @Test
    void testGlobalColorPersistsAcrossToolChanges() {
        Parent root = canvasScene.getRoot();
        ToggleButton selectButton = (ToggleButton) root.lookup("#" + WhiteboardApp.TOOL_DOCK_SELECT_BUTTON_ID);
        ToggleButton penButton = (ToggleButton) root.lookup("#" + WhiteboardApp.TOOL_DOCK_PEN_BUTTON_ID);
        ColorPicker colorPicker = lookup(".properties-color-picker").queryAs(ColorPicker.class);
        GlobalCanvasContext context = app.getGlobalCanvasContext();

        interact(penButton::fire);
        interact(() -> colorPicker.setValue(Color.RED));
        assertThat(context.getActiveColor()).isEqualTo(Color.RED);

        interact(selectButton::fire);
        interact(penButton::fire);

        assertThat(context.getActiveColor()).isEqualTo(Color.RED);
        assertThat(colorPicker.getValue()).isEqualTo(Color.RED);
    }

    @Test
    void propertiesBarStaysVisibleForAllTools() {
        Parent root = canvasScene.getRoot();
        Node propertiesBar = root.lookup("#" + WhiteboardApp.WORKSPACE_PROPERTIES_BAR_ID);
        ToggleButton eraser = (ToggleButton) root.lookup("#" + WhiteboardApp.TOOL_DOCK_ERASER_BUTTON_ID);
        ToggleButton select = (ToggleButton) root.lookup("#" + WhiteboardApp.TOOL_DOCK_SELECT_BUTTON_ID);

        assertThat(propertiesBar.isVisible()).isTrue();

        interact(eraser::fire);
        assertThat(propertiesBar.isVisible()).isTrue();

        interact(select::fire);
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
