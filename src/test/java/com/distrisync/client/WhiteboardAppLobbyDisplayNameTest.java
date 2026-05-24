package com.distrisync.client;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lobby display-name validation (headless FX).
 */
class WhiteboardAppLobbyDisplayNameTest {

    @BeforeAll
    static void initJavaFxToolkit() {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException ignored) {
            // Toolkit already started by another test class in the same JVM.
        }
    }

    @Test
    void isLobbyDisplayNameValid_blankIsInvalid() {
        assertThat(WhiteboardApp.isLobbyDisplayNameValid(null)).isFalse();
        assertThat(WhiteboardApp.isLobbyDisplayNameValid("")).isFalse();
        assertThat(WhiteboardApp.isLobbyDisplayNameValid("   ")).isFalse();
    }

    @Test
    void isLobbyDisplayNameValid_nonBlankIsValid() {
        assertThat(WhiteboardApp.isLobbyDisplayNameValid("Alice")).isTrue();
        assertThat(WhiteboardApp.isLobbyDisplayNameValid("  Bob  ")).isTrue();
    }

    @Test
    void applyLobbyDisplayNameFieldStyle_setsRedOrGreenBorder() throws Exception {
        TextField field = new TextField();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            WhiteboardApp.applyLobbyDisplayNameFieldStyle(field, false);
            assertThat(field.getStyle()).contains("red");
            WhiteboardApp.applyLobbyDisplayNameFieldStyle(field, true);
            assertThat(field.getStyle()).contains("green");
            latch.countDown();
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void buildLobbyRoot_disablesJoinUntilDisplayNameEntered() throws Exception {
        WhiteboardApp app = new WhiteboardApp();
        CountDownLatch buildLatch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Method build = WhiteboardApp.class.getDeclaredMethod("buildLobbyRoot");
                build.setAccessible(true);
                build.invoke(app);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            buildLatch.countDown();
        });
        assertThat(buildLatch.await(5, TimeUnit.SECONDS)).isTrue();

        var displayNameFieldField = WhiteboardApp.class.getDeclaredField("displayNameField");
        displayNameFieldField.setAccessible(true);
        TextField displayNameField = (TextField) displayNameFieldField.get(app);

        var createBtnField = WhiteboardApp.class.getDeclaredField("lobbyCreateRoomBtn");
        createBtnField.setAccessible(true);
        Button lobbyCreateRoomBtn = (Button) createBtnField.get(app);

        CountDownLatch assertLatch = new CountDownLatch(1);
        Platform.runLater(() -> {
            assertThat(displayNameField.getStyle()).contains("red");
            assertThat(lobbyCreateRoomBtn.isDisabled()).isTrue();
            displayNameField.setText("Alice");
            assertThat(lobbyCreateRoomBtn.isDisabled()).isFalse();
            assertThat(displayNameField.getStyle()).contains("green");
            assertLatch.countDown();
        });
        assertThat(assertLatch.await(5, TimeUnit.SECONDS)).isTrue();
    }
}
