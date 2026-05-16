package com.distrisync.client;

import javafx.geometry.Bounds;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

class WhiteboardAppLobbyStylesTest extends ApplicationTest {

    private WhiteboardApp app;

    @Override
    public void start(Stage stage) throws Exception {
        app = new WhiteboardApp();
        Parent lobbyRoot = invoke(app, "buildLobbyRoot", Parent.class);
        invoke(app, "refreshLobbyRooms", Void.class, List.of(new RoomInfo("Design Room", 3)));
        stage.setScene(new Scene(lobbyRoot, 900, 700));
        stage.show();
        lobbyRoot.applyCss();
        lobbyRoot.layout();
    }

    @Test
    void lobbyRoomRowUsesExpectedCssClasses() {
        FlowPane roomCard = lookup(".room-card").queryAs(FlowPane.class);
        Label roomTitle = lookup(".lobby-room-title").queryAs(Label.class);
        Label roomMeta = lookup(".lobby-meta").queryAs(Label.class);
        Button joinButton = from(roomCard).lookup(".tool-button").queryAs(Button.class);
        Button deleteButton = from(roomCard).lookup(".danger-btn").queryAs(Button.class);

        assertThat(roomCard.getStyleClass()).contains("room-card");
        assertThat(roomTitle.getStyleClass()).contains("lobby-room-title");
        assertThat(roomMeta.getStyleClass()).contains("lobby-meta");
        assertThat(joinButton.getStyleClass()).contains("tool-button");
        assertThat(deleteButton.getStyleClass()).contains("danger-btn");
    }

    @Test
    void testLobbyInputAndButtonAreInline() {
        TextField newRoomField = lookup(".lobby-textfield").queryAs(TextField.class);
        Button createRoom = lookup(".tool-button").queryAll().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(b -> "Create Room".equals(b.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Create Room button not found"));

        assertThat(newRoomField.getParent()).isSameAs(createRoom.getParent());
        assertThat(newRoomField.getParent()).isInstanceOf(HBox.class);

        Bounds fieldBounds = newRoomField.getBoundsInParent();
        Bounds buttonBounds = createRoom.getBoundsInParent();
        double yOverlap = Math.min(fieldBounds.getMaxY(), buttonBounds.getMaxY())
                - Math.max(fieldBounds.getMinY(), buttonBounds.getMinY());
        assertThat(yOverlap).isPositive();
    }

    @Test
    void testLobbyCardIsCenteredFloatingPanel() {
        Label header = lookup(".lobby-header").queryAs(Label.class);
        Parent p = header.getParent();
        while (p != null && !p.getStyleClass().contains("floating-panel")) {
            p = p.getParent();
        }
        assertThat(p).isNotNull();

        VBox modal = lookup("#" + WhiteboardApp.LOBBY_MODAL_CARD_ID).queryAs(VBox.class);
        assertThat(modal.getStyleClass()).contains("floating-panel");
        assertThat(modal.getStyleClass()).contains("lobby-floating-card");
        assertThat(modal.getMaxWidth()).isEqualTo(600.0);
        assertThat(p).isSameAs(modal);

        Scene scene = modal.getScene();
        interact(() -> {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
        });
        Bounds modalInScene = modal.localToScene(modal.getLayoutBounds());
        double sceneMidX = scene.getWidth() / 2.0;
        double sceneMidY = scene.getHeight() / 2.0;
        double cardMidX = modalInScene.getMinX() + modalInScene.getWidth() / 2.0;
        double cardMidY = modalInScene.getMinY() + modalInScene.getHeight() / 2.0;
        assertThat(cardMidX).isCloseTo(sceneMidX, within(20.0));
        assertThat(cardMidY).isCloseTo(sceneMidY, within(40.0));
    }

    @Test
    void testToastNotificationFadesInAndOut() {
        interact(() -> invoke(app, "showToast", Void.class, "Toast unit test message"));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(lookup("#" + WhiteboardApp.LOBBY_TOAST_SHELL_ID).query().isVisible()).isTrue();

        await().atMost(4, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(lookup("#" + WhiteboardApp.LOBBY_TOAST_SHELL_ID).query().isVisible())
                        .isFalse());
    }

    private static <T> T invoke(Object target, String name, Class<T> type, Object... args) {
        try {
            Method method = target.getClass().getDeclaredMethod(name, parameterTypes(args));
            method.setAccessible(true);
            Object result = method.invoke(target, args);
            if (type == Void.class) {
                return null;
            }
            return type.cast(result);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to invoke method: " + name, ex);
        }
    }

    private static Class<?>[] parameterTypes(Object[] args) {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a == null) {
                types[i] = Object.class;
            } else if (a instanceof List) {
                types[i] = List.class;
            } else if (a instanceof String) {
                types[i] = String.class;
            } else {
                types[i] = a.getClass();
            }
        }
        return types;
    }
}
