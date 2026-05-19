package com.distrisync.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageCodecModerationTest {

    @Test
    void moderationAction_roundTrip() throws Exception {
        ByteBuffer encoded = MessageCodec.encodeModerationAction("KICK", "target-uuid", "spam");
        Message msg = MessageCodec.decode(encoded);
        assertThat(msg.type()).isEqualTo(MessageType.MODERATION_ACTION);

        MessageCodec.ModerationActionPayload p = MessageCodec.decodeModerationAction(msg);
        assertThat(p.actionType()).isEqualTo("KICK");
        assertThat(p.targetClientId()).isEqualTo("target-uuid");
        assertThat(p.reason()).isEqualTo("spam");
    }

    @Test
    void grantSpeakModerationAction_roundTrip() throws Exception {
        ByteBuffer encoded = MessageCodec.encodeModerationAction("GRANT_SPEAK", "target-uuid", "restored");
        Message msg = MessageCodec.decode(encoded);
        assertThat(msg.type()).isEqualTo(MessageType.MODERATION_ACTION);

        MessageCodec.ModerationActionPayload p = MessageCodec.decodeModerationAction(msg);
        assertThat(p.actionType()).isEqualTo("GRANT_SPEAK");
        assertThat(p.targetClientId()).isEqualTo("target-uuid");
        assertThat(p.reason()).isEqualTo("restored");
    }

    @Test
    void sessionRevoked_roundTrip() throws Exception {
        ByteBuffer encoded = MessageCodec.encodeSessionRevoked("removed by admin");
        Message msg = MessageCodec.decode(encoded);
        assertThat(msg.type()).isEqualTo(MessageType.SESSION_REVOKED);
        assertThat(MessageCodec.decodeSessionRevoked(msg)).isEqualTo("removed by admin");
    }

    @Test
    void decodeModerationAction_rejectsBlankTarget() {
        Message msg = new Message(MessageType.MODERATION_ACTION,
                "{\"actionType\":\"KICK\",\"targetClientId\":\"  \"}");
        assertThatThrownBy(() -> MessageCodec.decodeModerationAction(msg))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
