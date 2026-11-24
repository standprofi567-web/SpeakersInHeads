package dev.klxsh.speakersinheads.audio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndoorAudioFilterTest {

    private IndoorAudioFilter monoFilter;
    private IndoorAudioFilter stereoFilter;

    @BeforeEach
    void setUp() {
        monoFilter = new IndoorAudioFilter(44100.0f, 1);
        stereoFilter = new IndoorAudioFilter(44100.0f, 2);
    }

    @Test
    void initialOutdoorStateBypassesProcessing() {
        assertFalse(monoFilter.isIndoor());
        monoFilter.reset();

        // High frequency alternating test samples (+10000, -10000, +10000, -10000)
        ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) 10000);
        buffer.putShort((short) -10000);
        buffer.putShort((short) 10000);
        buffer.putShort((short) -10000);
        buffer.flip();

        monoFilter.process(buffer, 8);

        // Outdoor bypass preserves raw samples exactly
        assertEquals((short) 10000, buffer.getShort(0));
        assertEquals((short) -10000, buffer.getShort(2));
    }

    @Test
    void indoorModeAttenuatesHighFrequencies() {
        monoFilter.setIndoor(true);
        assertTrue(monoFilter.isIndoor());

        // Fast alternating signal at Nyquist frequency (harsh treble)
        int sampleCount = 200;
        ByteBuffer buffer = ByteBuffer.allocate(sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < sampleCount; i++) {
            buffer.putShort((short) (i % 2 == 0 ? 15000 : -15000));
        }
        buffer.flip();

        monoFilter.process(buffer, sampleCount * 2);

        // Samples towards the end must be heavily dampened by ~1600 Hz cutoff
        short lastSample = buffer.getShort((sampleCount - 1) * 2);
        assertTrue(Math.abs(lastSample) < 4000,
                "Indoor filter should attenuate high frequencies below 4000 (was " + lastSample + ")");
    }

    @Test
    void stereoFilterProcessesBothChannelsIndependently() {
        stereoFilter.setIndoor(true);

        // 100 stereo frames
        int frames = 100;
        ByteBuffer buffer = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            buffer.putShort((short) (i % 2 == 0 ? 12000 : -12000)); // L
            buffer.putShort((short) (i % 2 == 0 ? 8000 : -8000));   // R
        }
        buffer.flip();

        stereoFilter.process(buffer, frames * 4);

        short lastL = buffer.getShort((frames - 1) * 4);
        short lastR = buffer.getShort((frames - 1) * 4 + 2);

        assertTrue(Math.abs(lastL) < 5000, "Left channel should be filtered");
        assertTrue(Math.abs(lastR) < 5000, "Right channel should be filtered");
    }

    @Test
    void occlusionDirectLOSBypassesFilter() {
        monoFilter.setOcclusion(0.0f);
        assertFalse(monoFilter.isIndoor());
        assertEquals(0.0f, monoFilter.occlusion());

        ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) 12000);
        buffer.putShort((short) -12000);
        buffer.flip();

        monoFilter.process(buffer, 4);
        assertEquals((short) 12000, buffer.getShort(0));
        assertEquals((short) -12000, buffer.getShort(2));
    }

    @Test
    void occlusionBehindWallAttenuatesHighFrequencies() {
        monoFilter.setOcclusion(0.8f);
        assertTrue(monoFilter.isIndoor());
        assertEquals(0.8f, monoFilter.occlusion(), 0.001f);

        int sampleCount = 200;
        ByteBuffer buffer = ByteBuffer.allocate(sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < sampleCount; i++) {
            buffer.putShort((short) (i % 2 == 0 ? 15000 : -15000));
        }
        buffer.flip();

        monoFilter.process(buffer, sampleCount * 2);

        short lastSample = buffer.getShort((sampleCount - 1) * 2);
        assertTrue(Math.abs(lastSample) < 4000,
                "Occluded filter should heavily attenuate treble through walls (was " + lastSample + ")");
    }

    @Test
    void resetClearsFilterState() {
        monoFilter.setIndoor(true);
        ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) 10000);
        buffer.putShort((short) 10000);
        monoFilter.process(buffer, 4);

        monoFilter.reset();
        monoFilter.setIndoor(false);

        ByteBuffer newBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        newBuffer.putShort((short) 5000);
        newBuffer.putShort((short) -5000);

        monoFilter.process(newBuffer, 4);
        assertEquals((short) 5000, newBuffer.getShort(0));
    }

    @Test
    void wallFilterDoesNotAddAnEchoAtSixteenMilliseconds() {
        monoFilter.setOcclusion(0.8f);
        ByteBuffer pcm = ByteBuffer.allocate(2400).order(ByteOrder.LITTLE_ENDIAN);
        monoFilter.process(pcm, pcm.capacity()); // Settle before feeding the impulse.
        pcm.putShort(0, (short) 20000);
        monoFilter.process(pcm, pcm.capacity());
        for (int frame = 640; frame < 800; frame++) {
            assertEquals(0, pcm.getShort(frame * 2));
        }
    }
}
