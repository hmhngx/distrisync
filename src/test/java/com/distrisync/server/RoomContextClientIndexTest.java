package com.distrisync.server;

import org.junit.jupiter.api.Test;

import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;

import static org.assertj.core.api.Assertions.assertThat;

class RoomContextClientIndexTest {

    @Test
    void addKey_indexesClientId_removeKeyClearsLookup() {
        RoomContext room = new RoomContext("room-a", null);
        SelectionKey key = stubKey("k1");
        ClientSession session = new ClientSession();
        session.clientId = "client-a";
        key.attach(session);

        room.addKey(key);
        assertThat(room.lookupClientKey("client-a")).isSameAs(key);
        assertThat(room.lookupClientKey("missing")).isNull();

        room.removeKey(key);
        assertThat(room.lookupClientKey("client-a")).isNull();
        assertThat(room.getActiveClientCount()).isZero();
    }

    @Test
    void removeKey_onlyRemovesMatchingKeyForClientId() {
        RoomContext room = new RoomContext("room-a", null);
        SelectionKey key1 = stubKey("k1");
        SelectionKey key2 = stubKey("k2");
        ClientSession session1 = new ClientSession();
        session1.clientId = "shared-id";
        ClientSession session2 = new ClientSession();
        session2.clientId = "shared-id";
        key1.attach(session1);
        key2.attach(session2);

        room.addKey(key1);
        room.addKey(key2);
        room.removeKey(key1);

        assertThat(room.lookupClientKey("shared-id")).isSameAs(key2);
    }

    @Test
    void addKey_skipsBlankClientId() {
        RoomContext room = new RoomContext("room-a", null);
        SelectionKey key = stubKey("k1");
        ClientSession session = new ClientSession();
        session.clientId = "";
        key.attach(session);

        room.addKey(key);
        assertThat(room.lookupClientKey("")).isNull();
    }

    private static SelectionKey stubKey(String label) {
        return new SelectionKey() {
            @Override public SelectableChannel channel()            { return null; }
            @Override public Selector          selector()           { return null; }
            @Override public boolean           isValid()            { return true; }
            @Override public void              cancel()             {}
            @Override public int               interestOps()        { return 0; }
            @Override public SelectionKey      interestOps(int ops) { return this; }
            @Override public int               readyOps()           { return 0; }
            @Override public String            toString()           { return "StubKey[" + label + "]"; }
        };
    }
}
