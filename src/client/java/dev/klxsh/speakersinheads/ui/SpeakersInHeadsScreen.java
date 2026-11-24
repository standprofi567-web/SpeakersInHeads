package dev.klxsh.speakersinheads.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

public final class SpeakersInHeadsScreen extends StudioScreen {
    private static final int PANEL_WIDTH = 340;
    private static final int PANEL_HEIGHT = 130;

    private final BlockPos headPosition;
    private final String headType;

    public SpeakersInHeadsScreen(BlockPos headPosition, String headType) {
        super(Component.literal("SpeakersInHeads"));
        this.headPosition = headPosition.immutable();
        this.headType = headType;
    }

    @Override
    protected void init() {
        int secondary = Math.max(1, Math.min(82, (width - 54) / 2));
        int buttonWidth = secondary * 2 + 6;
        int left = (width - buttonWidth) / 2;
        addRenderableWidget(StudioUi.button(Component.literal("Music Mixer"), button ->
                        Minecraft.getInstance().gui.setScreen(new MusicMixerScreen(headPosition, headType)))
                .bounds(left, height / 2 + 8, buttonWidth, 22)
                .build());
        addRenderableWidget(UnifiedMusicPlayer.button("Settings", "Configure this speaker and global options",
                left, height / 2 + 36, secondary,
                () -> minecraft.gui.setScreen(new SettingsScreen(this, headPosition, headType))));
        addRenderableWidget(UnifiedMusicPlayer.button("Close", "Return to Minecraft",
                left + secondary + 6, height / 2 + 36, secondary, this::onClose));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float deltaTracker) {
        int panelWidth = Math.min(PANEL_WIDTH, width - 24);
        int left = (width - panelWidth) / 2;
        int top = (height - PANEL_HEIGHT) / 2;
        int centerX = width / 2;

        drawPanel(graphics, width, height, left, top, panelWidth, PANEL_HEIGHT);
        graphics.text(font, "SpeakersInHeads", left + 14, top + 12, StudioUi.text());
        var track = dev.klxsh.speakersinheads.audio.SpeakerAudioManager.currentTrack(headPosition);
        String detail = track == null ? headType + "  /  " + headPosition.toShortString() : track.title();
        graphics.text(font, StudioUi.fit(font, detail, panelWidth - 28), left + 14, top + 43, StudioUi.accent());

        super.extractRenderState(graphics, mouseX, mouseY, deltaTracker);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
