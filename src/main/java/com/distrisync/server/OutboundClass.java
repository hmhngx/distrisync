package com.distrisync.server;

/**
 * Priority class for outbound TCP frames when the per-session write queue is full.
 */
enum OutboundClass {
    /** Loss is acceptable (e.g. PONG, lobby fan-out, in-progress shape updates). */
    EPHEMERAL,
    /** Must be delivered or the session is disconnected to protect server memory. */
    CRITICAL
}
