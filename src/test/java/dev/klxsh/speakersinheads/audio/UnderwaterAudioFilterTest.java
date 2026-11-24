package dev.klxsh.speakersinheads.audio;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnderwaterAudioFilterTest {

    @Test
    void lowPassAttenuatesHighFrequenciesMoreThanLowFrequencies() {
        int sampleRate = 44100;
        UnderwaterAudioFilter filter = new UnderwaterAudioFilter(sampleRate, 1);
        filter.setUnderwater(true);
        filter.reset();

        // Generate 1000 samples of 8000 Hz high-frequency tone
        int numSamples = 1000;
        short[] highFreq = generateSine(8000.0, sampleRate, numSamples, (short) 10000);
        byte[] highBytes = toByteArray(highFreq);
        ByteBuffer highBuf = ByteBuffer.wrap(highBytes).order(ByteOrder.LITTLE_ENDIAN);
        filter.process(highBuf, highBytes.length);
        double highRms = calculateRms(highBytes);

        // Generate 1000 samples of 200 Hz low-frequency tone
        filter.reset();
        filter.setUnderwater(true);
        short[] lowFreq = generateSine(200.0, sampleRate, numSamples, (short) 10000);
        byte[] lowBytes = toByteArray(lowFreq);
        ByteBuffer lowBuf = ByteBuffer.wrap(lowBytes).order(ByteOrder.LITTLE_ENDIAN);
        filter.process(lowBuf, lowBytes.length);
        double lowRms = calculateRms(lowBytes);

        // The low-pass filter must pass the 200 Hz tone with significantly higher amplitude than the 8000 Hz tone
        assertTrue(lowRms > highRms * 3.0,
                String.format("Low RMS (%.1f) should be significantly higher than High RMS (%.1f)", lowRms, highRms));
    }

    @Test
    void airModeLeavesSignalEssentiallyUnchanged() {
        int sampleRate = 44100;
        UnderwaterAudioFilter filter = new UnderwaterAudioFilter(sampleRate, 1);
        filter.setUnderwater(false);

        short[] original = generateSine(1000.0, sampleRate, 500, (short) 15000);
        byte[] bytes = toByteArray(original);
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        filter.process(buf, bytes.length);

        // Signal should be practically identical in air mode
        double originalRms = calculateRms(toByteArray(original));
        double filteredRms = calculateRms(bytes);
        assertEquals(originalRms, filteredRms, originalRms * 0.05);
    }

    @Test
    void surfacingRestoresExactDryPcmWithoutMovingTheBuffer() {
        UnderwaterAudioFilter filter = new UnderwaterAudioFilter(48_000, 2);
        filter.setUnderwater(true);
        filter.reset();
        byte[] original = toByteArray(generateSine(8500, 48_000, 1024, (short) 14000));
        byte[] wet = original.clone();
        filter.process(ByteBuffer.wrap(wet), wet.length);
        assertTrue(calculateRms(wet) < calculateRms(original) * 0.75);

        filter.setUnderwater(false);
        byte[] transition = original.clone();
        ByteBuffer buffer = ByteBuffer.wrap(transition);
        filter.process(buffer, transition.length);
        assertEquals(0, buffer.position());
        assertEquals(transition.length, buffer.limit());
        assertArrayEquals(java.util.Arrays.copyOfRange(original, original.length - 4, original.length),
                java.util.Arrays.copyOfRange(transition, transition.length - 4, transition.length));

        byte[] dry = original.clone();
        filter.process(ByteBuffer.wrap(dry), dry.length);
        assertArrayEquals(original, dry);
    }

    @Test
    void handlesStereoChannelsIndependently() {
        int sampleRate = 44100;
        UnderwaterAudioFilter filter = new UnderwaterAudioFilter(sampleRate, 2);
        filter.setUnderwater(true);

        // Prepare stereo buffer: Left = DC 10000, Right = DC 0
        int frames = 200;
        byte[] bytes = new byte[frames * 4];
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            buf.putShort((short) 10000); // L
            buf.putShort((short) 0);     // R
        }

        buf.position(0);
        filter.process(buf, bytes.length);

        buf.position(0);
        for (int i = 0; i < frames; i++) {
            short l = buf.getShort();
            short r = buf.getShort();
            // Left should ramp toward 10000, Right must remain exactly 0 (no crosstalk)
            assertEquals(0, r, "Right channel must not leak Left channel signal");
        }
    }

    private static short[] generateSine(double freqHz, int sampleRate, int count, short amplitude) {
        short[] samples = new short[count];
        for (int i = 0; i < count; i++) {
            double angle = 2.0 * Math.PI * freqHz * i / sampleRate;
            samples[i] = (short) (Math.sin(angle) * amplitude);
        }
        return samples;
    }

    private static byte[] toByteArray(short[] samples) {
        byte[] bytes = new byte[samples.length * 2];
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : samples) buf.putShort(s);
        return bytes;
    }

    private static double calculateRms(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        double sum = 0;
        int count = bytes.length / 2;
        // Skip initial transient settling frames
        int skip = Math.min(count / 4, 100);
        for (int i = 0; i < skip; i++) buf.getShort();
        int measured = count - skip;
        for (int i = 0; i < measured; i++) {
            short s = buf.getShort();
            sum += s * s;
        }
        return Math.sqrt(sum / measured);
    }
}
