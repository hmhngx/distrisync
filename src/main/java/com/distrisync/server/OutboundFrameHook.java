package com.distrisync.server;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;

/**
 * Delivers one encoded outbound TCP frame to a session (optionally via {@link NioServer#safeEnqueue}).
 */
@FunctionalInterface
interface OutboundFrameHook {
    void accept(ClientSession session, ByteBuffer frame, SelectionKey key);
}
