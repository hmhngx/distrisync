package com.distrisync.client;

import javafx.scene.control.Button;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.paint.Color;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Paint;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WhiteboardAppLoginButtonTest extends ApplicationTest {

    @Override
    public void start(Stage stage) throws Exception {
        new WhiteboardApp().start(stage);
        stage.show();
        interact(() -> {
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
        });
    }

    @Test
    void testButtonHasNoDefaultBackgroundInsets() {
        Button join = lookup("#" + WhiteboardApp.LOGIN_JOIN_NETWORK_BUTTON_ID).queryAs(Button.class);
        interact(() -> {
            join.applyCss();
            join.layout();
        });

        Background bg = join.getBackground();
        assertThat(bg).isNotNull();
        List<BackgroundFill> fills = bg.getFills();
        assertThat(fills).as("flat fill — no Modena multi-layer gradient stack").hasSize(1);
        Paint fill = fills.getFirst().getFill();
        assertThat(fill)
                .as("no linear-gradient chrome")
                .isNotInstanceOf(LinearGradient.class)
                .isInstanceOf(Color.class);
        Color expected = Color.web("#5C6CFF");
        Color got = (Color) fill;
        assertThat(got.getRed()).isEqualTo(expected.getRed());
        assertThat(got.getGreen()).isEqualTo(expected.getGreen());
        assertThat(got.getBlue()).isEqualTo(expected.getBlue());
        assertThat(got.getOpacity()).isEqualTo(expected.getOpacity());
    }
}
