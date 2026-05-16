package com.distrisync.client;

import javafx.scene.Scene;
import javafx.stage.Stage;

/** Shared TestFX helpers for {@link WhiteboardApp} workspace scene tests. */
final class WhiteboardAppTestFxSupport {

    private WhiteboardAppTestFxSupport() {
    }

    static void showCanvasScene(Stage stage, Scene canvasScene) {
        stage.setScene(canvasScene);
        stage.show();
    }
}
