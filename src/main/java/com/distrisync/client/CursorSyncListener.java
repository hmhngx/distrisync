package com.distrisync.client;

/**
 * Receives peer multiplayer cursor position updates relayed over TCP ({@code CURSOR_SYNC}).
 */
@FunctionalInterface
public interface CursorSyncListener {

    /**
     * @param clientId   session id of the peer whose cursor moved
     * @param authorName human-readable display name of the peer
     * @param x          canvas X coordinate
     * @param y          canvas Y coordinate
     */
    void onCursorSync(String clientId, String authorName, double x, double y);
}
