package com.distrisync.client;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class WhiteboardAppOptimisticUITest {

    @BeforeAll
    static void initJavaFxToolkit() {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException ignored) {
            // Toolkit already started by another test class in the same JVM.
        }
    }

    @Test
    void testDeleteConfirmationImmediatelyDisablesRoomRow() throws Exception {
        Label roomName = new Label("Room-1");
        Button joinBtn = new Button("Join");
        Button deleteBtn = new Button("Delete");

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            WhiteboardApp.applyLobbyRoomDeletingState("Room-1", roomName, joinBtn, deleteBtn);
            latch.countDown();
        });

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(joinBtn.isDisabled()).isTrue();
        assertThat(deleteBtn.isDisabled()).isTrue();
        assertThat(roomName.getText()).isEqualTo("Room-1 (Deleting...)");
        assertThat(roomName.getOpacity()).isEqualTo(0.5);
    }
}
