package dev.klxsh.speakersinheads.audio;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeakerAnimationManagerTest {

    @Test
    void idleHeadReturnsZeroMotion() {
        BlockPos pos = new BlockPos(0, 64, 0);
        // Head not playing music returns 0 motion
        assertEquals(0.0f, SpeakerAnimationManager.getHeadBounce(pos, 0.0f));
        assertEquals(0.0f, SpeakerAnimationManager.getHeadTilt(pos, 0.0f));
    }

    @Test
    void soundWaveRecordCalculatesProgressCorrectly() {
        BlockPos pos = new BlockPos(10, 64, 10);
        Vec3 origin = new Vec3(10.5, 64.25, 10.5);
        Vec3 forward = new Vec3(0, 0, 1);
        Vec3 right = new Vec3(1, 0, 0);
        Vec3 up = new Vec3(0, 1, 0);
        long spawnTime = 1000L;

        SpeakerAnimationManager.SoundWave wave = new SpeakerAnimationManager.SoundWave(
                pos, origin, forward, right, up, spawnTime);

        assertEquals(0.0f, wave.progress(1000L), 0.001f);
        assertEquals(0.5f, wave.progress(1500L), 0.001f);
        assertEquals(1.0f, wave.progress(2000L), 0.001f);
        assertEquals(1.0f, wave.progress(3000L), 0.001f); // clamped to 1.0
    }
}
