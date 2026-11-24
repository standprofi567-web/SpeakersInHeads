package dev.klxsh.speakersinheads.audio;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeakerGroupTest {

    @TempDir
    Path tempDir;

    private Identifier dim;

    @BeforeEach
    void setUp() {
        dim = Identifier.parse("minecraft:overworld");
        SpeakerGroupManager.initializeForTesting(tempDir.resolve("speaker_groups.json"));
    }

    @AfterEach
    void tearDown() {
        SpeakerGroupManager.clearForTesting();
    }

    @Test
    void createAndFindGroup() {
        BlockPos pos1 = new BlockPos(10, 64, 20);
        BlockPos pos2 = new BlockPos(15, 64, 25);
        BlockPos pos3 = new BlockPos(20, 65, 30);

        SpeakerGroup group = SpeakerGroupManager.createGroup("Concert Hall", dim, Set.of(pos1, pos2, pos3));
        assertEquals("Concert Hall", group.name());
        assertEquals(3, group.size());

        Optional<SpeakerGroup> found1 = SpeakerGroupManager.findGroupFor(dim, pos1);
        Optional<SpeakerGroup> found2 = SpeakerGroupManager.findGroupFor(dim, pos2);
        Optional<SpeakerGroup> foundNone = SpeakerGroupManager.findGroupFor(dim, new BlockPos(0, 0, 0));

        assertTrue(found1.isPresent());
        assertTrue(found2.isPresent());
        assertFalse(foundNone.isPresent());
        assertEquals(group.id(), found1.get().id());
    }

    @Test
    void addAndRemoveMember() {
        BlockPos pos1 = new BlockPos(10, 64, 20);
        BlockPos pos2 = new BlockPos(15, 64, 25);

        SpeakerGroup group = SpeakerGroupManager.createGroup("Lobby", dim, Set.of(pos1, pos2));
        assertEquals(2, group.size());

        BlockPos pos3 = new BlockPos(30, 70, 40);
        group.addMember(pos3);
        assertEquals(3, group.size());
        assertTrue(group.contains(dim, pos3));

        boolean removed = SpeakerGroupManager.removeFromAnyGroup(dim, pos1);
        assertTrue(removed);
        assertEquals(2, group.size());
        assertFalse(group.contains(dim, pos1));
    }

    @Test
    void persistenceAcrossReload() {
        BlockPos pos1 = new BlockPos(100, 64, 200);
        BlockPos pos2 = new BlockPos(105, 64, 205);

        SpeakerGroup created = SpeakerGroupManager.createGroup("Square", dim, Set.of(pos1, pos2));
        UUID id = created.id();

        // Reload from disk
        SpeakerGroupManager.initializeForTesting(tempDir.resolve("speaker_groups.json"));

        Optional<SpeakerGroup> loaded = SpeakerGroupManager.getGroup(id);
        assertTrue(loaded.isPresent());
        assertEquals("Square", loaded.get().name());
        assertEquals(2, loaded.get().size());
        assertTrue(loaded.get().contains(dim, pos1));
        assertTrue(loaded.get().contains(dim, pos2));
    }
}
