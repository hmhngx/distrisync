package com.distrisync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WalManagerSyncTest {

    @TempDir
    Path tempDir;

    @Test
    void testDeleteRoomFiles_isStrictlySynchronous() throws IOException {
        String roomId = "Room Sync";
        String sanitizedRoomId = WalManager.sanitize(roomId);
        Path walPath = tempDir.resolve(sanitizedRoomId + "_Board-1.wal");
        Files.writeString(walPath, "mock wal bytes");

        try (WalManager wal = new WalManager(tempDir)) {
            assertThat(wal.getPersistedRoomIds()).contains(sanitizedRoomId);

            wal.deleteRoomFiles(roomId);

            assertThat(wal.getPersistedRoomIds().contains(sanitizedRoomId)).isFalse();
            assertThat(Files.exists(walPath)).isFalse();
        }
    }
}
