package com.distrisync.client;

import com.distrisync.protocol.MessageCodec;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class YoutubePlayerNodeTest {

    @Test
    void expectedPlaybackSeconds_playingAdvancesWithWallClock() {
        MessageCodec.MediaStatePayload state = new MessageCodec.MediaStatePayload(
                "PLAYING", 10.0, 1_000L, "vid");
        assertThat(YoutubePlayerNode.expectedPlaybackSeconds(state, 4_000L)).isEqualTo(13.0);
    }

    @Test
    void expectedPlaybackSeconds_pausedUsesMediaTimeOnly() {
        MessageCodec.MediaStatePayload state = new MessageCodec.MediaStatePayload(
                "PAUSED", 42.0, 1_000L, "vid");
        assertThat(YoutubePlayerNode.expectedPlaybackSeconds(state, 9_000L)).isEqualTo(42.0);
    }

    @Test
    void formatMediaTime_formatsMinutesAndSeconds() {
        assertThat(YoutubePlayerNode.formatMediaTime(83.7)).isEqualTo("01:23");
        assertThat(YoutubePlayerNode.formatMediaTime(296.2)).isEqualTo("04:56");
        assertThat(YoutubePlayerNode.formatMediaTime(-1)).isEqualTo("00:00");
    }

    @Test
    void syncDriftColor_mapsThresholdBands() {
        assertThat(YoutubePlayerNode.syncDriftColor(0.2)).isEqualTo(Color.web("#10B981"));
        assertThat(YoutubePlayerNode.syncDriftColor(1.0)).isEqualTo(Color.web("#FBBF24"));
        assertThat(YoutubePlayerNode.syncDriftColor(2.0)).isEqualTo(Color.web("#F59E0B"));
    }

    @Test
    void parseYouTubeVideoId_watchUrl() {
        assertThat(YoutubePlayerNode.parseYouTubeVideoId(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ")).isEqualTo("dQw4w9WgXcQ");
    }

    @Test
    void parseYouTubeVideoId_shortUrl() {
        assertThat(YoutubePlayerNode.parseYouTubeVideoId("https://youtu.be/dQw4w9WgXcQ"))
                .isEqualTo("dQw4w9WgXcQ");
    }

    @Test
    void parseYouTubeVideoId_bareId() {
        assertThat(YoutubePlayerNode.parseYouTubeVideoId("dQw4w9WgXcQ")).isEqualTo("dQw4w9WgXcQ");
    }

    @Test
    void parseYouTubeVideoId_invalid() {
        assertThat(YoutubePlayerNode.parseYouTubeVideoId("not-a-url")).isNull();
    }
}
