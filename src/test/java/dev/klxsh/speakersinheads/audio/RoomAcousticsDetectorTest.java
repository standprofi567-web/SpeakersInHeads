package dev.klxsh.speakersinheads.audio;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomAcousticsDetectorTest {

    @BeforeEach
    void setUp() {
        RoomAcousticsDetector.clearCache();
    }

    @Test
    void defaultProfilesHaveExpectedAcousticParameters() {
        RoomAcousticsDetector.RoomProfile outdoor = RoomAcousticsDetector.RoomProfile.OUTDOOR;
        assertFalse(outdoor.isIndoor());
        assertEquals(48.0f, outdoor.maxAudibleDistance(), 0.01f);
        assertEquals(20000.0f, outdoor.cutoffHz(), 0.01f);
        assertEquals(0.0f, outdoor.reflectionGain(), 0.01f);

        RoomAcousticsDetector.RoomProfile indoor = RoomAcousticsDetector.RoomProfile.INDOOR;
        assertTrue(indoor.isIndoor());
        assertEquals(24.0f, indoor.maxAudibleDistance(), 0.01f);
        assertEquals(1600.0f, indoor.cutoffHz(), 0.01f);
        assertEquals(0.15f, indoor.reflectionGain(), 0.01f);
        assertEquals(16.0f, indoor.reflectionDelayMs(), 0.01f);
    }

    @Test
    void nullLevelReturnsOutdoorProfileSafely() {
        BlockPos pos = new BlockPos(100, 64, 100);
        RoomAcousticsDetector.RoomProfile resolved = RoomAcousticsDetector.detect(null, pos);
        assertNotNull(resolved);
        assertFalse(resolved.isIndoor());
        assertEquals(48.0f, resolved.maxAudibleDistance(), 0.01f);
    }

    @Test
    void nullPositionReturnsOutdoorProfileSafely() {
        RoomAcousticsDetector.RoomProfile resolved = RoomAcousticsDetector.detect(null, null);
        assertNotNull(resolved);
        assertFalse(resolved.isIndoor());
    }

    @Test
    void cacheInvalidationClearsEntries() {
        BlockPos pos = new BlockPos(50, 64, 50);
        RoomAcousticsDetector.detect(null, pos);
        RoomAcousticsDetector.invalidate(pos);
        // After invalidation, should re-resolve safely without error
        RoomAcousticsDetector.RoomProfile resolved = RoomAcousticsDetector.detect(null, pos);
        assertNotNull(resolved);
    }
}
