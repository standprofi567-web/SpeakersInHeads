package dev.klxsh.speakersinheads.ui;

import dev.klxsh.speakersinheads.audio.SpatialSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Compact modal screen for configuring spatial distance attenuation and 3D positioning modes.
 */
public final class SpatialSettingsScreen extends StudioScreen {
    private static final int PANEL_WIDTH = 340;
    private static final int PANEL_HEIGHT = 220;

    private final Screen parent;
    private final SpatialSettings settings = SpatialSettings.get();

    public SpatialSettingsScreen(Screen parent) {
        super(Component.literal("Spatial Audio Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(PANEL_WIDTH, width - 24);
        int panelHeight = Math.min(PANEL_HEIGHT, height - 12);
        int left = (width - panelWidth) / 2;
        int top = (height - panelHeight) / 2;
        int contentWidth = panelWidth - 32;

        int rowY = top + 36;
        int step = Math.min(24, (panelHeight - 60) / 6);
        int rowHeight = step - 2;
        // 1. Enable/Disable toggle
        Button toggle = StudioUi.button(
                Component.literal("Spatial Attenuation: " + (settings.isEnabled() ? "Enabled" : "Disabled")),
                button -> {
                    settings.setEnabled(!settings.isEnabled());
                    clearWidgets();
                    init();
                }
        ).bounds(left + 16, rowY, contentWidth, rowHeight).build();
        toggle.setTooltip(Tooltip.create(Component.literal(
                settings.isEnabled()
                        ? "Sound volume decreases smoothly as you move away from the head."
                        : "Sound plays at constant volume regardless of distance."
        )));
        addRenderableWidget(toggle);

        // 2. Spatial Mode selector (Mono 3D / Stereo / Virtual Stereo)
        rowY += step;
        Button modeButton = StudioUi.button(
                Component.literal("Spatial Mode: " + settings.mode().displayName()),
                button -> {
                    SpatialSettings.SpatialMode[] modes = SpatialSettings.SpatialMode.values();
                    int nextIdx = (settings.mode().ordinal() + 1) % modes.length;
                    settings.setMode(modes[nextIdx]);
                    clearWidgets();
                    init();
                }
        ).bounds(left + 16, rowY, contentWidth, rowHeight).build();
        modeButton.setTooltip(Tooltip.create(Component.literal(
                settings.mode().displayName() + " — " + settings.mode().description()
        )));
        addRenderableWidget(modeButton);

        // Presets complement the continuous slider and preserve legacy custom distances.
        rowY += step;
        float maxNorm = (settings.maxDistance() - SpatialSettings.MIN_MAX_DISTANCE)
                / (SpatialSettings.MAX_MAX_DISTANCE - SpatialSettings.MIN_MAX_DISTANCE);
        var maxSlider = new DistanceSlider(
                left + 16, rowY, contentWidth - 72, rowHeight,
                "Max Distance", maxNorm,
                SpatialSettings.MIN_MAX_DISTANCE, SpatialSettings.MAX_MAX_DISTANCE,
                "Sound fades to silence at this distance", settings::setMaxDistance
        );
        addRenderableWidget(maxSlider);
        var presets = StudioUi.button(Component.literal("Preset"), button -> {
            float next = SpatialSettings.nextRangePreset(settings.maxDistance());
            settings.setMaxDistance(next);
            maxSlider.setDisplayedValue(next);
        }).bounds(left + 16 + contentWidth - 68, rowY, 68, rowHeight).build();
        presets.setTooltip(Tooltip.create(Component.literal("Cycle 16, 32, 48, 64, 80 and 96 blocks")));
        addRenderableWidget(presets);

        // 4. Full volume distance slider (0 to 32 blocks)
        rowY += step;
        float fullMax = Math.min(32.0f, Math.max(1.0f, settings.maxDistance() - 1.0f));
        float fullNorm = settings.fullVolumeDistance() / fullMax;
        addRenderableWidget(new DistanceSlider(
                left + 16, rowY, contentWidth, rowHeight,
                "Full-Volume Distance",
                fullNorm,
                SpatialSettings.MIN_FULL_VOLUME_DISTANCE,
                fullMax,
                "Sound plays at 100% volume within this radius",
                value -> settings.setFullVolumeDistance(value)
        ));

        // 5. Curve selector (Smooth / Linear)
        rowY += step;
        Button curveButton = StudioUi.button(
                Component.literal("Attenuation Curve: " + settings.curve().displayName()),
                button -> {
                    SpatialSettings.AttenuationCurve next = settings.curve() == SpatialSettings.AttenuationCurve.SMOOTH
                            ? SpatialSettings.AttenuationCurve.LINEAR
                            : SpatialSettings.AttenuationCurve.SMOOTH;
                    settings.setCurve(next);
                    clearWidgets();
                    init();
                }
        ).bounds(left + 16, rowY, contentWidth, rowHeight).build();
        curveButton.setTooltip(Tooltip.create(Component.literal(
                settings.curve() == SpatialSettings.AttenuationCurve.SMOOTH
                        ? "Smooth (S-curve): Gradual, realistic falloff using smoothstep."
                        : "Linear: Direct straight-line volume decrease."
        )));
        addRenderableWidget(curveButton);

        // 6. Visual animations toggle
        rowY += step;
        Button animButton = StudioUi.button(
                Component.literal("Speaker Animations: " + (settings.isAnimationsEnabled() ? "Enabled" : "Disabled")),
                button -> {
                    settings.setAnimationsEnabled(!settings.isAnimationsEnabled());
                    clearWidgets();
                    init();
                }
        ).bounds(left + 16, rowY, contentWidth, rowHeight).build();
        animButton.setTooltip(Tooltip.create(Component.literal(
                settings.isAnimationsEnabled()
                        ? "Visual sound waves and speaker motion are enabled during playback."
                        : "Visual sound waves and speaker motion are disabled."
        )));
        addRenderableWidget(animButton);

        // 7. Done button
        rowY = top + panelHeight - 22;
        addRenderableWidget(StudioUi.button(Component.literal("Done"), button -> onClose())
                .bounds(width / 2 - 60, rowY, 120, 16)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float deltaTracker) {

        int panelWidth = Math.min(PANEL_WIDTH, width - 24);
        int panelHeight = Math.min(PANEL_HEIGHT, height - 12);
        int left = (width - panelWidth) / 2;
        int top = (height - panelHeight) / 2;

        // Background panel
        drawPanel(graphics, width, height, left, top, panelWidth, panelHeight);
        // Subtle border

        // Header
        graphics.text(font, "Settings / Spatial", left + 14, top + 11, StudioUi.text());

        super.extractRenderState(graphics, mouseX, mouseY, deltaTracker);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static final class DistanceSlider extends StudioSlider {
        private final String label;
        private final float min;
        private final float max;
        private final java.util.function.Consumer<Float> consumer;

        private DistanceSlider(int x, int y, int width, int height, String label, double initialNorm,
                               float min, float max, String tooltipText,
                               java.util.function.Consumer<Float> consumer) {
            super(x, y, width, height, Component.empty(), initialNorm);
            this.label = label;
            this.min = min;
            this.max = max;
            this.consumer = consumer;
            if (tooltipText != null) {
                setTooltip(Tooltip.create(Component.literal(tooltipText)));
            }
            updateMessage();
        }

        private void setDisplayedValue(float distance) {
            value = (distance - min) / (max - min);
            updateMessage();
        }

        private float actualValue() {
            return min + (float) value * (max - min);
        }

        @Override
        protected void updateMessage() {
            int rounded = Math.round(actualValue());
            setMessage(Component.literal(label + ": " + rounded + " blocks"));
        }

        @Override
        protected void applyValue() {
            consumer.accept(actualValue());
        }
    }
}
