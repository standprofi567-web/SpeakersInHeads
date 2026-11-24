package dev.klxsh.speakersinheads.audio;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AudioOcclusionDetectorTest {
    @Test
    void emptyOpeningAndSuccessiveWallCellsHaveDifferentLoss() {
        Vec3 start = new Vec3(0.5, 1.5, 0.5);
        Vec3 end = new Vec3(5.5, 1.5, 0.5);
        assertEquals(0f, AudioOcclusionDetector.trace(start, end, p -> 0));
        assertEquals(0.15f, AudioOcclusionDetector.trace(start, end, p -> p.getX() == 2 ? 0.15 : 0), 0.0001f);
        assertEquals(0.45f, AudioOcclusionDetector.trace(start, end, p -> p.getX() == 2 ? 0.45 : 0), 0.0001f);
        assertEquals(0.9f, AudioOcclusionDetector.trace(start, end,
                p -> p.getX() == 2 || p.getX() == 3 ? 0.45 : 0), 0.0001f);
        assertEquals(0.45f, AudioOcclusionDetector.trace(start, end,
                p -> p.getX() == 2 ? -1 : 0.45), 0.0001f,
                "Traversal stops at missing chunks and skips the source/listener cells");
    }

    @Test
    void rangePresetsIncludeCityDistancesAndWrap() {
        int preset = 0;
        for (int expected : new int[] {16, 32, 48, 64, 80, 96, 16}) {
            preset = SpatialSettings.nextRangePreset(preset);
            assertEquals(expected, preset);
        }
    }
}
