package com.distrisync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.Pipe;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link NioServer#selectHostCandidate(RoomContext)}.
 */
class HostElectionTest {

    @TempDir
    Path tempDir;

    @Test
    void selectHostCandidate_oldestConnectedAtMillisWins() throws Exception {
        WalManager wal = new WalManager(tempDir);
        RoomContext room = new RoomContext("election-room", wal);
        Selector sel = Selector.open();
        try {
            attach(room, sel, "client-c", 3000L);
            attach(room, sel, "client-a", 1000L);
            attach(room, sel, "client-b", 2000L);

            ClientSession winner = NioServer.selectHostCandidate(room);
            assertThat(winner).isNotNull();
            assertThat(winner.clientId).isEqualTo("client-a");
        } finally {
            sel.close();
        }
    }

    @Test
    void selectHostCandidate_equalTimestamps_lexicographicClientIdTieBreak() throws Exception {
        WalManager wal = new WalManager(tempDir);
        RoomContext room = new RoomContext("tie-room", wal);
        Selector sel = Selector.open();
        try {
            long ts = 5000L;
            attach(room, sel, "client-z", ts);
            attach(room, sel, "client-m", ts);
            attach(room, sel, "client-a", ts);

            ClientSession winner = NioServer.selectHostCandidate(room);
            assertThat(winner).isNotNull();
            assertThat(winner.clientId).isEqualTo("client-a");
        } finally {
            sel.close();
        }
    }

    private static void attach(RoomContext room, Selector sel, String clientId, long connectedAtMillis)
            throws Exception {
        Pipe pipe = Pipe.open();
        pipe.source().configureBlocking(false);
        SelectionKey key = pipe.source().register(sel, SelectionKey.OP_READ);
        ClientSession session = new ClientSession();
        session.clientId = clientId;
        session.connectedAtMillis = connectedAtMillis;
        key.attach(session);
        room.addKey(key);
    }
}
