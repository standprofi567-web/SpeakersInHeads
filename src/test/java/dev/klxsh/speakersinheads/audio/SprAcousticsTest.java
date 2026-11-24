package dev.klxsh.speakersinheads.audio;

import org.junit.jupiter.api.Test;
import net.minecraft.sounds.SoundSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SprAcousticsTest {
    @Test
    void categoryOverrideOnlyAffectsOurManagedRecordsInsideSpr() {
        assertEquals(SoundSource.BLOCKS, SprAcoustics.environmentCategory(SoundSource.RECORDS, true));
        assertEquals(SoundSource.RECORDS, SprAcoustics.environmentCategory(SoundSource.RECORDS, false));
        assertEquals(SoundSource.MUSIC, SprAcoustics.environmentCategory(SoundSource.MUSIC, true));
    }

    @Test
    void ordinaryClientCanLoadTheCompatibilityBridgeWithoutSpr() {
        assertEquals("Local filtering is enabled.", SprAcoustics.status());
        org.junit.jupiter.api.Assertions.assertFalse(SprAcoustics.ownsDirectSignal(1));
        SprAcoustics.detach(1);
    }
}
