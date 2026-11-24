package dev.klxsh.speakersinheads.audio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.resources.sounds.SoundEventRegistration;
import net.minecraft.client.resources.sounds.SoundEventRegistrationSerializer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeakerSoundInstanceTest {
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void positionsSourceAtHeadCenterAndUsesMinecraftRecordsVolume() {
        BlockPos position = new BlockPos(24, 63, 50);
        SpeakerSoundInstance sound = new SpeakerSoundInstance(Path.of("local.wav"), position,
                Identifier.fromNamespaceAndPath("minecraft", "overworld"));

        assertEquals(24.5, sound.getX());
        assertEquals(63.5, sound.getY());
        assertEquals(50.5, sound.getZ());
        // Attenuation.NONE prevents OpenAL double attenuation while preserving 3D positioning.
        assertEquals(SoundInstance.Attenuation.NONE, sound.getAttenuation());
        assertFalse(sound.isRelative());
        assertEquals(SoundSource.RECORDS, sound.getSource());
    }

    @Test
    void soundResourceConfiguresStreamAndFiniteThirtyTwoBlockRange() throws Exception {
        try (var resource = getClass().getClassLoader()
                .getResourceAsStream("assets/speakersinheads/sounds.json")) {
            assertTrue(resource != null, "The local streaming sound definition must be packaged");
            Type type = new TypeToken<Map<String, SoundEventRegistration>>() {}.getType();
            Gson gson = new GsonBuilder()
                    .registerTypeAdapter(SoundEventRegistration.class, new SoundEventRegistrationSerializer())
                    .create();
            Map<String, SoundEventRegistration> definitions = gson.fromJson(new InputStreamReader(resource), type);
            var sound = definitions.get("local_stream").getSounds().getFirst();
            assertTrue(sound.shouldStream());
            assertEquals(SpeakerSoundInstance.ATTENUATION_DISTANCE, sound.getAttenuationDistance());
        }
    }

    @Test
    void sourceValidityRecognizesOnlyTheTwoSupportedPlayerHeadBlocks() {
        BlockState floorHead = Blocks.PLAYER_HEAD.defaultBlockState();
        BlockState wallHead = Blocks.PLAYER_WALL_HEAD.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();

        assertTrue(SpeakerSoundInstance.isSupportedHead(floorHead));
        assertTrue(SpeakerSoundInstance.isSupportedHead(wallHead));
        assertFalse(SpeakerSoundInstance.isSupportedHead(stone));
    }
}
