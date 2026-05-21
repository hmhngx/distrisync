package com.distrisync.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoomPermissionsTest {

    @Test
    void roleConstants_matchBitmaskComposition() {
        assertThat(RoomPermissions.OWNER).isEqualTo(63);
        assertThat(RoomPermissions.MEMBER).isEqualTo(3);
        assertThat(RoomPermissions.SPECTATOR).isZero();
        assertThat(RoomPermissions.OWNER)
                .isEqualTo(RoomPermissions.PERM_DRAW
                        | RoomPermissions.PERM_SPEAK
                        | RoomPermissions.PERM_MANAGE_USERS
                        | RoomPermissions.PERM_DELETE_ROOM
                        | RoomPermissions.PERM_MANAGE_ROOM
                        | RoomPermissions.PERM_MANAGE_MEDIA);
    }

    @Test
    void canManageRoom_reflectsManageRoomBit() {
        assertThat(RoomPermissions.canManageRoom(RoomPermissions.OWNER)).isTrue();
        assertThat(RoomPermissions.canManageRoom(RoomPermissions.MEMBER)).isFalse();
        assertThat(RoomPermissions.canManageRoom(RoomPermissions.PERM_MANAGE_ROOM)).isTrue();
    }

    @Test
    void canManageMedia_reflectsManageMediaBit() {
        assertThat(RoomPermissions.canManageMedia(RoomPermissions.OWNER)).isTrue();
        assertThat(RoomPermissions.canManageMedia(RoomPermissions.MEMBER)).isFalse();
        assertThat(RoomPermissions.canManageMedia(RoomPermissions.PERM_MANAGE_MEDIA)).isTrue();
    }

    @Test
    void helpers_reflectIndividualBits() {
        assertThat(RoomPermissions.canDraw(RoomPermissions.MEMBER)).isTrue();
        assertThat(RoomPermissions.canSpeak(RoomPermissions.MEMBER)).isTrue();
        assertThat(RoomPermissions.canManageUsers(RoomPermissions.MEMBER)).isFalse();
        assertThat(RoomPermissions.canDeleteRoom(RoomPermissions.MEMBER)).isFalse();

        assertThat(RoomPermissions.canDraw(RoomPermissions.OWNER)).isTrue();
        assertThat(RoomPermissions.canManageUsers(RoomPermissions.OWNER)).isTrue();
        assertThat(RoomPermissions.canDeleteRoom(RoomPermissions.OWNER)).isTrue();

        assertThat(RoomPermissions.canDraw(RoomPermissions.SPECTATOR)).isFalse();
    }
}
