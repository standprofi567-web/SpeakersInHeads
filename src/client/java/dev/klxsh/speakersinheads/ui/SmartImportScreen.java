package dev.klxsh.speakersinheads.ui;

import dev.klxsh.speakersinheads.music.AuthorizedImport;
import dev.klxsh.speakersinheads.music.MetadataProviders;
import dev.klxsh.speakersinheads.music.MusicCatalog;
import dev.klxsh.speakersinheads.music.MusicLibrary;
import dev.klxsh.speakersinheads.music.MusicLink;
import dev.klxsh.speakersinheads.music.MusicTrack;
import dev.klxsh.speakersinheads.music.TrackMetadata;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.awt.EventQueue;
import java.awt.FileDialog;
import java.awt.Frame;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

final class SmartImportScreen extends StudioScreen {
    @FunctionalInterface private interface Task<T> { T run(IntConsumer progress) throws IOException; }

    private final Screen parent;
    private final Consumer<MusicTrack> play;
    private ServiceArtwork artwork;
    private String url = "", title = "", artist = "", recognized = "", status = "Paste a track link or choose your own audio.";
    private TrackMetadata metadata;
    private AuthorizedImport.Preview preview;
    private MusicTrack imported;
    private MusicLink link;
    private Path file;
    private MusicCatalog.Entry licensed;
    private List<MusicTrack> local = List.of();
    private List<MusicCatalog.Entry> alternatives = List.of();
    private Thread request;
    private volatile int epoch, progress;
    private boolean downloading, picking;
    private boolean open;
    private int renderedHeight;
    private int compactMatch;
    private final java.util.List<Button> sourceButtons = new java.util.ArrayList<>();
    private EditBox titleBox, artistBox;

    SmartImportScreen(Screen parent, Consumer<MusicTrack> play) {
        super(Component.literal("Smart Music Import"));
        this.parent = parent;
        this.play = play;
    }

    private int panelWidth() { return Math.min(408, width - 16); }
    private int panelHeight() { return renderedHeight; }
    private int left() { return (width - panelWidth()) / 2; }
    private int top() { return (height - panelHeight()) / 2; }

    @Override protected void init() {
        open = true;
        if (artwork == null) artwork = new ServiceArtwork();
        rebuild();
    }

    private Button button(String label, int x, int y, int w, boolean active, Runnable action) {
        var widget = StudioUi.button(Component.literal(label), ignored -> action.run()).bounds(x, y, w, 17).build();
        widget.active = active;
        return addRenderableWidget(widget);
    }

    private EditBox edit(String hint, String value, int x, int y, int w, int max, Consumer<String> change) {
        var field = new EditBox(font, x, y, w, 17, Component.literal(hint));
        field.setMaxLength(max);
        field.setHint(Component.literal(hint));
        field.setValue(value);
        field.setResponder(change);
        return addRenderableWidget(field);
    }

