package dev.klxsh.speakersinheads.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;

abstract class StudioSlider extends AbstractSliderButton {
    StudioSlider(int x, int y, int width, int height, Component label, double value) {
        super(x, y, Math.max(1, width), height, label, Math.clamp(value, 0d, 1d));
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        g.fill(x, y, x + w, y + h, StudioUi.control());
        int left = x + 4;
        int right = x + w - 4;
        int marker = left + (int) Math.round(value * Math.max(0, right - left));
        if (right > left) {
            g.fill(left, y + h - 4, right, y + h - 2, StudioUi.line());
            if (marker > left) g.fill(left, y + h - 4, marker, y + h - 2, StudioUi.accent());
        }
        var font = Minecraft.getInstance().font;
        g.centeredText(font, StudioUi.fit(font, getMessage().getString(), w - 8),
                x + w / 2, y + (h - 10) / 2 - 1, isHoveredOrFocused() ? StudioUi.accent() : StudioUi.text());
    }
}
