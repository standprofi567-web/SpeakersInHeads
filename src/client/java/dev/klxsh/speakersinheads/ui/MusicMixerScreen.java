package dev.klxsh.speakersinheads.ui;

import dev.klxsh.speakersinheads.audio.PhysicalStereoPair;
import dev.klxsh.speakersinheads.audio.SpeakerAudioManager;
import dev.klxsh.speakersinheads.audio.SpeakerGroup;
import dev.klxsh.speakersinheads.music.AudioMetadataResolver;
import dev.klxsh.speakersinheads.music.MusicLibrary;
import dev.klxsh.speakersinheads.music.MusicTrack;
import dev.klxsh.speakersinheads.music.PlaybackSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Optional;
import java.util.function.DoubleConsumer;

/**
 * Compact, refined music player and library mixer screen for player heads.
 * Supports smooth Drag-to-Seek with instant visual scrubber preview,
 * Next/Previous track navigation, and synchronized volume controls.
 */
public final class MusicMixerScreen extends StudioScreen {
    private static final int PANEL_WIDTH = 420;
    private static final int PANEL_HEIGHT = 304;

    private final BlockPos headPosition;
    private final String headType;
    private List<MusicTrack> displayedTracks = List.of();
    private MusicTrack selectedTrack;
    private MusicTrack renderedTrack;
    private final java.util.List<Button> trackRows = new java.util.ArrayList<>();
    private List<MusicTrack> matchingTracks = List.of();
    private String query = "";
    private boolean firstRefreshRequested;
    private int scrollOffset;
    private float masterVolume = 1.0f;
    private float headVolume = 1.0f;
    private String uiError;
    private Button transport;
    private boolean pairVolumeChanged;

    // Drag-to-Seek state
    private boolean isDraggingSeek = false;
    private double previewSeekSeconds = 0.0;

    public MusicMixerScreen(BlockPos headPosition, String headType) {
        super(Component.literal("Music Mixer"));
        this.headPosition = headPosition.immutable();
        this.headType = headType;
        this.masterVolume = SpeakerAudioManager.masterVolume();
        this.headVolume = SpeakerAudioManager.headVolume(headPosition);
    }

