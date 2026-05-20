package com.distrisync.client;

import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.RoomPermissions;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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
 * Zero-trust UI degradation for {@code ROLE_UPDATE} speak revocation and {@code SESSION_REVOKED}.
 */
class WhiteboardAppModerationUiTest extends ApplicationTest {

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
        invokeWireParticipantHud(app, networkClient.getParticipantManager());
        WhiteboardAppTestFxSupport.showCanvasScene(stage, canvasScene);
    }

    @Test
    void applyRoleUpdate_lostSpeak_mutesHardwareAndDisablesMicButton() {
        ParticipantManager manager = networkClient.getParticipantManager();
        manager.setLocalPermissions(RoomPermissions.MEMBER);
        AudioEngine audio = networkClient.getAudioEngine();
        audio.setMicMuted(false);

        MessageCodec.RoleUpdatePayload payload = new MessageCodec.RoleUpdatePayload(
                networkClient.getClientId(),
                RoomPermissions.PERM_DRAW,
                null);

        interact(() -> invokeApplyRoleUpdate(app, payload));

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(audio.isMicMuted()).isTrue();
            Button micBtn = lookup("#" + WhiteboardApp.MIC_TOGGLE_BUTTON_ID).queryButton();
            assertThat(micBtn.isDisabled()).isTrue();
            Label toastLabel = lookup(".lobby-status-disconnected").queryAs(Label.class);
            assertThat(toastLabel.getText()).isEqualTo("An Admin has revoked your microphone access.");
        });
    }

    @Test
    void applyRoleUpdate_remoteRevokeSpeak_showsSlashedMicWithoutVoiceState() {
        String remoteId = "remote-peer";
        ParticipantManager manager = networkClient.getParticipantManager();

        interact(() -> {
            manager.putParticipant(remoteId, "Remote Peer");
            Participant participant = manager.get(remoteId);
            assertThat(participant).isNotNull();
            participant.setPermissions(RoomPermissions.MEMBER);
            participant.setMuted(false);
        });

        MessageCodec.RoleUpdatePayload payload = new MessageCodec.RoleUpdatePayload(
                remoteId,
                RoomPermissions.PERM_DRAW,
                null);

        interact(() -> invokeApplyRoleUpdate(app, payload));

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            Participant participant = manager.get(remoteId);
            assertThat(participant).isNotNull();
            assertThat(RoomPermissions.canSpeak(participant.getPermissions())).isFalse();
            assertThat(participant.isMuted()).isFalse();
            Node slashedMic = lookup(".roster-hw-mute-icon").query();
            verifyThat(slashedMic, isVisible());
        });
    }

    @Test
    void onSessionRevoked_showsDeathOverlayWithReturnToLobby() {
        interact(() -> invokeOnSessionRevoked(app, "policy violation"));

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            verifyThat(lookup(".session-revoked-overlay"), isVisible());
            Label title = lookup(".session-revoked-title").queryAs(Label.class);
            assertThat(title.getText()).isEqualTo("Session Revoked");
            Label subtitle = lookup(".session-revoked-subtitle").queryAs(Label.class);
            assertThat(subtitle.getText())
                    .isEqualTo("You have been removed from this room by an administrator.");
            Label reason = lookup(".session-revoked-reason").queryAs(Label.class);
            assertThat(reason.getText()).isEqualTo("policy violation");
            Button returnBtn = lookup(".session-revoked-return-btn").queryButton();
            assertThat(returnBtn.getText()).isEqualTo("Return to Lobby");
        });

        assertThat(networkClient.getAudioEngine().isClosed()).isTrue();
    }

    @Test
    void finishSessionRevokedReturn_reinitializesAudioEngine() {
        interact(() -> {
            invokeOnSessionRevoked(app, "");
            invokeFinishSessionRevokedReturn(app);
        });

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(networkClient.getAudioEngine().isClosed()).isFalse();
        });
    }

    private static void invokeApplyRoleUpdate(WhiteboardApp app, MessageCodec.RoleUpdatePayload payload) {
        try {
            Method m = WhiteboardApp.class.getDeclaredMethod(
                    "applyRoleUpdate", MessageCodec.RoleUpdatePayload.class);
            m.setAccessible(true);
            m.invoke(app, payload);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("applyRoleUpdate", ex);
        }
    }

    private static void invokeOnSessionRevoked(WhiteboardApp app, String reason) {
        try {
            Method m = WhiteboardApp.class.getDeclaredMethod("onSessionRevoked", String.class);
            m.setAccessible(true);
            m.invoke(app, reason);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("onSessionRevoked", ex);
        }
    }

    private static void invokeFinishSessionRevokedReturn(WhiteboardApp app) {
        try {
            Method m = WhiteboardApp.class.getDeclaredMethod("finishSessionRevokedReturn");
            m.setAccessible(true);
            m.invoke(app);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("finishSessionRevokedReturn", ex);
        }
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
