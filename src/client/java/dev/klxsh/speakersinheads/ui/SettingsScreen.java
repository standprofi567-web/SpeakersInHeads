package dev.klxsh.speakersinheads.ui;

import dev.klxsh.speakersinheads.ClientPreferences;
import dev.klxsh.speakersinheads.audio.SpeakerAudioManager;
import dev.klxsh.speakersinheads.audio.SpeakerGroupManager;
import dev.klxsh.speakersinheads.music.MusicLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

final class SettingsScreen extends StudioScreen {
    private enum Page { SECTIONS, GROUPS, LIBRARY }
    private final Screen parent;
    private final BlockPos head;
    private final String type;
    private Page page = Page.SECTIONS;
    private boolean groupVolumeChanged;

    SettingsScreen(Screen parent, BlockPos head, String type) {
        super(Component.literal("Settings"));
        this.parent = parent;
        this.head = head;
        this.type = type;
    }

    private int w() { return Math.min(366, width - 16); }
    private int x() { return (width - w()) / 2; }
    private int y() { return (height - 154) / 2; }

    @Override protected void init() {
        int left = x() + 10, top = y(), contentWidth = w() - 20;
        if (page == Page.SECTIONS) {
            int col = (contentWidth - 6) / 2;
            button("Audio", "Minecraft volume category and output boost", left, top + 40, col,
                    () -> open(new AudioSettingsScreen(this)));
            button("Spatial", "Positioning, attenuation and animations", left + col + 6, top + 40, col,
                    () -> open(new SpatialSettingsScreen(this)));
            button("Acoustics", "SPR, walls and underwater filtering", left, top + 62, col,
                    () -> open(new AppearanceScreen(this, true)));
            button("Appearance", "Theme and interface motion", left + col + 6, top + 62, col,
                    () -> open(new AppearanceScreen(this, false)));
            button("Groups", "Playback and member controls for this speaker", left, top + 84, col, () -> show(Page.GROUPS));
            button("Library & Import", "Music folder, auto refresh and import", left + col + 6, top + 84, col,
                    () -> show(Page.LIBRARY));
        } else if (page == Page.GROUPS) {
            var group = SpeakerAudioManager.getSpeakerGroup(head).orElse(null);
            if (group != null) {
                addRenderableWidget(new StudioSlider(left, top + 56, contentWidth, 18,
                        groupVolume(group.volume()), group.volume()) {
                    @Override protected void updateMessage() { setMessage(groupVolume(value)); }
                    @Override protected void applyValue() {
                        SpeakerAudioManager.setGroupVolume(head, (float) value);
                        groupVolumeChanged = true;
                    }
                });
                int half = (contentWidth - 6) / 2;
                button("Sync Now", "Check and align the active speakers without restarting audio",
                        left, top + 80, half,
                        () -> SpeakerAudioManager.resyncGroup(head));
            }
            int half = (contentWidth - 6) / 2;
            button("Manage Members", "Manage the selected speaker's group", left + (group == null ? 0 : half + 6),
                    top + 80, group == null ? contentWidth : half, () -> open(new SpeakerGroupScreen(this, head, type)));
        } else {
            button("Auto Refresh / " + (MusicLibrary.autoRefresh() ? "On" : "Off"),
                    "Watch the managed music folder for added and removed audio", left, top + 57, contentWidth, () -> {
                        MusicLibrary.setAutoRefresh(!MusicLibrary.autoRefresh());
                        show(Page.LIBRARY);
                    });
            int col = (contentWidth - 6) / 2;
            button("Smart Music Import", "Analyze a link or import your own file", left, top + 80, col,
                    () -> open(new SmartImportScreen(this, track -> SpeakerAudioManager.play(
                            track, head, SpeakerAudioManager.masterVolume(), SpeakerAudioManager.headVolume(head)))));
            button("Refresh Library", "Scan the music folder now", left + col + 6, top + 80, col, MusicLibrary::refresh);
        }
        if (page != Page.SECTIONS) button("Back", "Return to Settings sections", left, top + 125, 67, () -> show(Page.SECTIONS));
        button("Done", "Return to the previous screen", x() + w() - 77, top + 125, 67, () -> open(parent));
    }

    private void button(String label, String tip, int x, int y, int w, Runnable callback) {
        addRenderableWidget(UnifiedMusicPlayer.button(label, tip, x, y, w, callback));
    }

    private static Component groupVolume(double value) {
        return Component.literal("Group Volume / " + Math.round(value * 100) + "%");
    }


    private void show(Page next) {
        save();
        page = next;
        clearWidgets();
        init();
    }

    private void save() {
        if (groupVolumeChanged) {
            SpeakerGroupManager.save();
            groupVolumeChanged = false;
        }
    }

    private void open(Screen next) { Minecraft.getInstance().gui.setScreen(next); }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        drawPanel(g, width, height, x(), y(), w(), 154);
        String heading = switch (page) {
            case SECTIONS -> "Settings";
            case GROUPS -> "Settings / Groups";
            case LIBRARY -> "Settings / Library & Import";
        };
        g.text(font, heading, x() + 10, y() + 11, StudioUi.text());
        if (page == Page.GROUPS) {
            var group = SpeakerAudioManager.getSpeakerGroup(head).orElse(null);
            String info = group == null ? "This speaker is not in a group." :
                    group.name() + " / " + group.size() + " speakers / " + SpeakerAudioManager.state(head);
            g.text(font, StudioUi.fit(font, info, w() - 20), x() + 10, y() + 40, StudioUi.muted());
        } else if (page == Page.LIBRARY) {
            var library = MusicLibrary.getMusicDirectory();
            String info = (library == null ? "music/speakersinheads" : library.getParent().getFileName() + "/" + library.getFileName())
                    + " / " + MusicLibrary.tracks().size() + " tracks";
            g.text(font, StudioUi.fit(font, info, w() - 20), x() + 10, y() + 40, StudioUi.muted());
        }
        super.extractRenderState(g, mx, my, delta);
    }

    @Override public void removed() { save(); super.removed(); }
    @Override public void onClose() { if (page == Page.SECTIONS) open(parent); else show(Page.SECTIONS); }
    @Override public boolean isPauseScreen() { return false; }
}
