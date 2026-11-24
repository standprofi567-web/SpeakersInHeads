package dev.klxsh.speakersinheads.ui;

import dev.klxsh.speakersinheads.ClientPreferences;
import dev.klxsh.speakersinheads.audio.OutputBoost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;

import java.util.function.DoubleConsumer;

final class AudioSettingsScreen extends StudioScreen {
    private final Screen parent;
    private boolean changed;

    AudioSettingsScreen(Screen parent) {
        super(Component.literal("Audio Settings"));
        this.parent = parent;
    }

    private int panelWidth() { return Math.min(330, width - 18); }
    private int top() { return (height - 154) / 2; }

    @Override protected void init() {
        int w = panelWidth(), x = (width - w) / 2 + 12, y = top();
        addSetting("Output Boost", "Extra level with a soft ceiling. Loud tracks and multiple heads can still sound compressed.",
                x, y + 56, w - 24, OutputBoost.setting(), 100, 50, value -> OutputBoost.setSetting((float) value));
        int buttonWidth = (w - 29) / 2;
        addRenderableWidget(StudioUi.button(Component.literal("Reset to Defaults"), ignored -> {
            OutputBoost.setSetting(0f);
            changed = true;
            clearWidgets();
            init();
        }).bounds(x, y + 116, buttonWidth, 19).build());
        addRenderableWidget(StudioUi.button(Component.literal("Back"), ignored -> onClose())
                .bounds(x + buttonWidth + 5, y + 116, buttonWidth, 19).build());
    }

    private void addSetting(String title, String help, int x, int y, int w, double level,
                            int base, int range, DoubleConsumer update) {
        var slider = new StudioSlider(x, y, w, 19, label(title, base, range, level), level) {
            @Override protected void updateMessage() { setMessage(label(title, base, range, value)); }
            @Override protected void applyValue() { update.accept(value); changed = true; }
        };
        slider.setTooltip(Tooltip.create(Component.literal(help)));
        addRenderableWidget(slider);
    }

    private static Component label(String title, int base, int range, double amount) {
        return Component.literal(title + " / " + (base + Math.round(range * amount)) + "%");
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        int w = panelWidth(), x = (width - w) / 2, y = top();
        drawPanel(g, width, height, x, y, w, 154);
        g.text(font, getTitle(), x + 12, y + 11, StudioUi.text());
        var category = Component.literal("Minecraft Audio Category / ")
                .append(Component.translatable("soundCategory." + SoundSource.RECORDS.getName()));
        g.text(font, StudioUi.fit(font, category.getString(), w - 24), x + 12, y + 37, StudioUi.accent());
        g.text(font, StudioUi.fit(font, "Set head, group or pair volume in the player.", w - 24),
                x + 12, y + 83, StudioUi.muted());
        g.text(font, StudioUi.fit(font, "Volume changes are smoothed during playback.", w - 24),
                x + 12, y + 96, StudioUi.muted());
        super.extractRenderState(g, mx, my, delta);
    }

    @Override public void removed() {
        if (changed) {
            ClientPreferences.set("boost", Float.toString(OutputBoost.setting()));
        }
        super.removed();
    }

    @Override public void onClose() { Minecraft.getInstance().gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
