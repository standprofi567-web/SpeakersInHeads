package dev.klxsh.speakersinheads.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UnifiedPlayerUiTest {
    @Test
    void primaryActionFollowsSpeakerSessionInsteadOfTheSelectedLibraryTrack() {
        assertEquals(UnifiedMusicPlayer.Action.PLAY, UnifiedMusicPlayer.action("Stopped"));
        assertEquals(UnifiedMusicPlayer.Action.PLAY, UnifiedMusicPlayer.action("Error: Unsupported file"));
        assertEquals(UnifiedMusicPlayer.Action.PAUSE, UnifiedMusicPlayer.action("Loading"));
        assertEquals(UnifiedMusicPlayer.Action.PAUSE, UnifiedMusicPlayer.action("Playing"));
        assertEquals(UnifiedMusicPlayer.Action.PAUSE, UnifiedMusicPlayer.action("Playing (Unloaded)"));
        assertEquals(UnifiedMusicPlayer.Action.RESUME, UnifiedMusicPlayer.action("Paused"));
    }

    @Test
    void analyzedCoverUsesTwoCornersInsideItsCardAtDifferentGuiWidths() {
        for (int width : new int[]{320, 426, 640, 853, 1280, 2560}) {
            int panelWidth = Math.min(408, width - 16);
            int panelX = (width - panelWidth) / 2;
            int panelY = 31;
            var bounds = ServiceArtwork.bounds(panelX + panelWidth - 42, panelY + 83, 20);
            assertEquals(20, bounds.right() - bounds.left());
            assertEquals(20, bounds.bottom() - bounds.top());
            assertTrue(bounds.left() >= panelX);
            assertTrue(bounds.right() <= panelX + panelWidth);
            assertTrue(bounds.bottom() <= panelY + 105);
        }
        assertEquals("Size unknown", SmartImportScreen.sizeLabel(-1));
        assertEquals("1.5 MiB", SmartImportScreen.sizeLabel(1572864));
    }
}
