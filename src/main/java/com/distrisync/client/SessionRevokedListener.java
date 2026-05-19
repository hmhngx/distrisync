package com.distrisync.client;

/**
 * Receives {@code SESSION_REVOKED} when moderation ends the TCP session.
 *
 * <p>Callbacks run on the {@code distrisync-read} thread. UI code should use
 * {@link javafx.application.Platform#runLater}.
 */
@FunctionalInterface
public interface SessionRevokedListener {

    /**
     * @param reason human-readable reason from the server; may be blank
     */
    void onSessionRevoked(String reason);
}
