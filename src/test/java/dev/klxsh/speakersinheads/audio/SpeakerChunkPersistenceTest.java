package dev.klxsh.speakersinheads.audio;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeakerChunkPersistenceTest {
    @BeforeAll
    static void initMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void initialHeadStateIsActive() {
        BlockPos pos = new BlockPos(10, 64, 10);
        SpeakerSoundInstance sound = new SpeakerSoundInstance(
                Path.of("test.wav"), pos, Identifier.parse("minecraft:overworld"));

        assertEquals(SpeakerSoundInstance.HeadState.ACTIVE, sound.headState());
        assertTrue(sound.isChunkLoaded());
        assertFalse(sound.isStopped());
    }

    @Test
    void playbackStateReflectsTemporaryUnloadState() {
        BlockPos pos = new BlockPos(10, 64, 10);
        SpeakerSoundInstance sound = new SpeakerSoundInstance(
                Path.of("test.wav"), pos, Identifier.parse("minecraft:overworld"));

        // When stream is loading
        assertEquals("Loading", sound.playbackState());
    }
}
