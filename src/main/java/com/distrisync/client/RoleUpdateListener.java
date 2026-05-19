package com.distrisync.client;

import com.distrisync.protocol.MessageCodec;

/**
 * Receives {@code ROLE_UPDATE} when room host permissions change (e.g. host migration).
 *
 * <p>Callbacks run on the {@code distrisync-read} thread. UI code should use
 * {@link javafx.application.Platform#runLater}.
 */
@FunctionalInterface
public interface RoleUpdateListener {

    void onRoleUpdate(MessageCodec.RoleUpdatePayload payload);
}