    @Override
    protected void init() {
        masterVolume = SpeakerAudioManager.masterVolume();
        headVolume = SpeakerAudioManager.headVolume(headPosition);
        int panelWidth = Math.min(PANEL_WIDTH, width - 20);
        int panelHeight = Math.min(PANEL_HEIGHT, height - 12);
        int left = (width - panelWidth) / 2;
        int top = (height - panelHeight) / 2;
        int x = left + 14, w = panelWidth - 28;
        int y = top + 55;
        int small = Math.min(55, (w - 104) / 4);
        addRenderableWidget(UnifiedMusicPlayer.button("|<", "Previous track", x, y, small, this::playPrevious));
        transport = addRenderableWidget(UnifiedMusicPlayer.button("Play", "Control the current speaker session",
                x + small + 4, y, w - small * 3 - 12, this::toggleTransport));
        addRenderableWidget(UnifiedMusicPlayer.button("Stop", "Stop current playback", x + w - small * 2 - 4, y,
                small, () -> SpeakerAudioManager.stop(headPosition)));
        addRenderableWidget(UnifiedMusicPlayer.button(">|", "Next track", x + w - small, y, small, this::playNext));

        y += 22;
        int modeWidth = Math.min(82, (w - 90) / 3);
        int volumeWidth = w - modeWidth * 2 - 8;
        var group = SpeakerAudioManager.getSpeakerGroup(headPosition).orElse(null);
        var pair = SpeakerAudioManager.getStereoPair(headPosition).orElse(null);
        String volumeLabel = group != null ? "Group volume" : pair != null ? "Pair volume" : "Head volume";
        float volume = group != null ? group.volume() : pair != null ? pair.pairMasterVolume() : headVolume;
        addRenderableWidget(new VolumeSlider(x, y, volumeWidth, volumeLabel, volume, value -> {
                    if (group != null) {
                        SpeakerAudioManager.setGroupVolume(headPosition, (float) value);
                        groupVolumeChanged = true;
                    } else if (pair != null) {
                        SpeakerAudioManager.setPairVolume(headPosition, (float) value);
                        pairVolumeChanged = true;
                    } else setHeadVolume((float) value);
                }));
        addRenderableWidget(UnifiedMusicPlayer.button(repeatLabel(), "Cycle repeat mode", x + volumeWidth + 4,
                y, modeWidth, () -> {
                    PlaybackSettings.toggleRepeatMode();
                    clearWidgets();
                    init();
                }));
        addRenderableWidget(UnifiedMusicPlayer.button(PlaybackSettings.isShuffle() ? "Shuffle on" : "Shuffle off",
                "Toggle shuffled playback", x + w - modeWidth, y, modeWidth, () -> {
                    PlaybackSettings.toggleShuffle();
                    clearWidgets();
                    init();
                }));

        int footer = top + panelHeight - 23;
        int unit = (w - 12) / 4;
        if (group != null) {
            addRenderableWidget(UnifiedMusicPlayer.button("Members", group.name(), x, footer, unit, () ->
                    minecraft.gui.setScreen(new SpeakerGroupScreen(this, headPosition, headType))));
            addRenderableWidget(UnifiedMusicPlayer.button("Sync Now", "Align members to the group timeline", x + unit + 4,
                    footer, unit, () -> SpeakerAudioManager.resyncGroup(headPosition)));
        } else if (pair != null) {
            addRenderableWidget(UnifiedMusicPlayer.button("Swap L/R", "Swap stereo channels", x, footer, unit, () -> {
                SpeakerAudioManager.swapStereoPair(headPosition);
                clearWidgets();
                init();
            }));
            addRenderableWidget(UnifiedMusicPlayer.button("Unlink", "Unlink the physical stereo pair", x + unit + 4,
                    footer, unit, () -> {
                        SpeakerAudioManager.unlinkStereoPair(headPosition);
                        clearWidgets();
                        init();
                    }));
        } else {
            addRenderableWidget(UnifiedMusicPlayer.button("Group", "Create or manage a speaker group", x, footer, unit, () ->
                    minecraft.gui.setScreen(new SpeakerGroupScreen(this, headPosition, headType))));
            addRenderableWidget(UnifiedMusicPlayer.button("Pair", "Link a physical stereo pair", x + unit + 4, footer, unit, () ->
                    minecraft.gui.setScreen(new StereoPairSetupScreen(this, headPosition, headType))));
        }
        addRenderableWidget(UnifiedMusicPlayer.button("Settings", "Audio, groups, themes and library", x + 2 * (unit + 4),
                footer, unit, () -> minecraft.gui.setScreen(new SettingsScreen(this, headPosition, headType))));
        addRenderableWidget(UnifiedMusicPlayer.button("Back", "", x + 3 * (unit + 4), footer, unit, this::onClose));

        if (!firstRefreshRequested) {
            firstRefreshRequested = true;
            MusicLibrary.refresh();
        }
        int searchY = trackListY() - 21;
        var search = new EditBox(font, x, searchY, w - 108, 17, Component.literal("Search library"));
        search.setMaxLength(160);
        search.setHint(Component.literal("Search library"));
        search.setValue(query);
        search.setResponder(value -> {
            query = value;
            scrollOffset = 0;
            refreshRows();
        });
        addRenderableWidget(search);
        addRenderableWidget(UnifiedMusicPlayer.button("SMI", "Smart Music Import", x + w - 103, searchY, 39,
                () -> minecraft.gui.setScreen(new SmartImportScreen(this, this::playServiceTrack))));
        addRenderableWidget(UnifiedMusicPlayer.button("Manage", "Add, refresh or remove library entries", x + w - 60,
                searchY, 60, () -> minecraft.gui.setScreen(new LibraryActionsScreen(this, selectedTrack, this::playServiceTrack))));
        rebuildTrackList(left, panelWidth);
        updateTransport();
    }

    private boolean groupVolumeChanged;

    private static String repeatLabel() {
        return switch (PlaybackSettings.repeatMode()) {
            case OFF -> "Repeat off";
            case REPEAT_ALL -> "Repeat all";
            case REPEAT_ONE -> "Repeat one";
        };
    }

    private void toggleTransport() {
        switch (UnifiedMusicPlayer.action(SpeakerAudioManager.state(headPosition))) {
            case PAUSE -> SpeakerAudioManager.pause(headPosition);
            case RESUME -> SpeakerAudioManager.resume(headPosition);
            case PLAY -> playSelected();
        }
        updateTransport();
    }

