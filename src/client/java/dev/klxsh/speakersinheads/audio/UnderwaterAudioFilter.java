package dev.klxsh.speakersinheads.audio;

import java.nio.ByteBuffer;

/**
 * Real-time low-pass DSP filter simulating natural underwater acoustic attenuation.
 *
 * <p>Uses a fast, single-pole IIR filter: {@code y[n] = y[n-1] + alpha * (x[n] - y[n-1])}.
 * When the listener is submerged in water, the cutoff frequency is smoothly lowered to ~800 Hz,
 * dampening harsh high frequencies while preserving warm, deep bass. Transitions between water
 * and air are smoothed across PCM samples to prevent audible popping or zipper noise.
 */
public final class UnderwaterAudioFilter {
    public static final float AIR_CUTOFF_HZ = 20000.0f;
    public static final float WATER_CUTOFF_HZ = 850.0f;

    private final float sampleRate;
    private final int channels;
    private final float waterAlpha;

    private volatile boolean underwater = false;

    private float currentWet;
    private float prevLeft = 0.0f;
    private float prevRight = 0.0f;

    public UnderwaterAudioFilter(float sampleRate, int channels) {
        this.sampleRate = sampleRate > 0 ? sampleRate : 44100.0f;
        this.channels = Math.max(1, Math.min(2, channels));
        this.waterAlpha = calculateAlpha(WATER_CUTOFF_HZ, this.sampleRate);
    }

    public void setUnderwater(boolean value) {
        this.underwater = value;
    }

    public boolean isUnderwater() {
        return underwater;
    }

    public float getSampleRate() {
        return sampleRate;
    }

    public int getChannels() {
        return channels;
    }

    /**
     * Applies the filter in-place to 16-bit little-endian PCM samples.
     *
     * @param bytes the direct/heap byte buffer containing PCM data
     * @param length the number of bytes to process
     */
    public void process(ByteBuffer bytes, int length) {
        float wet = underwater ? HybridAcoustics.water() : 0f;
        if (wet == 0f && currentWet == 0f) return;
        int frames = Math.min(bytes.limit(), length) / (channels * 2);
        if (frames <= 0) return;

        int stride = channels * 2;
        float wetStep = (wet - currentWet) / frames;
        boolean stereo = channels == 2;
        if (currentWet == 0f) {
            prevLeft = sampleAt(bytes, 0);
            if (stereo) prevRight = sampleAt(bytes, 2);
        }
        for (int frame = 0, index = 0; frame < frames; frame++, index += stride) {
            float blend = frame == frames - 1 ? wet : currentWet + (frame + 1) * wetStep;
            prevLeft = filterSample(bytes, index, prevLeft, blend);
            if (stereo) prevRight = filterSample(bytes, index + 2, prevRight, blend);
        }

        currentWet = wet;
    }

    public void reset() {
        prevLeft = 0.0f;
        prevRight = 0.0f;
        currentWet = underwater ? HybridAcoustics.water() : 0f;
    }

    private static short sampleAt(ByteBuffer bytes, int index) {
        return (short) ((bytes.get(index) & 255) | (bytes.get(index + 1) << 8));
    }

    private float filterSample(ByteBuffer bytes, int index, float previous, float blend) {
        short dry = sampleAt(bytes, index);
        float filtered = previous + waterAlpha * (dry - previous);
        int sample = Math.round(dry + blend * (filtered - dry));
        bytes.put(index, (byte) sample);
        bytes.put(index + 1, (byte) (sample >> 8));
        return filtered;
    }

    private static float calculateAlpha(float cutoffHz, float sampleRate) {
        double omega = 2.0 * Math.PI * cutoffHz / sampleRate;
        return (float) (omega / (1.0 + omega));
    }
}