    private void rebuild() {
        clearWidgets();
        int rows = Math.min(3, Math.max(local.size(), alternatives.size()));
        renderedHeight = Math.min(link == null && file == null && rows == 0 ? 174 : 190 + 18 * rows, height - 8);
        int x = left() + 8, y = top(), w = panelWidth() - 16, h = panelHeight();
        int footer = y + h - 23;
        boolean idle = request == null;
        sourceButtons.clear();
        titleBox = artistBox = null;
        var field = edit("Paste Music Link", url, x, y + 35, w - 87, 2048, value -> {
            url = value;
            try { recognized = MusicLink.parse(value).service().label(); }
            catch (IllegalArgumentException invalid) { recognized = ""; }
            if (link != null) {
                link = null;
                metadata = null;
                preview = null;
                imported = null;
                local = List.of();
                alternatives = List.of();
                licensed = null;
                file = null;
                title = artist = "";
                status = "Analyze the new link to check its source.";
                for (var action : sourceButtons) {
                    action.active = false;
                    action.visible = false;
                }
                if (titleBox != null) titleBox.visible = false;
                if (artistBox != null) artistBox.visible = false;
            }
        });
        button("Analyze Link", x + w - 83, y + 35, 83, idle, this::inspect);
        field.setEditable(idle);
        int half = (w - 4) / 2;
        boolean providerLink = link != null && link.service() != MusicLink.Service.DIRECT;
        if (providerLink || file != null) {
            titleBox = edit("Track title", title, x, y + 66, half, 180, value -> title = value);
            artistBox = edit("Artist", artist, x + half + 4, y + 66, w - half - 4, 180, value -> artist = value);
            titleBox.setEditable(idle);
            artistBox.setEditable(idle);
        }
        int localWidth = 100;
        int actionY = y + 111;
        button("Import Local File", x, actionY, localWidth, idle && !picking, this::choose);
        if (link != null) {
            sourceButtons.add(button("Open Original", x + w - 68, actionY, 68, idle,
                    () -> ConfirmLinkScreen.confirmLinkNow(this, link.original())));
        }
        if (providerLink) {
            int remaining = w - localWidth - 76;
            boolean licensedSearch = MusicCatalog.jamendoConfigured();
            int searchWidth = licensedSearch ? (remaining - 4) / 2 : Math.min(82, remaining);
            sourceButtons.add(button("Find Local", x + localWidth + 4, actionY, searchWidth, idle, this::findLocal));
            if (licensedSearch) {
                sourceButtons.add(button("Find Licensed", x + localWidth + searchWidth + 8, actionY,
                        searchWidth, idle, this::match));
            }
        }
        int maxRows = Math.max(0, (h - 180) / 18);
        for (int i = 0; i < maxRows && i < local.size(); i++) {
            var track = local.get(i);
            sourceButtons.add(button(StudioUi.fit(font, "Play local / " + track.title(), w - 8), x, y + 132 + 18 * i, w, idle,
                    () -> { play.accept(track); status = "Playback requested / " + track.title(); }));
        }
        for (int i = 0; i < maxRows && i < alternatives.size(); i++) {
            var entry = alternatives.get(i);
            sourceButtons.add(button(StudioUi.fit(font, entry.artist() + " / " + entry.title() + " / " + entry.detail(), w - 8),
                    x, y + 132 + 18 * i, w, idle && !entry.equals(licensed), () -> {
                        licensed = entry;
                        file = null;
                        status = "Jamendo recording selected. Review the source before importing.";
                        rebuild();
                    }));
        }
        if (imported != null) {
            sourceButtons.add(button("Play", x, footer, 68, idle, () -> {
                if (imported != null) { play.accept(imported); onClose(); }
            }));
        } else if (availability().audio() == SmartImportStatus.Audio.DOWNLOAD
                || availability().audio() == SmartImportStatus.Audio.LOCAL) {
            sourceButtons.add(button(file == null ? "Download & Import" : "Import Selected File", x, footer, 112, idle, this::importAudio));
        } else if (maxRows == 0 && !local.isEmpty()) {
            var candidate = local.get(Math.min(compactMatch, local.size() - 1));
            var playMatch = button("Play " + candidate.title(), x, footer, Math.min(140, w - 113), idle, () -> {
                play.accept(candidate);
                onClose();
            });
            sourceButtons.add(playMatch);
            playMatch.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(candidate.title())));
            if (local.size() > 1) sourceButtons.add(button("Next match", x + Math.min(144, w - 109), footer, 58, idle, () -> {
                compactMatch = (compactMatch + 1) % local.size();
                rebuild();
            }));
        }
        if (!idle) button("Cancel", x + w - 104, footer, 49, true, () -> {
            cancel();
            status = "Request cancelled.";
            rebuild();
        });
        button("Back", x + w - 51, footer, 51, true, this::onClose);
    }

    private boolean validDirect() {
        return link != null && link.service() == MusicLink.Service.DIRECT && preview != null;
    }

    private SmartImportStatus availability() {
        return SmartImportStatus.resolve(link != null, link != null && link.service() != MusicLink.Service.DIRECT,
                metadata != null && !metadata.title().isBlank(), request != null && !downloading && metadata == null,
                validDirect() || (licensed != null && licensed.canImport()), file != null, !local.isEmpty(), imported != null);
    }

    private void findLocal() {
        if (title.isBlank()) {
            status = "Enter a title to search your library.";
            return;
        }
        local = AuthorizedImport.localMatches(artist, title);
        alternatives = List.of();
        compactMatch = 0;
        status = local.isEmpty() ? "No matching title and artist in the library."
                : artist.isBlank() ? "Matches by title only. Confirm the recording." : "Select your recording.";
        rebuild();
    }

    private void inspect() {
        MusicLink resolved;
        try { resolved = MusicLink.parse(url); }
        catch (IllegalArgumentException error) {
            link = null;
            metadata = null;
            preview = null;
            imported = null;
            local = List.of();
            alternatives = List.of();
            licensed = null;
            status = error.getMessage();
            rebuild();
            return;
        }
        recognized = resolved.service().label();
        link = resolved;
        metadata = null;
        preview = null;
        imported = null;
        file = null;
        licensed = null;
        local = List.of();
        compactMatch = 0;
        alternatives = List.of();
        title = "";
        artist = "";
        if (resolved.service() == MusicLink.Service.DIRECT) {
            submit("Checking the audio source…", false, ignored -> AuthorizedImport.preview(resolved), result -> {
                preview = result;
                status = "Import only audio you have permission to copy.";
            });
        } else {
            submit("Looking up metadata", false, ignored -> MetadataProviders.resolve(resolved), result -> {
                metadata = result.metadata();
                if (!metadata.title().isBlank()) title = metadata.title();
                if (!metadata.artist().isBlank()) artist = metadata.artist();
                if (result.source() != null && result.source().canImport()) licensed = result.source();
                local = AuthorizedImport.localMatches(artist, title);
                if (local.isEmpty()) {
                    status = metadata.status().isBlank() ? "Choose your own recording with Import Local File." : metadata.status();
                } else {
                    status = artist.isBlank() ? "Matches by title only. Confirm the recording before playing."
                            : "Select the matching recording in your library.";
                }
            });
        }
    }

    private void match() {
        String currentArtist = artist.strip(), currentTitle = title.strip();
        if (currentArtist.isBlank() || currentTitle.isBlank()) {
            status = "Enter both artist and title to look for a licensed match.";
            return;
        }
        long duration = metadata == null ? 0 : metadata.seconds();
        submit("Looking for permitted Jamendo matches", false,
                ignored -> AuthorizedImport.licensedMatches(currentArtist, currentTitle, duration), found -> {
                    local = List.of();
                    alternatives = found;
                    status = found.isEmpty() ? "No downloadable exact-name match." : "Choose the Jamendo recording before importing.";
                });
    }

    void choose() {
        picking = true;
        int generation = epoch;
        rebuild();
        EventQueue.invokeLater(() -> {
            Path selected = null;
            try {
                FileDialog dialog = new FileDialog((Frame) null, "Choose your own recording", FileDialog.LOAD);
                try {
                    dialog.setVisible(true);
                    if (dialog.getFile() != null) selected = Path.of(dialog.getDirectory(), dialog.getFile());
                } finally { dialog.dispose(); }
            } catch (RuntimeException ignored) {
                // File drag and drop remains available if the OS dialog fails.
            }
            Path chosen = selected;
            Minecraft.getInstance().execute(() -> {
                picking = false;
                if (!open || generation != epoch) return;
                if (chosen != null) selectFile(chosen);
                else rebuild();
            });
        });
    }

    @Override public void onFilesDrop(List<Path> files) {
        if (!files.isEmpty() && request == null) selectFile(files.getFirst());
    }

    private void selectFile(Path path) {
        file = path;
        imported = null;
        licensed = null;
        local = List.of();
        alternatives = List.of();
        status = "Selected local source / " + path.getFileName();
        rebuild();
    }

    private void importAudio() {
        if (file == null && licensed == null && !validDirect()) return;
        var library = MusicLibrary.getMusicDirectory();
        if (library == null) { status = "The local library is still loading."; return; }
        var recording = file;
        var source = licensed;
        var original = link;
        String name = title.strip(), performer = artist.strip();
        submit("Importing audio", true, update -> {
            var result = recording != null ? AuthorizedImport.local(recording, performer, name, library, update)
                    : source != null ? MusicCatalog.download(source, library, update)
                    : AuthorizedImport.direct(original, performer, name, library, update);
            MusicLibrary.refreshImported(result.file());
            return result;
        }, result -> {
            status = (result.existing() ? "Already in library / " : "Imported / ") + result.quality() + " / " + sizeLabel(result.bytes());
            imported = new MusicTrack(result.file(), result.file().getFileName().toString(), true, MusicTrack.Source.FOLDER);
            local = List.of();
            alternatives = List.of();
        });
    }

    private <T> void submit(String label, boolean audio, Task<T> task, Consumer<T> complete) {
        if (request != null) return;
        int generation = ++epoch;
        progress = 0;
        downloading = audio;
        status = label;
        request = Thread.ofVirtual().name("sih-smart-import").start(() -> {
            try {
                T result = task.run(value -> { if (epoch == generation) progress = value; });
                finish(generation, () -> complete.accept(result));
            } catch (IOException | RuntimeException failure) {
                finish(generation, () -> status = MusicCatalog.safeMessage(failure));
            }
        });
        rebuild();
    }

    private void finish(int generation, Runnable action) {
        Minecraft.getInstance().execute(() -> {
            if (generation != epoch) return;
            request = null;
            downloading = false;
            action.run();
            rebuild();
        });
    }

    private void cancel() {
        epoch++;
        if (request != null) request.interrupt();
        request = null;
        downloading = false;
    }

    @Override public void tick() {
        if (metadata == null || metadata.artwork() == null || link == null) return;
        var entry = metadata;
        var source = link;
        artwork.request(entry.artwork().toString(), () -> MetadataProviders.loadArtwork(source, entry));
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        int x = left(), y = top(), w = panelWidth(), h = panelHeight();
        drawPanel(g, width, height, x, y, w, h);
        g.text(font, getTitle(), x + 8, y + 9, StudioUi.text());
        var available = availability();
        String source = file != null ? "Local audio file" : recognized;
        String metadataLabel = source.isBlank() ? available.metadata() : source
                + (available.metadata().isBlank() ? "" : " / " + available.metadata());
        if (!metadataLabel.isBlank()) g.text(font, StudioUi.fit(font, metadataLabel, w - 16), x + 8, y + 55, StudioUi.accent());
        g.text(font, StudioUi.fit(font, available.audio().label, w - 60), x + 8, y + 87, StudioUi.text());
        if (preview != null && imported == null) {
            String format = preview.format().isBlank() ? "Format verified on import" : preview.format() + " (source header)";
            g.text(font, StudioUi.fit(font, format + " / " + sizeLabel(preview.bytes()), w - 60),
                    x + 8, y + 98, StudioUi.muted());
        }
        if (metadata != null) {
            String detail = metadata.album();
            if (metadata.year() > 0) detail += " / " + metadata.year();
            if (metadata.seconds() > 0) detail += " / " + MusicMixerScreen.formatTime(metadata.seconds());
            if (detail.isBlank() && !metadata.title().isBlank() && metadata.artist().isBlank()) detail = "Artist unavailable from provider.";
            if (!detail.isBlank()) g.text(font, StudioUi.fit(font, detail, w - 62), x + 8, y + 98, StudioUi.muted());
            if (metadata.artwork() != null && artwork != null) {
                var texture = artwork.find(metadata.artwork().toString());
                if (texture != null) ServiceArtwork.draw(g, texture, x + w - 42, y + 83, 20);
            }
        }
        String amount = !downloading ? "" : progress >= 0 ? " / " + progress + "%"
                : String.format(java.util.Locale.ROOT, " / %.1f MiB", -progress / 1048576.0);
        String message = status + amount;
        String line = font.plainSubstrByWidth(message, w - 16);
        int statusY = y + h - 35;
        if (line.length() < message.length() && h >= 198) {
            g.text(font, line.stripTrailing(), x + 8, statusY - 9, StudioUi.text());
            g.text(font, StudioUi.fit(font, message.substring(line.length()).stripLeading(), w - 16),
                    x + 8, statusY, StudioUi.muted());
        } else {
            g.text(font, StudioUi.fit(font, message, w - 16), x + 8, statusY, StudioUi.text());
        }
        super.extractRenderState(g, mx, my, delta);
    }

    static String sizeLabel(long bytes) {
        if (bytes < 0) return "Size unknown";
        return String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / 1048576.0);
    }

    @Override public void removed() {
        open = false;
        if (request != null) status = "Request cancelled.";
        cancel();
        if (artwork != null) artwork.close();
        artwork = null;
        super.removed();
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { Minecraft.getInstance().gui.setScreen(parent); }
}
