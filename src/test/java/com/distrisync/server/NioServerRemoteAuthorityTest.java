package com.distrisync.server;

import com.distrisync.model.Line;
import com.distrisync.model.Shape;
import com.distrisync.protocol.Message;
import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.MessageType;
import com.distrisync.server.backplane.BackplaneEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NioServerRemoteAuthorityTest {

    @Test
    void testRemoteEnvelopeAppliesToLocalState(@TempDir Path walDir) throws IOException {
        try (WalManager walManager = new WalManager(walDir)) {
            RoomManager roomManager = new RoomManager(walManager);
            NioServer nodeB = new NioServer(0, roomManager, walManager, null, null, "node-b");

            Line shape = Line.create("#FF0000", 0, 0, 100, 100, 2.0, "Alice", "client-a");
            Message msg = new Message(MessageType.MUTATION, ShapeCodec.encodeMutation(shape));
            ByteBuffer frame = MessageCodec.encode(msg);
            BackplaneEnvelope envelope = new BackplaneEnvelope(
                    "evt-from-node-a",
                    "node-a",
                    "room-hydrate",
                    MessageCodec.DEFAULT_INITIAL_BOARD_ID,
                    frame);

            nodeB.fanoutRemoteEnvelope(envelope);

            RoomContext room = roomManager.getRoom("room-hydrate");
            assertThat(room).isNotNull();

            List<Shape> snapshot = room.getBoard(MessageCodec.DEFAULT_INITIAL_BOARD_ID).snapshot();
            assertThat(snapshot)
                    .hasSize(1)
                    .extracting(Shape::objectId)
                    .containsExactly(shape.objectId());

            List<Message> walRecords = walManager.recover("room-hydrate", MessageCodec.DEFAULT_INITIAL_BOARD_ID);
            assertThat(walRecords).hasSize(1);
            assertThat(walRecords.get(0).type()).isEqualTo(MessageType.MUTATION);

            assertThat(nodeB.isEventIdRecorded("evt-from-node-a")).isTrue();

            nodeB.fanoutRemoteEnvelope(envelope);

            assertThat(room.getBoard(MessageCodec.DEFAULT_INITIAL_BOARD_ID).snapshot()).hasSize(1);
            assertThat(walManager.recover("room-hydrate", MessageCodec.DEFAULT_INITIAL_BOARD_ID)).hasSize(1);
        }
    }
}
