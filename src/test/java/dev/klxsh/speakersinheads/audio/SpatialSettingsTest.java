package dev.klxsh.speakersinheads.audio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpatialSettingsTest {
    private SpatialSettings settings;

    @BeforeEach
    void setUp() {
        settings = SpatialSettings.get();
        settings.setEnabled(true);
        settings.setMaxDistance(48.0f);
        settings.setFullVolumeDistance(2.0f);
        settings.setCurve(SpatialSettings.AttenuationCurve.SMOOTH);
        settings.setMode(SpatialSettings.SpatialMode.MONO_SPATIAL);
        settings.setAnimationsEnabled(true);
    }

    @Test
    void defaultValuesMatchSpecification() {
        assertTrue(settings.isEnabled());
        assertTrue(settings.isAnimationsEnabled());
        assertEquals(48.0f, settings.maxDistance(), 0.001f);
        assertEquals(2.0f, settings.fullVolumeDistance(), 0.001f);
        assertEquals(SpatialSettings.AttenuationCurve.SMOOTH, settings.curve());
        assertEquals(SpatialSettings.SpatialMode.MONO_SPATIAL, settings.mode());
    }

    @Test
    void animationSettingToggle() {
        settings.setAnimationsEnabled(false);
        assertFalse(settings.isAnimationsEnabled());
        settings.setAnimationsEnabled(true);
        assertTrue(settings.isAnimationsEnabled());
    }

    @Test
    void spatialModeSwitching() {
        settings.setMode(SpatialSettings.SpatialMode.STEREO);
        assertEquals(SpatialSettings.SpatialMode.STEREO, settings.mode());
        settings.setMode(SpatialSettings.SpatialMode.VIRTUAL_STEREO);
        assertEquals(SpatialSettings.SpatialMode.VIRTUAL_STEREO, settings.mode());
    }

    @Test
    void fullVolumeInsideFullVolumeRadius() {
        assertEquals(1.0f, settings.gainForDistance(0.0f), 0.0001f);
        assertEquals(1.0f, settings.gainForDistance(1.0f), 0.0001f);
        assertEquals(1.0f, settings.gainForDistance(2.0f), 0.0001f);
    }

    @Test
    void zeroGainBeyondMaxDistance() {
        assertEquals(0.0f, settings.gainForDistance(48.0f), 0.0001f);
        assertEquals(0.0f, settings.gainForDistance(50.0f), 0.0001f);
        assertEquals(0.0f, settings.gainForDistance(100.0f), 0.0001f);
    }

    @Test
    void linearCurveDecreasesMonotonically() {
        settings.setCurve(SpatialSettings.AttenuationCurve.LINEAR);
        float midpointDistance = 2.0f + (48.0f - 2.0f) / 2.0f; // 25.0
        assertEquals(0.5f, settings.gainForDistance(midpointDistance), 0.001f);

        float prev = 1.0f;
        for (float d = 2.0f; d <= 48.0f; d += 1.0f) {
            float gain = settings.gainForDistance(d);
            assertTrue(gain <= prev, "Gain must monotonically decrease: " + gain + " vs " + prev);
            prev = gain;
        }
    }

    @Test
    void smoothCurveDecreasesMonotonicallyWithSmoothstep() {
        settings.setCurve(SpatialSettings.AttenuationCurve.SMOOTH);
        float midpointDistance = 2.0f + (48.0f - 2.0f) / 2.0f; // 25.0
        // smoothstep(0.5) = 3*(0.25) - 2*(0.125) = 0.75 - 0.25 = 0.5 -> 1 - 0.5 = 0.5
        assertEquals(0.5f, settings.gainForDistance(midpointDistance), 0.001f);

        float prev = 1.0f;
        for (float d = 2.0f; d <= 48.0f; d += 1.0f) {
            float gain = settings.gainForDistance(d);
            assertTrue(gain <= prev, "Smooth gain must monotonically decrease: " + gain + " vs " + prev);
            prev = gain;
        }
    }

    @Test
    void disabledSpatialAttenuationAlwaysReturnsFullGain() {
        settings.setEnabled(false);
        assertEquals(1.0f, settings.gainForDistance(0.0f), 0.0001f);
        assertEquals(1.0f, settings.gainForDistance(30.0f), 0.0001f);
        assertEquals(1.0f, settings.gainForDistance(100.0f), 0.0001f);
    }

    @Test
    void clampingPreventsInvalidRanges() {
        settings.setMaxDistance(200.0f);
        assertEquals(SpatialSettings.MAX_MAX_DISTANCE, settings.maxDistance(), 0.001f);

        settings.setMaxDistance(1.0f);
        assertEquals(SpatialSettings.MIN_MAX_DISTANCE, settings.maxDistance(), 0.001f);

        settings.setMaxDistance(10.0f);
        settings.setFullVolumeDistance(15.0f);
        assertTrue(settings.fullVolumeDistance() < settings.maxDistance(),
                "Full volume distance must be less than max distance");
    }

    @Test
    void smoothGainUsesExtendedDistanceInEveryMode() {
        for (SpatialSettings.SpatialMode mode : SpatialSettings.SpatialMode.values()) {
            settings.setMode(mode);
            settings.setMaxDistance(96);
            assertEquals(1f, settings.gainForDistance(2));
            assertTrue(settings.gainForDistance(64) > settings.gainForDistance(80));
            assertTrue(settings.gainForDistance(95) > 0);
            assertEquals(0f, settings.gainForDistance(96));
        }
    }
}