    private void updateTransport() {
        if (transport != null) {
            UnifiedMusicPlayer.update(transport, SpeakerAudioManager.state(headPosition),
                    selectedTrack != null && selectedTrack.available());
        }
    }

    private void refreshRows() {
        int w = Math.min(PANEL_WIDTH, width - 20);
        trackRows.forEach(this::removeWidget);
        rebuildTrackList((width - w) / 2, w);
        updateTransport();
    }
    private void rebuildTrackList(int left, int panelWidth) {
        trackRows.clear();
        displayedTracks = MusicLibrary.tracks();
        renderedTrack = SpeakerAudioManager.currentTrack(headPosition);
        String filter = query.strip().toLowerCase(java.util.Locale.ROOT);
        matchingTracks = filter.isEmpty() ? displayedTracks : displayedTracks.stream()
                .filter(track -> track.title().toLowerCase(java.util.Locale.ROOT).contains(filter)
                        || track.fileName().toLowerCase(java.util.Locale.ROOT).contains(filter)).toList();
        if (selectedTrack != null) {
            selectedTrack = displayedTracks.stream()
                    .filter(track -> samePath(track, selectedTrack))
                    .findFirst().orElse(selectedTrack);
        }

        int visible = visibleRows();
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, matchingTracks.size() - visible)));

        int listTop = trackListY();
        int rowHeight = 18;
        int rowWidth = panelWidth - 28;

        for (int i = 0; i < visible && (scrollOffset + i) < matchingTracks.size(); i++) {
            MusicTrack track = matchingTracks.get(scrollOffset + i);
            boolean isCurrent = isPlayingTrack(track);
            boolean isSelected = track.equals(selectedTrack);

            String prefix = isCurrent ? "▶ " : (isSelected ? "● " : "  ");
            String status = track.available() ? "" : " [Missing]";
            double trackDur = AudioMetadataResolver.getDurationSeconds(track.path());
            String durStr = trackDur > 0 ? "  (" + formatTime(trackDur) + ")" : "";
            String label = prefix + track.title() + status + durStr;

            Button rowBtn = StudioUi.button(Component.literal(label), button -> {
                if (track.equals(selectedTrack) && !isPlayingTrack(track)) playSelected();
                selectedTrack = track;
                refreshRows();
            }).bounds(left + 14, listTop + i * rowHeight, rowWidth, rowHeight - 1).build();

            rowBtn.setTooltip(Tooltip.create(Component.literal(
                    track.fileName() + "\nSource: " + (track.source() == MusicTrack.Source.FOLDER ? "Folder" : "External")
                            + "\nPath: " + track.path().toString()
            )));
            trackRows.add(rowBtn);
            addRenderableWidget(rowBtn);
        }
    }

    private void playNext() {
        if (displayedTracks.isEmpty()) return;
        MusicTrack next = PlaybackSettings.getNextTrack(transportTrack(), displayedTracks, true);
        if (next != null) {
            selectedTrack = next;
            playSelected();
            clearWidgets();
            init();
        }
    }

    private void playPrevious() {
        if (displayedTracks.isEmpty()) return;
        MusicTrack prev = PlaybackSettings.getPreviousTrack(transportTrack(), displayedTracks);
        if (prev != null) {
            selectedTrack = prev;
            playSelected();
            clearWidgets();
            init();
        }
    }

    private MusicTrack transportTrack() {
        MusicTrack playing = SpeakerAudioManager.currentTrack(headPosition);
        return playing == null ? selectedTrack : playing;
    }

    private boolean isPlayingTrack(MusicTrack track) {
        MusicTrack active = SpeakerAudioManager.currentTrack(headPosition);
        return active != null && samePath(track, active);
    }

    @Override
    public void tick() {
        super.tick();
        updateTransport();
        List<MusicTrack> latest = MusicLibrary.tracks();
        if (!displayedTracks.equals(latest) || !java.util.Objects.equals(renderedTrack, SpeakerAudioManager.currentTrack(headPosition))) {
            refreshRows();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int listTop = trackListY();
        int listBottom = listTop + visibleRows() * 18;
        if (scrollY != 0 && mouseY >= listTop && mouseY <= listBottom) {
            int max = Math.max(0, matchingTracks.size() - visibleRows());
            int next = Math.max(0, Math.min(max, scrollOffset + (scrollY < 0 ? 1 : -1)));
            if (next != scrollOffset) {
                scrollOffset = next;
                refreshRows();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isClick) {
        int panelWidth = Math.min(PANEL_WIDTH, width - 20);
        int panelHeight = Math.min(PANEL_HEIGHT, height - 12);
        int left = (width - panelWidth) / 2;
        int top = (height - panelHeight) / 2;

        int progressX = left + 14;
        int progressY = top + 47;
        int progressW = panelWidth - 28;

        double mouseX = event.x();
        double mouseY = event.y();

        if (event.button() == 0 && mouseX >= progressX && mouseX <= progressX + progressW
                && mouseY >= progressY - 4 && mouseY <= progressY + 8) {
            double total = getTotalDuration();
            if (total > 0) {
                isDraggingSeek = true;
                double ratio = Math.max(0.0, Math.min(1.0, (mouseX - progressX) / (double) progressW));
                previewSeekSeconds = ratio * total;
                return true;
            }
        }
        return super.mouseClicked(event, isClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (isDraggingSeek && event.button() == 0) {
            int panelWidth = Math.min(PANEL_WIDTH, width - 20);
            int left = (width - panelWidth) / 2;
            int progressX = left + 14;
            int progressW = panelWidth - 28;

            double total = getTotalDuration();
            if (total > 0) {
                double ratio = Math.max(0.0, Math.min(1.0, (event.x() - progressX) / (double) progressW));
                previewSeekSeconds = ratio * total;
                return true;
            }
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (isDraggingSeek && event.button() == 0) {
            isDraggingSeek = false;
            SpeakerAudioManager.seek(headPosition, previewSeekSeconds);
            return true;
        }
        return super.mouseReleased(event);
    }

    private double getTotalDuration() {
        double total = SpeakerAudioManager.totalDurationSeconds(headPosition);
        MusicTrack current = SpeakerAudioManager.currentTrack(headPosition);
        if (total <= 0 && current != null) {
            total = AudioMetadataResolver.getDurationSeconds(current.path());
        }
        if (total <= 0 && selectedTrack != null) {
            total = AudioMetadataResolver.getDurationSeconds(selectedTrack.path());
        }
        return total;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float deltaTracker) {
        int panelWidth = Math.min(PANEL_WIDTH, width - 20);
        int panelHeight = Math.min(PANEL_HEIGHT, height - 12);
        int left = (width - panelWidth) / 2;
        int top = (height - panelHeight) / 2;

        // Background overlay & main panel
        drawPanel(graphics, width, height, left, top, panelWidth, panelHeight);

        // Borders

        // Header
        graphics.text(font, "Music Player", left + 14, top + 10, StudioUi.text());
        Optional<PhysicalStereoPair> currentPair = SpeakerAudioManager.getStereoPair(headPosition);
        String headInfo = headType + " (" + headPosition.getX() + ", " + headPosition.getY() + ", " + headPosition.getZ() + ")";
        var group = SpeakerAudioManager.getSpeakerGroup(headPosition).orElse(null);
        if (group != null) {
            headInfo = "Group Playback / " + group.name() + " / " + group.size() + " speakers";
        } else if (currentPair.isPresent()) {
            PhysicalStereoPair pair = currentPair.get();
            headInfo = "Stereo Pair / " + pair.name() + " / " + (pair.isLeft(headPosition) ? "Left" : "Right");
        }
        graphics.text(font, StudioUi.fit(font, headInfo, panelWidth - 28), left + 14, top + 21, StudioUi.muted());
        // Now Playing & Status line with exact duration
        MusicTrack current = SpeakerAudioManager.currentTrack(headPosition);
        String currentTitle = current == null ? "Nothing playing" : current.title();
        String playerState = uiError != null ? "Error: " + uiError : SpeakerAudioManager.state(headPosition);
        double seconds = isDraggingSeek ? previewSeekSeconds : SpeakerAudioManager.secondsPlayed(headPosition);
        double total = getTotalDuration();

        String seekNote = isDraggingSeek ? " [Seeking...]" : "";
        String timeDisplay = formatTime(seconds) + " / " + (total > 0 ? formatTime(total) : "--:--") + seekNote;
        String statusLine = currentTitle + " / " + playerState;

        int statusColor = playerState.startsWith("Error") ? StudioUi.danger() : StudioUi.text();
        int timeWidth = font.width(timeDisplay);
        graphics.text(font, StudioUi.fit(font, statusLine, panelWidth - 36 - timeWidth), left + 14, top + 34, statusColor);
        graphics.text(font, timeDisplay, left + panelWidth - 14 - timeWidth, top + 34, StudioUi.muted());

        // Progress bar (interactive seek target & visual scrubber)
        int progressX = left + 14;
        int progressY = top + 47;
        int progressW = panelWidth - 28;
        graphics.fill(progressX, progressY, progressX + progressW, progressY + 4, StudioUi.control());

        if (total > 0 && seconds > 0) {
            int filled = (int) Math.min(progressW, (seconds / total) * progressW);
            int barColor = StudioUi.accent();
            graphics.fill(progressX, progressY, progressX + filled, progressY + 4, barColor);
            graphics.fill(progressX + filled - 1, progressY - 1, progressX + filled + 2, progressY + 5, StudioUi.text());
        } else if (seconds > 0) {
            int filled = (int) ((seconds * 12) % progressW);
            graphics.fill(progressX, progressY, progressX + filled, progressY + 4, StudioUi.accent());
        }

        int listTop = trackListY();
        // Empty library notice
        if (matchingTracks.isEmpty() && !MusicLibrary.isScanning()) {
            graphics.centeredText(font, query.isBlank() ? "Add music to start listening." : "No tracks match your search.",
                    width / 2, listTop + 5, StudioUi.muted());
        }

        // Scroll indicator
        if (matchingTracks.size() > visibleRows() && panelHeight - 33 >= 119 + visibleRows() * 18) {
            String scrollText = (scrollOffset + 1) + "–"
                    + Math.min(matchingTracks.size(), scrollOffset + visibleRows())
                    + " of " + matchingTracks.size();
            graphics.centeredText(font, scrollText, width / 2, top + panelHeight - 33, StudioUi.muted());
        }

        super.extractRenderState(graphics, mouseX, mouseY, deltaTracker);
    }

    private void playSelected() {
        if (selectedTrack == null || !selectedTrack.available()) return;
        try {
            SpeakerAudioManager.play(selectedTrack, headPosition, masterVolume, headVolume);
            uiError = null;
        } catch (RuntimeException exception) {
            uiError = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        }
    }

    private void playServiceTrack(MusicTrack track) {
        selectedTrack = track;
        playSelected();
    }

    private void setHeadVolume(float value) {
        headVolume = value;
        SpeakerAudioManager.setGain(headPosition, masterVolume, headVolume);
    }

    private boolean samePath(MusicTrack first, MusicTrack second) {
        return first.path().toAbsolutePath().normalize().equals(second.path().toAbsolutePath().normalize());
    }

    public static String formatTime(double seconds) {
        int total = Math.max(0, (int) Math.round(seconds));
        return String.format("%02d:%02d", total / 60, total % 60);
    }

    @Override
    public void removed() {
        if (pairVolumeChanged) {
            dev.klxsh.speakersinheads.audio.StereoPairManager.save();
            pairVolumeChanged = false;
        }
        if (groupVolumeChanged) {
            dev.klxsh.speakersinheads.audio.SpeakerGroupManager.save();
            groupVolumeChanged = false;
        }
        super.removed();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(new SpeakersInHeadsScreen(headPosition, headType));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int trackListY() {
        int panelHeight = Math.min(PANEL_HEIGHT, height - 12);
        int top = (height - panelHeight) / 2;
        return top + 119;
    }

    private int visibleRows() {
        return Math.max(0, (Math.min(PANEL_HEIGHT, height - 12) - 149) / 18);
    }

    private static final class VolumeSlider extends StudioSlider {
        private final String label;
        private final DoubleConsumer consumer;

        private VolumeSlider(int x, int y, int width, String label, float initial, DoubleConsumer consumer) {
            super(x, y, width, 18, Component.empty(), initial);
            this.label = label;
            this.consumer = consumer;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(label + ": " + Math.round(value * 100) + "%"));
        }

        @Override
        protected void applyValue() {
            consumer.accept(value);
        }
    }
}
