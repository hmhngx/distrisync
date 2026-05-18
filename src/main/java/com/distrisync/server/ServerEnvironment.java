package com.distrisync.server;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Resolves cluster configuration from process environment variables.
 *
 * <p>Redis URI precedence: {@code DISTRISYNC_REDIS_URI} overrides
 * {@code redis://REDIS_HOST:REDIS_PORT} (port defaults to 6379).
 * Node id: {@code NODE_ID}, then {@code DISTRISYNC_NODE_ID}, else a random UUID.
 */
public final class ServerEnvironment {

    private static final int DEFAULT_REDIS_PORT = 6379;

    private ServerEnvironment() {}

    public static Optional<String> resolveRedisUri() {
        return resolveRedisUri(System::getenv);
    }

    public static String resolveNodeId() {
        return resolveNodeId(System::getenv);
    }

    static Optional<String> resolveRedisUri(Function<String, String> getenv) {
        String explicit = getenv.apply("DISTRISYNC_REDIS_URI");
        if (explicit != null && !explicit.isBlank()) {
            return Optional.of(explicit.trim());
        }

        String host = getenv.apply("REDIS_HOST");
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }

        int port = DEFAULT_REDIS_PORT;
        String portStr = getenv.apply("REDIS_PORT");
        if (portStr != null && !portStr.isBlank()) {
            try {
                port = Integer.parseInt(portStr.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid REDIS_PORT: '" + portStr + "'", e);
            }
        }

        return Optional.of("redis://" + host.trim() + ":" + port);
    }

    static String resolveNodeId(Function<String, String> getenv) {
        String nodeId = getenv.apply("NODE_ID");
        if (nodeId == null || nodeId.isBlank()) {
            nodeId = getenv.apply("DISTRISYNC_NODE_ID");
        }
        if (nodeId != null && !nodeId.isBlank()) {
            return nodeId.trim();
        }
        return UUID.randomUUID().toString();
    }

    /** Test helper: resolves from a fixed map. */
    static Optional<String> resolveRedisUri(Map<String, String> env) {
        return resolveRedisUri(env::get);
    }

    static String resolveNodeId(Map<String, String> env) {
        return resolveNodeId(env::get);
    }
}
