package com.distrisync.server;

import com.distrisync.model.Circle;
import com.distrisync.model.Line;
import com.distrisync.model.Shape;
import com.distrisync.protocol.Message;
import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.MessageType;
import com.distrisync.protocol.PartialMessageException;
import com.distrisync.protocol.RoomPermissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for server-side object authority (IDOR hardening).
 */
class NioServerObjectAuthorityTest {

    private static final String HOST = "127.0.0.1";

    @TempDir
    Path tempDir;

    private NioServer server;
    private Thread serverThread;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (server != null) {
            server.stop();
        }
        if (serverThread != null) {
            serverThread.interrupt();
            serverThread.join(10_000);
        }
        server = null;
        serverThread = null;
    }

    @Test
    void memberCannotClearAnotherUsersShapes() throws Exception {
        final String roomId = "clear-idor-room";
        final String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;
        RoomManager roomManager = startRoom(roomId, boardId);

        try (SocketChannel owner = connectAndJoin("owner", "owner-client", roomId, boardId);
             SocketChannel member = connectAndJoin("member", "member-client", roomId, boardId)) {

            Line ownerLine = Line.create("#FF0000", 0, 0, 50, 50, 2.0, "owner", "owner-client");
            writeFully(owner, MessageCodec.encode(new Message(
                    MessageType.MUTATION, ShapeCodec.encodeMutation(ownerLine))));
            drainUntilQuiet(owner, ByteBuffer.allocate(256 * 1024), 300, 5_000);

            CanvasStateManager board = board(roomManager, roomId, boardId);
            assertThat(board.snapshot()).hasSize(1);

            writeFully(member, MessageCodec.encodeClearUserShapes("owner-client"));
            drainUntilQuiet(member, ByteBuffer.allocate(256 * 1024), 300, 2_000);

            assertThat(board.snapshot())
                    .as("malicious CLEAR_USER_SHAPES must not remove another user's shapes")
                    .hasSize(1)
                    .extracting(Shape::objectId)
                    .containsExactly(ownerLine.objectId());
        }
    }

    @Test
    void moderatorCanClearAnotherUsersShapes() throws Exception {
        final String roomId = "clear-mod-room";
        final String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;
        RoomManager roomManager = startRoom(roomId, boardId);

        try (SocketChannel owner = connectAndJoin("owner", "owner-client", roomId, boardId);
             SocketChannel victim = connectAndJoin("victim", "victim-client", roomId, boardId)) {

            Line victimLine = Line.create("#00FF00", 1, 1, 40, 40, 2.0, "victim", "victim-client");
            writeFully(victim, MessageCodec.encode(new Message(
                    MessageType.MUTATION, ShapeCodec.encodeMutation(victimLine))));
            drainUntilQuiet(victim, ByteBuffer.allocate(256 * 1024), 300, 5_000);

            assertThat(board(roomManager, roomId, boardId).snapshot()).hasSize(1);

            writeFully(owner, MessageCodec.encodeClearUserShapes("victim-client"));
            drainUntilQuiet(owner, ByteBuffer.allocate(256 * 1024), 300, 5_000);

            assertThat(board(roomManager, roomId, boardId).snapshot()).isEmpty();
        }
    }

    @Test
    void mutationClientIdIsStampedFromSession() throws Exception {
        final String roomId = "mutation-stamp-room";
        final String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;
        RoomManager roomManager = startRoom(roomId, boardId);

        try (SocketChannel owner = connectAndJoin("owner", "owner-client", roomId, boardId);
             SocketChannel member = connectAndJoin("member", "member-client", roomId, boardId)) {

            drainUntilQuiet(owner, ByteBuffer.allocate(256 * 1024), 200, 3_000);

            Circle forged = Circle.create("#0000FF", 5, 5, 8.0, "forged-author", "forged-client-id");
            writeFully(member, MessageCodec.encode(new Message(
                    MessageType.MUTATION, ShapeCodec.encodeMutation(forged))));
            drainUntilQuiet(member, ByteBuffer.allocate(256 * 1024), 300, 5_000);

            List<Shape> shapes = board(roomManager, roomId, boardId).snapshot();
            assertThat(shapes).hasSize(1);
            assertThat(shapes.get(0).clientId())
                    .as("server must stamp MUTATION clientId from TCP session")
                    .isEqualTo("member-client");
            assertThat(shapes.get(0).objectId()).isEqualTo(forged.objectId());
        }
    }

    @Test
    void memberCannotDeleteOthersShape() throws Exception {
        final String roomId = "delete-idor-room";
        final String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;
        RoomManager roomManager = startRoom(roomId, boardId);

        try (SocketChannel owner = connectAndJoin("owner", "owner-client", roomId, boardId);
             SocketChannel member = connectAndJoin("member", "member-client", roomId, boardId)) {

            Line ownerLine = Line.create("#ABCDEF", 0, 0, 30, 30, 2.0, "owner", "owner-client");
            writeFully(owner, MessageCodec.encode(new Message(
                    MessageType.MUTATION, ShapeCodec.encodeMutation(ownerLine))));
            drainUntilQuiet(owner, ByteBuffer.allocate(256 * 1024), 300, 5_000);

            assertThat(board(roomManager, roomId, boardId).snapshot()).hasSize(1);

            record ShapeDeletePayload(String shapeId) {}
            String payload = MessageCodec.gson().toJson(new ShapeDeletePayload(ownerLine.objectId().toString()));
            writeFully(member, MessageCodec.encode(new Message(MessageType.SHAPE_DELETE, payload)));
            drainUntilQuiet(member, ByteBuffer.allocate(256 * 1024), 300, 2_000);

            assertThat(board(roomManager, roomId, boardId).snapshot()).hasSize(1);
        }
    }

    @Test
    void memberCanDeleteOwnShape() throws Exception {
        final String roomId = "delete-own-room";
        final String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;
        RoomManager roomManager = startRoom(roomId, boardId);

        try (SocketChannel owner = connectAndJoin("owner", "owner-client", roomId, boardId);
             SocketChannel member = connectAndJoin("member", "member-client", roomId, boardId)) {

            drainUntilQuiet(owner, ByteBuffer.allocate(256 * 1024), 200, 3_000);

            Line memberLine = Line.create("#123456", 2, 2, 20, 20, 2.0, "member", "member-client");
            writeFully(member, MessageCodec.encode(new Message(
                    MessageType.MUTATION, ShapeCodec.encodeMutation(memberLine))));
            drainUntilQuiet(member, ByteBuffer.allocate(256 * 1024), 300, 5_000);

            assertThat(board(roomManager, roomId, boardId).snapshot()).hasSize(1);

            record ShapeDeletePayload(String shapeId) {}
            String payload = MessageCodec.gson().toJson(new ShapeDeletePayload(memberLine.objectId().toString()));
            writeFully(member, MessageCodec.encode(new Message(MessageType.SHAPE_DELETE, payload)));
            drainUntilQuiet(member, ByteBuffer.allocate(256 * 1024), 300, 5_000);

            assertThat(board(roomManager, roomId, boardId).snapshot()).isEmpty();
        }
    }

    @Test
    void memberCannotUndoOthersShape() throws Exception {
        final String roomId = "undo-idor-room";
        final String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;
        RoomManager roomManager = startRoom(roomId, boardId);

        try (SocketChannel owner = connectAndJoin("owner", "owner-client", roomId, boardId);
             SocketChannel member = connectAndJoin("member", "member-client", roomId, boardId)) {

            Line ownerLine = Line.create("#ABCDEF", 0, 0, 30, 30, 2.0, "owner", "owner-client");
            writeFully(owner, MessageCodec.encode(new Message(
                    MessageType.MUTATION, ShapeCodec.encodeMutation(ownerLine))));
            drainUntilQuiet(owner, ByteBuffer.allocate(256 * 1024), 300, 5_000);

            assertThat(board(roomManager, roomId, boardId).snapshot()).hasSize(1);

            record UndoPayload(String shapeId) {}
            writeFully(member, MessageCodec.encodeObject(MessageType.UNDO_REQUEST,
                    new UndoPayload(ownerLine.objectId().toString())));
            drainUntilQuiet(member, ByteBuffer.allocate(256 * 1024), 300, 2_000);

            assertThat(board(roomManager, roomId, boardId).snapshot())
                    .as("malicious UNDO_REQUEST must not remove another user's shape")
                    .hasSize(1)
                    .extracting(Shape::objectId)
                    .containsExactly(ownerLine.objectId());
        }
    }

    @Test
    void memberCanUndoOwnShape() throws Exception {
        final String roomId = "undo-own-room";
        final String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;
        RoomManager roomManager = startRoom(roomId, boardId);

        try (SocketChannel owner = connectAndJoin("owner", "owner-client", roomId, boardId);
             SocketChannel member = connectAndJoin("member", "member-client", roomId, boardId)) {

            drainUntilQuiet(owner, ByteBuffer.allocate(256 * 1024), 200, 3_000);

            Line memberLine = Line.create("#123456", 2, 2, 20, 20, 2.0, "member", "member-client");
            writeFully(member, MessageCodec.encode(new Message(
                    MessageType.MUTATION, ShapeCodec.encodeMutation(memberLine))));
            drainUntilQuiet(member, ByteBuffer.allocate(256 * 1024), 300, 5_000);

            assertThat(board(roomManager, roomId, boardId).snapshot()).hasSize(1);

            record UndoPayload(String shapeId) {}
            writeFully(member, MessageCodec.encodeObject(MessageType.UNDO_REQUEST,
                    new UndoPayload(memberLine.objectId().toString())));
            drainUntilQuiet(member, ByteBuffer.allocate(256 * 1024), 300, 5_000);

            assertThat(board(roomManager, roomId, boardId).snapshot()).isEmpty();
        }
    }

    @Test
    void textUpdateRequiresDrawPermission() throws Exception {
        final String roomId = "text-rbac-room";
        final String boardId = MessageCodec.DEFAULT_INITIAL_BOARD_ID;
        RoomManager roomManager = startRoom(roomId, boardId);

        try (SocketChannel owner = connectAndJoin("owner", "owner-client", roomId, boardId);
             SocketChannel speakOnly = connectAndJoin("speaker", "speaker-client", roomId, boardId)) {

            drainUntilQuiet(owner, ByteBuffer.allocate(256 * 1024), 200, 3_000);

            ClientSession speakerSession = findSession(roomManager, roomId, "speaker-client");
            assertThat(speakerSession).isNotNull();
            speakerSession.permissions = RoomPermissions.PERM_SPEAK;

            ByteBuffer ownerAcc = ByteBuffer.allocate(256 * 1024);
            owner.configureBlocking(false);

            writeFully(speakOnly, MessageCodec.encodeTextUpdate(
                    UUID.randomUUID(), "speaker-client", "speaker", 10, 10, "hello"));
            drainUntilQuiet(speakOnly, ByteBuffer.allocate(64 * 1024), 300, 2_000);

            List<Message> ownerMessages = drainMessages(owner, ownerAcc, 500);
            owner.configureBlocking(true);

            assertThat(ownerMessages.stream().map(Message::type))
                    .as("TEXT_UPDATE must not relay when session lacks PERM_DRAW")
                    .doesNotContain(MessageType.TEXT_UPDATE);
        }
    }

    private RoomManager startRoom(String roomId, String boardId) throws Exception {
        WalManager walManager = new WalManager(tempDir);
        RoomManager roomManager = new RoomManager(walManager);
        walManager.append(roomId, boardId, new Message(MessageType.SHAPE_COMMIT, "{\"seed\":1}"));
        roomManager.getOrCreateRoom(roomId);
        startServer(roomManager, walManager);
        return roomManager;
    }

    private SocketChannel connectAndJoin(String author, String clientId, String roomId, String boardId)
            throws Exception {
        SocketChannel channel = SocketChannel.open();
        channel.configureBlocking(true);
        channel.connect(new InetSocketAddress(HOST, serverPort()));
        writeFully(channel, MessageCodec.encodeHandshake(clientId));
        drainUntilQuiet(channel, ByteBuffer.allocate(256 * 1024), 250, 5_000);
        writeFully(channel, MessageCodec.encodeJoinRoom(roomId, author, boardId));
        drainUntilQuiet(channel, ByteBuffer.allocate(256 * 1024), 250, 5_000);
        return channel;
    }

    private static CanvasStateManager board(RoomManager roomManager, String roomId, String boardId) {
        RoomContext room = roomManager.getRoom(roomId);
        assertThat(room).isNotNull();
        CanvasStateManager board = room.getBoard(boardId);
        assertThat(board).isNotNull();
        return board;
    }

    private void startServer(RoomManager roomManager, WalManager walManager) {
        server = new NioServer(0, roomManager, walManager);
        serverThread = new Thread(server, "nio-server-object-authority-test");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private int serverPort() throws Exception {
        return server.getBoundPortFuture().get(5, TimeUnit.SECONDS);
    }

    private static ClientSession findSession(RoomManager roomManager, String roomId, String clientId) {
        RoomContext room = roomManager.getRoom(roomId);
        if (room == null) {
            return null;
        }
        for (var key : room.getActiveKeys()) {
            if (key.attachment() instanceof ClientSession session && clientId.equals(session.clientId)) {
                return session;
            }
        }
        return null;
    }

    private static void writeFully(SocketChannel channel, ByteBuffer frame) throws Exception {
        ByteBuffer src = frame.duplicate();
        while (src.hasRemaining()) {
            channel.write(src);
        }
    }

    private static void drainUntilQuiet(SocketChannel channel, ByteBuffer acc, long quietMs, long maxWaitMs)
            throws Exception {
        channel.configureBlocking(false);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMs);
        long lastRead = System.nanoTime();
        while (System.nanoTime() < deadline) {
            int n = channel.read(acc);
            if (n > 0) {
                lastRead = System.nanoTime();
                decodeAvailable(acc);
            }
            if (System.nanoTime() - lastRead > TimeUnit.MILLISECONDS.toNanos(quietMs)) {
                channel.configureBlocking(true);
                return;
            }
            Thread.sleep(10);
        }
        channel.configureBlocking(true);
    }

    private static List<Message> drainMessages(SocketChannel channel, ByteBuffer acc, long windowMs)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(windowMs);
        while (System.nanoTime() < deadline) {
            int n = channel.read(acc);
            if (n > 0) {
                decodeAvailable(acc);
            }
            Thread.sleep(10);
        }
        acc.flip();
        var out = new java.util.ArrayList<Message>();
        while (acc.hasRemaining()) {
            int start = acc.position();
            try {
                out.add(MessageCodec.decode(acc));
            } catch (PartialMessageException e) {
                acc.position(start);
                break;
            }
        }
        acc.compact();
        return out;
    }

    private static List<Message> decodeAvailable(ByteBuffer acc) {
        var out = new java.util.ArrayList<Message>();
        acc.flip();
        while (acc.hasRemaining()) {
            int start = acc.position();
            try {
                out.add(MessageCodec.decode(acc));
            } catch (PartialMessageException e) {
                acc.position(start);
                break;
            }
        }
        acc.compact();
        return out;
    }
}
