package dev.klxsh.speakersinheads.ui;

import dev.klxsh.speakersinheads.music.MusicLibrary;
import dev.klxsh.speakersinheads.music.MusicTrack;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

final class LibraryActionsScreen extends StudioScreen {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private final Screen parent;
    private final MusicTrack selected;
    private final Consumer<MusicTrack> play;
    private String actionStatus = "";
    private boolean opening;
    private String openFailure;
    private Button openButton;

    LibraryActionsScreen(Screen parent, MusicTrack selected, Consumer<MusicTrack> play) {
        super(Component.literal("Manage Library"));
        this.parent = parent;
        this.selected = selected;
        this.play = play;
    }

    private int w() { return Math.min(360, width - 16); }
    private int x() { return (width - w()) / 2; }
    private int h() { return selected == null ? 145 : 166; }
    private int y() { return (height - h()) / 2; }

    @Override protected void init() {
        int left = x() + 10, half = (w() - 24) / 2, top = y();
        openButton = addRenderableWidget(UnifiedMusicPlayer.button("Open Music Folder", "Open the active profile's library in Explorer",
                left, top + 71, half, this::openFolder));
        if (openFailure != null) openButton.setTooltip(Tooltip.create(Component.literal(openFailure)));
        addRenderableWidget(UnifiedMusicPlayer.button("Import File", "Copy a file into the managed library",
                left + half + 4, top + 71, half, () -> {
                    var screen = new SmartImportScreen(this, play);
                    minecraft.gui.setScreen(screen);
                    screen.choose();
                }));
        addRenderableWidget(UnifiedMusicPlayer.button("Refresh Library", "Rescan for completed MP3, OGG and WAV files",
                left, top + 93, half, () -> {
                    actionStatus = "";
                    openFailure = null;
                    MusicLibrary.refresh();
                }));
        var folder = MusicLibrary.getMusicDirectory();
        var copy = UnifiedMusicPlayer.button("Copy Path", folder == null ? "Library is loading" : folder.toString(),
                left + half + 4, top + 93, half, () -> {
                    minecraft.keyboardHandler.setClipboard(folder.toString());
                    actionStatus = "Folder path copied.";
                    openFailure = null;
                });
        copy.active = folder != null;
        addRenderableWidget(copy);
        if (selected != null) {
            addRenderableWidget(UnifiedMusicPlayer.button("Remove Entry", "Hide the selected track from the library",
                    left, top + 115, half, () -> {
                        MusicLibrary.removeFromLibrary(selected);
                        onClose();
                    }));
            var delete = UnifiedMusicPlayer.button("Delete File…", selected.title(), left + half + 4, top + 115, half,
                    () -> minecraft.gui.setScreen(new DeleteMusicFileScreen(this, selected)));
            addRenderableWidget(delete);
        }
        addRenderableWidget(UnifiedMusicPlayer.button("Back", "", x() + w() - 66, top + h() - 25, 56, this::onClose));
    }

    private void openFolder() {
        if (opening) return;
        opening = true;
        openFailure = null;
        actionStatus = "Opening music folder…";
        MusicLibrary.openMusicFolder().whenComplete((ignored, error) -> minecraft.execute(() -> {
            opening = false;
            if (error == null) {
                actionStatus = "Folder open request sent.";
                openButton.setTooltip(Tooltip.create(Component.literal("Open the active profile's library")));
            } else {
                Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                String message = cause.getLocalizedMessage();
                openFailure = message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
                actionStatus = "Use Copy Path or check latest.log.";
                openButton.setTooltip(Tooltip.create(Component.literal(openFailure)));
            }
        }));
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        int left = x() + 10, top = y(), textWidth = w() - 20;
        drawPanel(g, width, height, x(), top, w(), h());
        g.text(font, getTitle(), left, top + 11, StudioUi.text());
        var folder = MusicLibrary.getMusicDirectory();
        String path = folder == null ? "Library is loading…" : folder.toString();
        g.text(font, StudioUi.fit(font, path, textWidth), left, top + 35, StudioUi.muted());
        var report = MusicLibrary.lastScan();
        g.text(font, report.available() + " tracks / " + report.folderFiles() + " in music folder", left, top + 46, StudioUi.text());
        var scan = MusicLibrary.lastScannedAt();
        String scanText = MusicLibrary.isScanning() ? "Scanning…" : scan == null ? "Waiting for first scan"
                : report.error() != null ? "Scan failed: " + report.error() : "Last scan " + TIME.format(scan);
        g.text(font, StudioUi.fit(font, scanText, textWidth), left, top + 57, StudioUi.muted());
        if (openFailure != null) {
            g.text(font, StudioUi.fit(font, openFailure, textWidth - 60), left, top + h() - 28, StudioUi.danger());
        }
        if (!actionStatus.isBlank()) g.text(font, StudioUi.fit(font, actionStatus, textWidth - 60),
                left, top + h() - (openFailure == null ? 20 : 17), StudioUi.text());
        super.extractRenderState(g, mx, my, delta);
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
