package dev.klxsh.speakersinheads.music;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackSettingsTest {

    private List<MusicTrack> sampleTracks;

    @BeforeEach
    void setUp() {
        sampleTracks = List.of(
                new MusicTrack(Path.of("a.mp3"), "Song A", true, MusicTrack.Source.FOLDER),
                new MusicTrack(Path.of("b.mp3"), "Song B", true, MusicTrack.Source.FOLDER),
                new MusicTrack(Path.of("c.mp3"), "Song C", true, MusicTrack.Source.FOLDER),
                new MusicTrack(Path.of("d.mp3"), "Song D", true, MusicTrack.Source.FOLDER)
        );
        PlaybackSettings.setRepeatMode(PlaybackSettings.RepeatMode.REPEAT_ALL);
        PlaybackSettings.setShuffle(false);
    }

    @Test
    void repeatModeCycle() {
        PlaybackSettings.setRepeatMode(PlaybackSettings.RepeatMode.OFF);
        assertEquals(PlaybackSettings.RepeatMode.OFF, PlaybackSettings.repeatMode());

        PlaybackSettings.toggleRepeatMode();
        assertEquals(PlaybackSettings.RepeatMode.REPEAT_ALL, PlaybackSettings.repeatMode());

        PlaybackSettings.toggleRepeatMode();
        assertEquals(PlaybackSettings.RepeatMode.REPEAT_ONE, PlaybackSettings.repeatMode());

        PlaybackSettings.toggleRepeatMode();
        assertEquals(PlaybackSettings.RepeatMode.OFF, PlaybackSettings.repeatMode());
    }

    @Test
    void repeatOneRepeatsSameTrackOnNaturalFinish() {
        PlaybackSettings.setRepeatMode(PlaybackSettings.RepeatMode.REPEAT_ONE);
        MusicTrack current = sampleTracks.get(1);

        MusicTrack next = PlaybackSettings.getNextTrack(current, sampleTracks, false);
        assertEquals(current, next, "Repeat One must return the same track when finished naturally");

        // When user explicitly clicks Next, it advances
        MusicTrack userNext = PlaybackSettings.getNextTrack(current, sampleTracks, true);
        assertEquals(sampleTracks.get(2), userNext, "User clicking Next should still advance");
    }

    @Test
    void repeatOffStopsAtEndOfLibrary() {
        PlaybackSettings.setRepeatMode(PlaybackSettings.RepeatMode.OFF);
        MusicTrack last = sampleTracks.get(sampleTracks.size() - 1);

        MusicTrack next = PlaybackSettings.getNextTrack(last, sampleTracks, false);
        assertNull(next, "Repeat Off must return null at the end of playlist");
    }

    @Test
    void repeatAllLoopsBackToBeginning() {
        PlaybackSettings.setRepeatMode(PlaybackSettings.RepeatMode.REPEAT_ALL);
        MusicTrack last = sampleTracks.get(sampleTracks.size() - 1);

        MusicTrack next = PlaybackSettings.getNextTrack(last, sampleTracks, false);
        assertEquals(sampleTracks.get(0), next, "Repeat All must loop back to first track");
    }

    @Test
    void shufflePlaysAllTracksWithoutDuplicatesBeforeRepeating() {
        PlaybackSettings.setRepeatMode(PlaybackSettings.RepeatMode.REPEAT_ALL);
        PlaybackSettings.setShuffle(true);
        assertTrue(PlaybackSettings.isShuffle());

        Set<MusicTrack> seen = new HashSet<>();
        MusicTrack current = sampleTracks.get(0);
        seen.add(current);

        // Advance 3 more times to consume all 4 tracks
        for (int i = 0; i < sampleTracks.size() - 1; i++) {
            MusicTrack next = PlaybackSettings.getNextTrack(current, sampleTracks, false);
            assertNotNull(next);
            assertFalse(seen.contains(next), "Shuffle must not repeat tracks within a cycle: " + next.title());
            seen.add(next);
            current = next;
        }

        assertEquals(sampleTracks.size(), seen.size(), "All tracks must be covered before cycle repeats");
    }
}
