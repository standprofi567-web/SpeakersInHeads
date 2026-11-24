package dev.klxsh.speakersinheads.audio;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class OutputBoostTest {
    @Test
    void defaultIsBitExactAndBoostKeepsAllSixteenBitSamplesInRange() {
        for (int sample = Short.MIN_VALUE; sample <= Short.MAX_VALUE; sample++) {
            assertEquals(sample, OutputBoost.compress((short) sample, 1f));
            int result = OutputBoost.compress((short) sample, OutputBoost.MAX_GAIN);
            assertTrue(result >= Short.MIN_VALUE && result <= Short.MAX_VALUE);
            assertEquals(Integer.signum(sample), Integer.signum(result));
        }
        assertEquals(15000, OutputBoost.compress((short) 10000, OutputBoost.MAX_GAIN));
        assertTrue(OutputBoost.compress((short) 30000, OutputBoost.MAX_GAIN) > 30000);
    }

    @Test
    void settingRampsWithoutChangingFrameLayoutOrStereoBalance() {
        OutputBoost.setSetting(0f);
        var boost = new OutputBoost(48000, 2);
        var pcm = ByteBuffer.allocate(4800 * 4).order(ByteOrder.LITTLE_ENDIAN);
        try {
            while (pcm.hasRemaining()) { pcm.putShort((short) 10000); pcm.putShort((short) -10000); }
            pcm.flip();
            OutputBoost.setSetting(1f);
            boost.process(pcm, pcm.remaining());
            assertEquals(0, pcm.position());
            assertEquals(4800 * 4, pcm.limit());
            assertTrue(pcm.getShort(0) < 10100);
            assertEquals(15000, pcm.getShort(pcm.limit() - 4));
            assertEquals(-15000, pcm.getShort(pcm.limit() - 2));
        } finally {
            OutputBoost.setSetting(0f);
        }
    }
}
