package com.distrisync.client;

import com.distrisync.protocol.RoomPermissions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WhiteboardAppPermissionsTest {

    @Test
    void lostSpeakPermission_detectsRevocation() {
        assertThat(WhiteboardApp.lostSpeakPermission(RoomPermissions.MEMBER, RoomPermissions.PERM_DRAW))
                .isTrue();
        assertThat(WhiteboardApp.lostSpeakPermission(RoomPermissions.OWNER, RoomPermissions.MEMBER))
                .isFalse();
        assertThat(WhiteboardApp.lostSpeakPermission(RoomPermissions.SPECTATOR, RoomPermissions.SPECTATOR))
                .isFalse();
    }
}
