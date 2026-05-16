package com.distrisync.client;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.effect.Effect;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;
import static org.testfx.api.FxAssert.verifyThat;
import static org.testfx.matcher.base.NodeMatchers.isVisible;

/**
 * Mic toggle HUD: spacebar must not change mute state (PTT removed).
 */
class WhiteboardAppMicToggleTest extends ApplicationTest {

    private WhiteboardApp app;
    private Scene canvasScene;
    private NetworkClient networkClient;

    @Override
    public void start(Stage stage) throws Exception {
        app = new WhiteboardApp();
        app.start(stage);
        canvasScene = getField(app, "canvasScene", Scene.class);
        networkClient = new NetworkClient("127.0.0.1", 9090, "Test", "test-client");
        setField(app, "networkClient", networkClient);
        invokeWireMicToggleHud(app, networkClient.getAudioEngine());
        WhiteboardAppTestFxSupport.showCanvasScene(stage, canvasScene);
    }

    @Test
    void testSpacebarDoesNotTriggerMic() {
        AudioEngine audio = networkClient.getAudioEngine();
        boolean mutedBefore = audio.isMicMutedProperty().get();

        KeyEvent spacePress = new KeyEvent(
                KeyEvent.KEY_PRESSED,
                "",
                "",
                KeyCode.SPACE,
                false,
                false,
                false,
                false);

        interact(() -> canvasScene.getRoot().fireEvent(spacePress));

        assertThat(audio.isMicMutedProperty().get())
                .as("SPACE must not toggle the microphone")
                .isEqualTo(mutedBefore);
        assertThat(audio.isMicMuted()).isEqualTo(mutedBefore);
    }

    @Test
    void testMicToggleVisualStates() {
        AudioEngine audio = networkClient.getAudioEngine();
        Button micBtn = lookup("#" + WhiteboardApp.MIC_TOGGLE_BUTTON_ID).queryButton();
        verifyThat(micBtn, isVisible());

        interact(() -> {
            audio.setMicMuted(true);
            audio.isSpeakingProperty().set(false);
            refreshMicToggleCss(micBtn);
        });

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(micBtn.getStyleClass()).contains("muted");
            assertThat(micBtn.getStyleClass()).doesNotContain("speaking");
            assertThat(micBtn.getTooltip().getText()).isEqualTo("Unmute mic");
            assertColorCloseTo(assertBackgroundColor(micBtn), Color.web("#22262F"), 0.02);
            assertColorCloseTo(assertTextFill(micBtn), Color.web("#EF4444"), 0.02);
            assertThat(micBtn.getEffect()).isNull();
        });

        interact(() -> {
            audio.setMicMuted(false);
            audio.isSpeakingProperty().set(false);
            refreshMicToggleCss(micBtn);
        });

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(micBtn.getStyleClass()).doesNotContain("muted", "speaking");
            assertThat(micBtn.getTooltip().getText()).isEqualTo("Mute mic");
            assertColorCloseTo(assertBackgroundColor(micBtn), Color.web("#2C313C"), 0.02);
            assertColorCloseTo(assertTextFill(micBtn), Color.web("#EDEDED"), 0.02);
            assertThat(micBtn.getEffect()).isNull();
        });

        interact(() -> {
            audio.setMicMuted(false);
            audio.isSpeakingProperty().set(true);
            refreshMicToggleCss(micBtn);
        });

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(micBtn.getStyleClass()).contains("speaking");
            assertThat(micBtn.getStyleClass()).doesNotContain("muted");
            assertThat(micBtn.getTooltip().getText()).isEqualTo("Mute mic (live)");
            assertColorCloseTo(assertBackgroundColor(micBtn), Color.rgb(16, 185, 129, 0.2), 0.02);
            assertColorCloseTo(assertTextFill(micBtn), Color.web("#10B981"), 0.02);
            Effect glow = micBtn.getEffect();
            assertThat(glow).isNotNull();
            assertThat(glow.getClass().getSimpleName()).containsIgnoringCase("DropShadow");
        });
    }

    private static void refreshMicToggleCss(Button micBtn) {
        Scene scene = micBtn.getScene();
        if (scene != null) {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
        }
        micBtn.applyCss();
    }

    private static Color assertBackgroundColor(Button button) {
        Background background = button.getBackground();
        assertThat(background).isNotNull();
        assertThat(background.getFills()).isNotEmpty();
        BackgroundFill fill = background.getFills().getFirst();
        Paint paint = fill.getFill();
        assertThat(paint).isInstanceOf(Color.class);
        return (Color) paint;
    }

    private static Color assertTextFill(Labeled labeled) {
        Paint textFill = labeled.getTextFill();
        assertThat(textFill).isInstanceOf(Color.class);
        return (Color) textFill;
    }

    private static void assertColorCloseTo(Color actual, Color expected, double tolerance) {
        assertThat(actual.getRed()).isCloseTo(expected.getRed(), within(tolerance));
        assertThat(actual.getGreen()).isCloseTo(expected.getGreen(), within(tolerance));
        assertThat(actual.getBlue()).isCloseTo(expected.getBlue(), within(tolerance));
        assertThat(actual.getOpacity()).isCloseTo(expected.getOpacity(), within(tolerance));
    }

    private static void invokeWireMicToggleHud(WhiteboardApp app, AudioEngine audio) {
        try {
            Method m = WhiteboardApp.class.getDeclaredMethod("wireMicToggleHud", AudioEngine.class);
            m.setAccessible(true);
            m.invoke(app, audio);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("wireMicToggleHud", ex);
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

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to write field: " + fieldName, ex);
        }
    }
}
