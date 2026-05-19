package com.distrisync.client;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.testfx.api.FxAssert.verifyThat;
import static org.testfx.matcher.base.NodeMatchers.isVisible;

/**
 * Voice HUD: participant avatars reflect {@link Participant#isSpeakingProperty()}.
 */
class WhiteboardAppParticipantHudTest extends ApplicationTest {

    private static final String TEST_CLIENT_ID = "User123";

    private WhiteboardApp app;
    private Scene canvasScene;
    private NetworkClient networkClient;

    @Override
    public void start(Stage stage) throws Exception {
        app = new WhiteboardApp();
        app.start(stage);
        canvasScene = getField(app, "canvasScene", Scene.class);
        networkClient = new NetworkClient("127.0.0.1", 9090, "Test User", TEST_CLIENT_ID);
        setField(app, "networkClient", networkClient);
        invokeWireParticipantHud(app, networkClient.getParticipantManager());
        WhiteboardAppTestFxSupport.showCanvasScene(stage, canvasScene);
    }

    @Test
    void testParticipantHudReflectsSpeakingState() {
        ParticipantManager manager = networkClient.getParticipantManager();

        interact(() -> {
            manager.putParticipant(TEST_CLIENT_ID, "Test User");
            Participant participant = manager.get(TEST_CLIENT_ID);
            assertThat(participant).isNotNull();
            participant.setSpeaking(true);
        });

        String avatarId = CollaborationRoster.avatarNodeId(TEST_CLIENT_ID);
        StackPane avatar = lookup("#" + avatarId).queryAs(StackPane.class);
        verifyThat(avatar, isVisible());

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(avatar.getStyleClass()).contains("speaking-ring"));
    }

    private static void invokeWireParticipantHud(WhiteboardApp app, ParticipantManager manager) {
        try {
            Method m = WhiteboardApp.class.getDeclaredMethod("wireParticipantHud", ParticipantManager.class);
            m.setAccessible(true);
            m.invoke(app, manager);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("wireParticipantHud", ex);
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
