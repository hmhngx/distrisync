package com.distrisync.server;

import com.distrisync.model.Line;
import com.distrisync.model.Shape;
import com.distrisync.protocol.Message;
import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.MessageType;
import com.distrisync.protocol.PartialMessageException;
import com.distrisync.server.backplane.BackplaneEnvelope;
import com.distrisync.server.backplane.BackplaneEnvelopeCodec;
import com.distrisync.server.backplane.RedisBackplanePublisher;
import com.distrisync.server.backplane.RedisPublishClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;

@ExtendWith(MockitoExtension.class)
class NioServerDistributedHydrationTest {

    private static final String ROOM_ID = "room-hydrate";
    private static final String BOARD_ID = MessageCodec.DEFAULT_INITIAL_BOARD_ID;

    @Mock
    private RedisPublishClient redisClient;

    private RedisBackplanePublisher publisherA;

    @AfterEach
    void tearDown() {
        if (publisherA != null) {
            publisherA.close();
        }
    }

    @Test
    void testDistributedHydrationProtocol(@TempDir Path walDir) throws Exception {
        doNothing().when(redisClient).close();

        List<byte[]> publishedBodies = new CopyOnWriteArrayList<>();
        CountDownLatch published = new CountDownLatch(1);
        doAnswer(invocation -> {
            publishedBodies.add(invocation.getArgument(1));
            published.countDown();
            return 1L;
        }).when(redisClient).publish(anyString(), any(byte[].class));

        publisherA = new RedisBackplanePublisher(
                "node-a",
                redisClient,
                Executors.newSingleThreadExecutor(),
                true);

        try (WalManager walManager = new WalManager(walDir)) {
            RoomManager roomManagerA = new RoomManager(walManager);
            NioServer nodeA = new NioServer(0, roomManagerA, walManager, publisherA, null, "node-a");

            Line shape = Line.create("#FF0000", 0, 0, 100, 100, 2.0, "Alice", "client-a");
            roomManagerA.getOrCreateRoom(ROOM_ID).getBoard(BOARD_ID).applyMutation(shape);

            RoomManager roomManagerB = new RoomManager(walManager);
            NioServer nodeB = new NioServer(0, roomManagerB, walManager, null, null, "node-b");

            Message requestMsg = new Message(MessageType.STATE_REQUEST, "{}");
            BackplaneEnvelope requestEnvelope = new BackplaneEnvelope(
                    "evt-state-request",
                    "node-b",
                    ROOM_ID,
                    BOARD_ID,
                    MessageCodec.encode(requestMsg));

            nodeA.fanoutRemoteEnvelope(requestEnvelope);

            assertThat(published.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(publishedBodies).isNotEmpty();

            BackplaneEnvelope snapshotEnvelope = decodeFirstSnapshotEnvelope(publishedBodies);
            assertThat(snapshotEnvelope.originNodeId()).isEqualTo("node-a");
            assertThat(snapshotEnvelope.boardId()).isEqualTo(BOARD_ID);

            Message snapshotMsg = decodeMessage(snapshotEnvelope);
            assertThat(snapshotMsg.type()).isEqualTo(MessageType.STATE_SNAPSHOT);
            List<Shape> snapshotShapes = ShapeCodec.decodeSnapshot(snapshotMsg.payload());
            assertThat(snapshotShapes)
                    .hasSize(1)
                    .extracting(Shape::objectId)
                    .containsExactly(shape.objectId());

            nodeB.fanoutRemoteEnvelope(snapshotEnvelope);

            List<Shape> hotSnapshot = roomManagerA.getRoom(ROOM_ID).getBoard(BOARD_ID).snapshot();
            List<Shape> coldSnapshot = roomManagerB.getRoom(ROOM_ID).getBoard(BOARD_ID).snapshot();
            assertThat(coldSnapshot).hasSize(hotSnapshot.size());
            assertThat(coldSnapshot.get(0).objectId()).isEqualTo(hotSnapshot.get(0).objectId());
            assertThat(coldSnapshot.get(0).timestamp()).isEqualTo(hotSnapshot.get(0).timestamp());

            List<Message> walRecords = walManager.recover(ROOM_ID, BOARD_ID);
            assertThat(walRecords).hasSize(1);
            assertThat(walRecords.get(0).type()).isEqualTo(MessageType.MUTATION_BATCH);

            assertThat(nodeB.isEventIdRecorded(snapshotEnvelope.eventId())).isTrue();

            nodeB.fanoutRemoteEnvelope(snapshotEnvelope);
            assertThat(roomManagerB.getRoom(ROOM_ID).getBoard(BOARD_ID).snapshot()).hasSize(1);
            assertThat(walManager.recover(ROOM_ID, BOARD_ID)).hasSize(1);
        }
    }

    private static BackplaneEnvelope decodeFirstSnapshotEnvelope(List<byte[]> publishedBodies)
            throws PartialMessageException {
        List<BackplaneEnvelope> snapshots = new ArrayList<>();
        for (byte[] body : publishedBodies) {
            BackplaneEnvelope envelope = BackplaneEnvelopeCodec.decode(body);
            Message msg = decodeMessage(envelope);
            if (msg.type() == MessageType.STATE_SNAPSHOT) {
                snapshots.add(envelope);
            }
        }
        assertThat(snapshots).isNotEmpty();
        return snapshots.get(0);
    }

    private static Message decodeMessage(BackplaneEnvelope envelope) throws PartialMessageException {
        ByteBuffer buf = envelope.serializedPayload().duplicate();
        return MessageCodec.decode(buf);
    }
}
