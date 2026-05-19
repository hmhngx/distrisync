package com.distrisync.client;

import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.RoomPermissions;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Moderation context menus on {@link CollaborationRoster} rows when peers join
 * after {@link CollaborationRoster#setModerationEnabled(boolean)}.
 */
class CollaborationRosterModerationTest {

    private static final String LOCAL_ID = "local-client";
    private static final String REMOTE_ID = "remote-peer";
    private static final String BOARD_ID = MessageCodec.DEFAULT_INITIAL_BOARD_ID;

    @BeforeAll
    static void initJavaFxToolkit() {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException ignored) {
            // Toolkit already started by another test class in the same JVM.
        }
    }

    @Test
    void lateJoiningPeer_hasEnabledModerationMenuWhenAdminEnabledBeforeJoin() throws Exception {
        ParticipantManager manager = new ParticipantManager();
        CollaborationRoster roster = new CollaborationRoster();

        Platform.runLater(() -> {
            roster.bindTo(manager);
            roster.setLocalClientId(LOCAL_ID);
            roster.setModerationEnabled(true);
            manager.putParticipant(LOCAL_ID, "Local");
            manager.putParticipant(REMOTE_ID, "Remote");
            manager.setCurrentBoardId(LOCAL_ID, BOARD_ID);
            manager.setCurrentBoardId(REMOTE_ID, BOARD_ID);
        });

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            MenuItem[] items = contextMenuItemsFor(roster, REMOTE_ID);
            assertThat(items).hasSize(2);
            assertThat(items[0].getText()).isEqualTo("Kick from Room");
            assertThat(items[1].getText()).isEqualTo("Revoke Mic Access");
            assertThat(items[0].isDisable()).isFalse();
            assertThat(items[1].isDisable()).isFalse();
            assertThat(moderationButtonFor(roster, REMOTE_ID).isVisible()).isTrue();
            assertThat(moderationButtonFor(roster, LOCAL_ID).isVisible()).isFalse();
        });
    }

    @Test
    void lateJoiningPeer_hasDisabledModerationMenuForNonAdmin() throws Exception {
        ParticipantManager manager = new ParticipantManager();
        CollaborationRoster roster = new CollaborationRoster();

        Platform.runLater(() -> {
            roster.bindTo(manager);
            roster.setLocalClientId(LOCAL_ID);
            roster.setModerationEnabled(false);
            manager.putParticipant(LOCAL_ID, "Local");
            manager.putParticipant(REMOTE_ID, "Remote");
            manager.setCurrentBoardId(LOCAL_ID, BOARD_ID);
            manager.setCurrentBoardId(REMOTE_ID, BOARD_ID);
        });

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            MenuItem[] items = contextMenuItemsFor(roster, REMOTE_ID);
            assertThat(items[0].isDisable()).isTrue();
            assertThat(items[1].isDisable()).isTrue();
            assertThat(moderationButtonFor(roster, REMOTE_ID).isVisible()).isFalse();
        });
    }

    @Test
    void ownerPermissions_showCrownOnRemoteRow() throws Exception {
        ParticipantManager manager = new ParticipantManager();
        CollaborationRoster roster = new CollaborationRoster();

        Platform.runLater(() -> {
            roster.bindTo(manager);
            roster.setLocalClientId(LOCAL_ID);
            manager.putParticipant(REMOTE_ID, "Admin");
            manager.updatePermissions(REMOTE_ID, RoomPermissions.OWNER);
            manager.setCurrentBoardId(REMOTE_ID, BOARD_ID);
        });

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(crownLabelFor(roster, REMOTE_ID).isVisible()).isTrue());
    }

    @Test
    void speakMenuItem_switchesBetweenRevokeAndGrantOnPermissionChange() throws Exception {
        ParticipantManager manager = new ParticipantManager();
        CollaborationRoster roster = new CollaborationRoster();

        Platform.runLater(() -> {
            roster.bindTo(manager);
            roster.setLocalClientId(LOCAL_ID);
            roster.setModerationEnabled(true);
            manager.putParticipant(REMOTE_ID, "Remote");
            manager.updatePermissions(REMOTE_ID, RoomPermissions.MEMBER);
            manager.setCurrentBoardId(REMOTE_ID, BOARD_ID);
        });

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            MenuItem[] items = contextMenuItemsFor(roster, REMOTE_ID);
            assertThat(items[1].getText()).isEqualTo("Revoke Mic Access");
        });

        Platform.runLater(() -> manager.updatePermissions(REMOTE_ID, RoomPermissions.PERM_DRAW));

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            MenuItem[] items = contextMenuItemsFor(roster, REMOTE_ID);
            assertThat(items[1].getText()).isEqualTo("Grant Mic Access");
        });

        Platform.runLater(() -> manager.updatePermissions(REMOTE_ID, RoomPermissions.MEMBER));

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            MenuItem[] items = contextMenuItemsFor(roster, REMOTE_ID);
            assertThat(items[1].getText()).isEqualTo("Revoke Mic Access");
        });
    }

    @Test
    void setModerationEnabled_rebuildsRowsWithUpdatedMenuState() throws Exception {
        ParticipantManager manager = new ParticipantManager();
        CollaborationRoster roster = new CollaborationRoster();

        Platform.runLater(() -> {
            roster.bindTo(manager);
            roster.setLocalClientId(LOCAL_ID);
            roster.setModerationEnabled(false);
            manager.putParticipant(LOCAL_ID, "Local");
            manager.putParticipant(REMOTE_ID, "Remote");
            manager.setCurrentBoardId(LOCAL_ID, BOARD_ID);
            manager.setCurrentBoardId(REMOTE_ID, BOARD_ID);
        });

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            MenuItem[] items = contextMenuItemsFor(roster, REMOTE_ID);
            assertThat(items[0].isDisable()).isTrue();
        });

        Platform.runLater(() -> roster.setModerationEnabled(true));

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            MenuItem[] items = contextMenuItemsFor(roster, REMOTE_ID);
            assertThat(items[0].isDisable()).isFalse();
            assertThat(items[1].isDisable()).isFalse();
            assertThat(moderationButtonFor(roster, REMOTE_ID).isVisible()).isTrue();
        });
    }

    @SuppressWarnings("unchecked")
    private static Label crownLabelFor(CollaborationRoster roster, String clientId) throws Exception {
        Object row = participantRowFor(roster, clientId);
        Field crownField = row.getClass().getDeclaredField("crownLabel");
        crownField.setAccessible(true);
        return (Label) crownField.get(row);
    }

    @SuppressWarnings("unchecked")
    private static Button moderationButtonFor(CollaborationRoster roster, String clientId)
            throws Exception {
        Object row = participantRowFor(roster, clientId);
        Field buttonField = row.getClass().getDeclaredField("modButton");
        buttonField.setAccessible(true);
        return (Button) buttonField.get(row);
    }

    @SuppressWarnings("unchecked")
    private static Object participantRowFor(CollaborationRoster roster, String clientId)
            throws Exception {
        Field rowsField = CollaborationRoster.class.getDeclaredField("rowsByClientId");
        rowsField.setAccessible(true);
        Map<String, Object> rows = (Map<String, Object>) rowsField.get(roster);
        Object row = rows.get(clientId);
        assertThat(row).isNotNull();
        return row;
    }

    @SuppressWarnings("unchecked")
    private static MenuItem[] contextMenuItemsFor(CollaborationRoster roster, String clientId)
            throws Exception {
        Object row = participantRowFor(roster, clientId);
        Field menuField = row.getClass().getDeclaredField("contextMenu");
        menuField.setAccessible(true);
        ContextMenu menu = (ContextMenu) menuField.get(row);
        return menu.getItems().toArray(new MenuItem[0]);
    }
}
