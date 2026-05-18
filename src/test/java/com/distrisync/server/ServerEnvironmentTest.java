package com.distrisync.server;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServerEnvironmentTest {

    @Test
    void resolveRedisUri_prefersExplicitUri() {
        Optional<String> uri = ServerEnvironment.resolveRedisUri(Map.of(
                "DISTRISYNC_REDIS_URI", "redis://custom:6380",
                "REDIS_HOST", "ignored",
                "REDIS_PORT", "6379"));

        assertThat(uri).contains("redis://custom:6380");
    }

    @Test
    void resolveRedisUri_buildsFromHostAndPort() {
        Optional<String> uri = ServerEnvironment.resolveRedisUri(Map.of(
                "REDIS_HOST", "redis",
                "REDIS_PORT", "6379"));

        assertThat(uri).contains("redis://redis:6379");
    }

    @Test
    void resolveRedisUri_defaultsPortWhenHostOnly() {
        Optional<String> uri = ServerEnvironment.resolveRedisUri(Map.of("REDIS_HOST", "redis"));

        assertThat(uri).contains("redis://redis:6379");
    }

    @Test
    void resolveRedisUri_emptyWhenNoRedisConfig() {
        assertThat(ServerEnvironment.resolveRedisUri(Map.of())).isEmpty();
    }

    @Test
    void resolveRedisUri_rejectsInvalidPort() {
        assertThatThrownBy(() -> ServerEnvironment.resolveRedisUri(Map.of(
                "REDIS_HOST", "redis",
                "REDIS_PORT", "not-a-port")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REDIS_PORT");
    }

    @Test
    void resolveNodeId_prefersNodeId() {
        assertThat(ServerEnvironment.resolveNodeId(Map.of(
                "NODE_ID", "node-a",
                "DISTRISYNC_NODE_ID", "other")))
                .isEqualTo("node-a");
    }

    @Test
    void resolveNodeId_fallsBackToDistrisyncNodeId() {
        assertThat(ServerEnvironment.resolveNodeId(Map.of("DISTRISYNC_NODE_ID", "node-b")))
                .isEqualTo("node-b");
    }

    @Test
    void resolveNodeId_generatesUuidWhenUnset() {
        String a = ServerEnvironment.resolveNodeId(Map.of());
        String b = ServerEnvironment.resolveNodeId(Map.of());

        assertThat(a).isNotBlank();
        assertThat(b).isNotBlank();
        assertThat(a).isNotEqualTo(b);
    }
}
