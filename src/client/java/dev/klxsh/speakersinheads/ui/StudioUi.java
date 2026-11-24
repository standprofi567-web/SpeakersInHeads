package dev.klxsh.speakersinheads.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Shared material, typography and interaction styling for the mixer screens. */
final class StudioUi {

    private StudioUi() {}

    static int surface() { return ThemeAppearance.color(0); }
    static int header() { return ThemeAppearance.color(1); }
    static int control() { return ThemeAppearance.color(2); }
    static int hover() { return ThemeAppearance.color(3); }
    static int disabled() { return ThemeAppearance.color(4); }
    static int text() { return ThemeAppearance.color(5); }
    static int muted() { return ThemeAppearance.color(6); }
    static int accent() { return ThemeAppearance.color(7); }
    static int line() { return ThemeAppearance.color(8); }
    static int danger() { return ThemeAppearance.color(9); }

    static void panel(GuiGraphicsExtractor g, int width, int height, int x, int y, int w, int h, float entrance) {
        g.fill(0, 0, width, height, (Math.round(171 * entrance) << 24) | 0x0b0a09);
        g.fill(x + 3, y + 5, x + w + 3, y + h + 5, 0x62000000);
        g.fill(x, y, x + w, y + h, surface());
        g.fill(x, y, x + w, y + 31, header());
        g.fill(x, y + 31, x + w, y + 32, line());
        g.fill(x, y, x + 2, y + Math.round(h * entrance), accent());
    }

    static String fit(Font font, String value, int width) {
        if (width <= 0) return "";
        if (font.width(value) <= width) return value;
        return font.plainSubstrByWidth(value, Math.max(0, width - font.width("…"))) + "…";
    }

    static Button.Builder button(Component text, Button.OnPress press) {
        return new Button.Builder(text, press) {
            private int x, y, width, height;

            @Override public Button.Builder bounds(int x, int y, int width, int height) {
                this.x = x;
                this.y = y;
                this.width = width;
                this.height = height;
                return this;
            }

            @Override public Button build() {
                return new StudioButton(x, y, width, height, text, press);
            }
        };
    }

    private static final class StudioButton extends Button {
        private long previousNanos;
        private float hoverAmount;
        private Component lastLabel;
        private int lastWidth;
        private String displayLabel = "";

        StudioButton(int x, int y, int width, int height, Component text, OnPress press) {
            super(x, y, Math.max(1, width), height, text, press, DEFAULT_NARRATION);
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float delta) {
            long now = System.nanoTime();
            float seconds = previousNanos == 0 ? 0 : Math.min(0.1f, (now - previousNanos) * 1e-9f);
            previousNanos = now;
            float target = active && isHoveredOrFocused() ? 1f : 0f;
            hoverAmount = ThemeAppearance.durationNanos() == 0 ? target
                    : hoverAmount + (target - hoverAmount) * Math.min(1f, seconds * (ThemeAppearance.motion() == ThemeAppearance.Motion.REDUCED ? 30 : 16));
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            int fill = active ? ThemeAppearance.mix(control(), hover(), hoverAmount) : disabled();
            g.fill(x, y, x + w, y + h, fill);
            g.fill(x, y + h - 1, x + w, y + h, isFocused() ? accent() : line());
            Font font = Minecraft.getInstance().font;
            if (!getMessage().equals(lastLabel) || lastWidth != w) {
                lastLabel = getMessage();
                lastWidth = w;
                displayLabel = fit(font, lastLabel.getString(), w - 8);
            }
            g.centeredText(font, displayLabel, x + w / 2, y + (h - 8) / 2, active ? text() : muted());
        }
    }

}
