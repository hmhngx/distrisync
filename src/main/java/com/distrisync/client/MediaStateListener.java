package com.distrisync.client;

import com.distrisync.protocol.MessageCodec;

/**
 * Receives authoritative room-global media snapshots ({@code MEDIA_STATE_UPDATE}).
 *
 * <p>Callbacks run on the {@code distrisync-read} thread. UI code should use
 * {@link javafx.application.Platform#runLater}.
 */
@FunctionalInterface
public interface MediaStateListener {

    void onMediaStateUpdate(MessageCodec.MediaStatePayload state);
}
