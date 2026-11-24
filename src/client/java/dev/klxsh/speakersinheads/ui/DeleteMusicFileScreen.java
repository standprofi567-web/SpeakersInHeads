package dev.klxsh.speakersinheads.ui;

import dev.klxsh.speakersinheads.music.MusicLibrary;
import dev.klxsh.speakersinheads.music.MusicTrack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Separate confirmation step for the irreversible Delete File action. */
public final class DeleteMusicFileScreen extends StudioScreen {
    private final Screen parent;
    private final MusicTrack track;
    private volatile String status;
    private volatile boolean deleting;

    public DeleteMusicFileScreen(Screen parent, MusicTrack track) {
        super(Component.literal("Delete music file?"));
        this.parent = parent;
        this.track = track;
    }

    @Override
    protected void init() {
        int left = width / 2 - 110;
        int y = height / 2 + 28;
        Button confirm = StudioUi.button(Component.literal("Delete File"), button -> delete())
                .bounds(left, y, 104, 22).build();
        confirm.active = !deleting;
        addRenderableWidget(confirm);
        Button cancel = StudioUi.button(Component.literal("Cancel"), button -> returnToParent())
                .bounds(left + 116, y, 104, 22).build();
        cancel.active = !deleting;
        addRenderableWidget(cancel);
    }

    private void delete() {
        if (deleting) return;
        deleting = true;
        clearWidgets();
        init();
        MusicLibrary.deleteFileAfterConfirmation(track).whenComplete((ignored, failure) ->
                Minecraft.getInstance().execute(() -> {
                    if (failure == null) {
                        Minecraft.getInstance().gui.setScreen(parent);
                    } else {
                        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                        status = "Could not delete file: " + cause.getMessage();
                        deleting = false;
                        clearWidgets();
                        init();
                    }
                }));
    }

    private void returnToParent() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float deltaTracker) {
        int panelWidth = Math.min(480, width - 24);
        int left = (width - panelWidth) / 2;
        int top = height / 2 - 76;
        drawPanel(graphics, width, height, left, top, panelWidth, 150);
        graphics.centeredText(font, "Delete music file?", width / 2, top + 18, StudioUi.danger());
        graphics.centeredText(font, "This permanently removes the source file:", width / 2, top + 48, StudioUi.text());
        graphics.centeredText(font, track.path().getFileName().toString(), width / 2, top + 70, StudioUi.muted());
        graphics.centeredText(font, "This action cannot be undone.", width / 2, top + 91, StudioUi.danger());
        if (status != null) graphics.centeredText(font, status, width / 2, top + 112, StudioUi.danger());
        super.extractRenderState(graphics, mouseX, mouseY, deltaTracker);
    }

    @Override
    public void onClose() {
        if (!deleting) returnToParent();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
