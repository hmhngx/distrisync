package com.distrisync.protocol;

/**
 * Zero-allocation bitmask RBAC for the NIO hot path and JavaFX client UI.
 *
 * <p>Permission checks are a single {@code int} AND — O(1) with no heap allocation.
 */
public final class RoomPermissions {

    public static final int PERM_DRAW = 1 << 0;
    public static final int PERM_SPEAK = 1 << 1;
    public static final int PERM_MANAGE_USERS = 1 << 2;
    public static final int PERM_DELETE_ROOM = 1 << 3;
    public static final int PERM_MANAGE_ROOM = 1 << 4;
    public static final int PERM_MANAGE_MEDIA = 1 << 5;

    /** All permissions (draw, speak, manage users, delete room, manage room settings, manage media). */
    public static final int OWNER = PERM_DRAW | PERM_SPEAK | PERM_MANAGE_USERS | PERM_DELETE_ROOM
            | PERM_MANAGE_ROOM | PERM_MANAGE_MEDIA;

    /** Draw and speak only. */
    public static final int MEMBER = PERM_DRAW | PERM_SPEAK;

    /** Lobby / no room capabilities. */
    public static final int SPECTATOR = 0;

    private RoomPermissions() {}

    public static boolean canDraw(int perms) {
        return (perms & PERM_DRAW) != 0;
    }

    public static boolean canSpeak(int perms) {
        return (perms & PERM_SPEAK) != 0;
    }

    public static boolean canManageUsers(int perms) {
        return (perms & PERM_MANAGE_USERS) != 0;
    }

    public static boolean canDeleteRoom(int perms) {
        return (perms & PERM_DELETE_ROOM) != 0;
    }

    public static boolean canManageRoom(int perms) {
        return (perms & PERM_MANAGE_ROOM) != 0;
    }

    public static boolean canManageMedia(int perms) {
        return (perms & PERM_MANAGE_MEDIA) != 0;
    }
}
