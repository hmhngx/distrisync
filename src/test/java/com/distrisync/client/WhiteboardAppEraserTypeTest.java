package com.distrisync.client;

import javafx.scene.Cursor;
import javafx.scene.ImageCursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class WhiteboardAppEraserTypeTest extends ApplicationTest {

    private WhiteboardApp app;

    @Override
    public void start(Stage stage) throws Exception {
        app = new WhiteboardApp();
        app.start(stage);
        Scene canvasScene = getField(app, "canvasScene", Scene.class);
        WhiteboardAppTestFxSupport.showCanvasScene(stage, canvasScene);
    }

    @Test
    void testEraserCursorChangesOnTypeToggle() {
        ToggleButton eraserTool = lookup("#" + WhiteboardApp.TOOL_DOCK_ERASER_BUTTON_ID)
                .queryAs(ToggleButton.class);
        ToggleButton squareType = lookup("#" + WhiteboardApp.ERASER_TYPE_SQUARE_BUTTON_ID)
                .queryAs(ToggleButton.class);
        Canvas baseCanvas = getField(app, "baseCanvas", Canvas.class);

        interact(eraserTool::fire);

        Cursor circleCursor = baseCanvas.getCursor();
        assertThat(circleCursor).isInstanceOf(ImageCursor.class);
        Image circleImage = ((ImageCursor) circleCursor).getImage();

        interact(squareType::fire);

        Cursor squareCursor = baseCanvas.getCursor();
        assertThat(squareCursor).isInstanceOf(ImageCursor.class);
        Image squareImage = ((ImageCursor) squareCursor).getImage();
        assertThat(squareImage).isNotSameAs(circleImage);

        assertThat(app.getGlobalCanvasContext().getActiveEraserType()).isEqualTo(EraserType.SQUARE);
    }

    @Test
    void eraserTypeToggleVisibleOnlyWhenEraserActive() {
        ToggleButton eraserTool = lookup("#" + WhiteboardApp.TOOL_DOCK_ERASER_BUTTON_ID)
                .queryAs(ToggleButton.class);
        ToggleButton selectTool = lookup("#" + WhiteboardApp.TOOL_DOCK_SELECT_BUTTON_ID)
                .queryAs(ToggleButton.class);

        interact(selectTool::fire);
        Node eraserBar = lookup("#" + WhiteboardApp.WORKSPACE_ERASER_TYPE_BAR_ID).query();
        assertThat(eraserBar.isVisible()).isFalse();

        interact(eraserTool::fire);
        assertThat(eraserBar.isVisible()).isTrue();
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
