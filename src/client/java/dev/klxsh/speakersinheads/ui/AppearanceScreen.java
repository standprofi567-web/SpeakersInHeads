package dev.klxsh.speakersinheads.ui;

import dev.klxsh.speakersinheads.audio.HybridAcoustics;
import dev.klxsh.speakersinheads.audio.SprAcoustics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class AppearanceScreen extends StudioScreen {
    private final Screen parent;
    private final boolean acoustics;

    AppearanceScreen(Screen parent, boolean acoustics) {
        super(Component.literal(acoustics ? "Acoustics" : "Appearance"));
        this.parent = parent;
        this.acoustics = acoustics;
    }

    private int panelWidth() { return Math.min(370, width - 20); }
    private int top() { return (height - 158) / 2; }

    @Override
    protected void init() {
        int w = panelWidth(), x = (width - w) / 2, y = top();
        if (acoustics) {
            addRenderableWidget(strength("Wall occlusion", x + 14, y + 60, w - 28, HybridAcoustics.wall(), v -> HybridAcoustics.setWall((float) v)));
            addRenderableWidget(strength("Underwater filter", x + 14, y + 86, w - 28, HybridAcoustics.water(), v -> HybridAcoustics.setWater((float) v)));
        } else {
            int card = (w - 40) / 4;
            var themes = ThemeAppearance.Theme.values();
            for (int i = 0; i < themes.length; i++) {
                var theme = themes[i];
                addRenderableWidget(StudioUi.button(Component.literal(theme.name()), button -> ThemeAppearance.setTheme(theme))
                        .bounds(x + 14 + i * (card + 4), y + 78, card, 18).build());
            }
            var label = Component.literal("Motion: " + ThemeAppearance.motion());
            addRenderableWidget(StudioUi.button(label, button -> {
                var values = ThemeAppearance.Motion.values();
                ThemeAppearance.setMotion(values[(ThemeAppearance.motion().ordinal() + 1) % values.length]);
                button.setMessage(Component.literal("Motion: " + ThemeAppearance.motion()));
            }).bounds(x + 14, y + 106, w - 28, 18).build());
        }
        addRenderableWidget(StudioUi.button(Component.literal("Back"), button -> onClose())
                .bounds(x + 14, y + 132, w - 28, 18).build());
    }

    private StudioSlider strength(String name, int x, int y, int w, float initial,
                                  java.util.function.DoubleConsumer apply) {
        return new StudioSlider(x, y, w, 18, Component.literal(label(name, initial)), initial) {
            @Override protected void updateMessage() { setMessage(Component.literal(label(name, value))); }
            @Override protected void applyValue() { apply.accept(value); }
        };
    }

    private static String label(String name, double value) {
        return name + (value == 0 ? " / Off" : " / " + Math.round(value * 100) + "%");
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int w = panelWidth(), x = (width - w) / 2, y = top();
        drawPanel(g, width, height, x, y, w, 158);
        g.text(font, getTitle(), x + 14, y + 11, StudioUi.text());
        if (acoustics) {
            g.text(font, StudioUi.fit(font, SprAcoustics.status(), w - 28), x + 14, y + 41, StudioUi.muted());
        } else {
            int card = (w - 40) / 4;
            var themes = ThemeAppearance.Theme.values();
            for (int i = 0; i < themes.length; i++) {
                var theme = themes[i];
                int left = x + 14 + i * (card + 4);
                g.fill(left, y + 41, left + card, y + 72, theme.color(0));
                g.fill(left + 3, y + 44, left + card - 3, y + 51, theme.color(1));
                g.fill(left + 6, y + 56, left + card - 6, y + 59, theme.color(8));
                g.fill(left + 6, y + 63, left + card / 2, y + 66, theme.color(7));
                if (theme == ThemeAppearance.theme()) g.fill(left, y + 71, left + card, y + 73, StudioUi.accent());
            }
        }
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { Minecraft.getInstance().gui.setScreen(parent); }
}
