package dev.klxsh.speakersinheads.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MusicMixerScreenTimeTest {

    @Test
    void formatTimeProducesTwoDigitMinutesAndSeconds() {
        assertEquals("00:00", MusicMixerScreen.formatTime(0.0));
        assertEquals("00:05", MusicMixerScreen.formatTime(5.0));
        assertEquals("01:24", MusicMixerScreen.formatTime(84.0));
        assertEquals("04:32", MusicMixerScreen.formatTime(272.0));
        assertEquals("10:00", MusicMixerScreen.formatTime(600.0));
    }
}
