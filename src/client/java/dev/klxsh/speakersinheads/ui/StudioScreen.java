package dev.klxsh.speakersinheads.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

abstract class StudioScreen extends Screen {
    private long opened;

    StudioScreen(Component title) { super(title); }

    @Override
    public void added() { opened = System.nanoTime(); }

    final void drawPanel(GuiGraphicsExtractor g, int width, int height, int x, int y, int w, int h) {
        long duration = ThemeAppearance.durationNanos();
        float progress = duration == 0 ? 1f : Math.clamp((System.nanoTime() - opened) / (float) duration, 0f, 1f);
        StudioUi.panel(g, width, height, x, y, w, h, 1f - (1f - progress) * (1f - progress));
    }
}
