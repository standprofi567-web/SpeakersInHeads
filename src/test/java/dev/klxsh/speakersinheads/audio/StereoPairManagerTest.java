package dev.klxsh.speakersinheads.audio;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StereoPairManagerTest {
    private static final Identifier OVERWORLD = Identifier.parse("minecraft:overworld");
    private static final Identifier NETHER = Identifier.parse("minecraft:the_nether");

    @TempDir
    Path tempDir;

    private Path testConfig;

    @BeforeEach
    void setUp() {
        testConfig = tempDir.resolve("stereo_pairs.json");
        StereoPairManager.initializeForTesting(testConfig);
    }

    @AfterEach
    void tearDown() {
        StereoPairManager.clearForTesting();
    }

    @Test
    void createsAndFindsStereoPair() {
        BlockPos left = new BlockPos(100, 64, 200);
        BlockPos right = new BlockPos(105, 64, 200);

        PhysicalStereoPair pair = StereoPairManager.createPair("Test Pair", OVERWORLD, left, right);
        assertNotNull(pair);
        assertEquals("Test Pair", pair.name());
        assertEquals(left, pair.leftPos());
        assertEquals(right, pair.rightPos());
        assertEquals(OVERWORLD, pair.dimension());
        assertFalse(pair.isSwapped());

        // Find by left pos
        Optional<PhysicalStereoPair> foundLeft = StereoPairManager.findPairFor(OVERWORLD, left);
        assertTrue(foundLeft.isPresent());
        assertEquals(pair.id(), foundLeft.get().id());
        assertTrue(foundLeft.get().isLeft(left));
        assertFalse(foundLeft.get().isRight(left));

        // Find by right pos
        Optional<PhysicalStereoPair> foundRight = StereoPairManager.findPairFor(OVERWORLD, right);
        assertTrue(foundRight.isPresent());
        assertEquals(pair.id(), foundRight.get().id());
        assertTrue(foundRight.get().isRight(right));
        assertFalse(foundRight.get().isLeft(right));

        // Different dimension must not match
        Optional<PhysicalStereoPair> foundNether = StereoPairManager.findPairFor(NETHER, left);
        assertFalse(foundNether.isPresent());
    }

    @Test
    void channelSwapInvertsChannels() {
        BlockPos left = new BlockPos(10, 64, 10);
        BlockPos right = new BlockPos(12, 64, 10);

        PhysicalStereoPair pair = StereoPairManager.createPair("Swap Pair", OVERWORLD, left, right);
        assertTrue(pair.isLeft(left));
        assertTrue(pair.isRight(right));

        pair.swap();
        assertTrue(pair.isSwapped());
        // After swap, left coordinate acts as Right channel, right coordinate acts as Left channel
        assertFalse(pair.isLeft(left));
        assertTrue(pair.isRight(left));
        assertTrue(pair.isLeft(right));
        assertFalse(pair.isRight(right));

        // Swap back
        pair.swap();
        assertFalse(pair.isSwapped());
        assertTrue(pair.isLeft(left));
        assertTrue(pair.isRight(right));
    }

    @Test
    void unlinkingRemovesPair() {
        BlockPos left = new BlockPos(50, 70, 50);
        BlockPos right = new BlockPos(55, 70, 50);

        PhysicalStereoPair pair = StereoPairManager.createPair("Unlink Pair", OVERWORLD, left, right);
        assertEquals(1, StereoPairManager.allPairs().size());

        boolean unlinked = StereoPairManager.unlink(OVERWORLD, left);
        assertTrue(unlinked);
        assertEquals(0, StereoPairManager.allPairs().size());
        assertFalse(StereoPairManager.findPairFor(OVERWORLD, left).isPresent());
        assertFalse(StereoPairManager.findPairFor(OVERWORLD, right).isPresent());
    }

    @Test
    void persistenceSavesAndRestoresPairs() throws IOException {
        BlockPos left = new BlockPos(20, 80, 20);
        BlockPos right = new BlockPos(25, 80, 20);

        PhysicalStereoPair pair = StereoPairManager.createPair("Persistent Pair", OVERWORLD, left, right);
        pair.swap();
        StereoPairManager.save();

        assertTrue(Files.isRegularFile(testConfig));

        // Re-initialize manager simulating game restart
        StereoPairManager.clearForTesting();
        assertEquals(0, StereoPairManager.allPairs().size());

        StereoPairManager.initializeForTesting(testConfig);
        assertEquals(1, StereoPairManager.allPairs().size());

        Optional<PhysicalStereoPair> restored = StereoPairManager.findPairFor(OVERWORLD, left);
        assertTrue(restored.isPresent());
        assertEquals("Persistent Pair", restored.get().name());
        assertTrue(restored.get().isSwapped());
    }
}
