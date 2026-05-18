package com.distrisync.server;

/**
 * Outcome of attempting to enqueue an outbound TCP frame.
 */
enum EnqueueResult {
    ENQUEUED,
    DROPPED,
    OVERFLOW_DISCONNECT
}
