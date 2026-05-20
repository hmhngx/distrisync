package com.distrisync.client;

import javafx.scene.effect.DropShadow;
import javafx.scene.paint.Color;

/** Shared visual effects for workspace chrome (keeps elevation in code, not CSS). */
public final class UiEffects {

    private UiEffects() {
    }

    /** Soft elevation for toolbars and the collaboration roster (matches prior CSS depth). */
    public static DropShadow toolbarDropShadow() {
        DropShadow shadow = new DropShadow();
        shadow.setRadius(12);
        shadow.setOffsetX(0);
        shadow.setOffsetY(4);
        shadow.setColor(Color.color(0, 0, 0, 0.35));
        return shadow;
    }
}
