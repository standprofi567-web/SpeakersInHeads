package dev.klxsh.speakersinheads.audio;

import dev.klxsh.speakersinheads.ClientPreferences;

import java.nio.ByteBuffer;

/** Shared output setting, with independent smoothing per PCM stream. */
public final class OutputBoost {
    public static final float MAX_GAIN = 1.5f;
    private static volatile float target = 1f;
    private final int channels;
    private final float step;
    private float current = target;

    OutputBoost(float sampleRate, int channels) {
        this.channels = channels;
        step = (MAX_GAIN - 1f) / Math.max(1f, sampleRate * 0.04f);
    }

    public static void initialize() {
        target = 1f + (MAX_GAIN - 1f) * ClientPreferences.getUnit("boost", 0f);
    }

    public static float setting() { return (target - 1f) / (MAX_GAIN - 1f); }

    public static void setSetting(float value) {
        target = 1f + (MAX_GAIN - 1f) * (Float.isFinite(value) ? Math.clamp(value, 0f, 1f) : 0f);
    }

    void process(ByteBuffer pcm, int length) {
        float goal = target;
        if (current == 1f && goal == 1f) return;
        int stride = channels * 2;
        for (int frame = 0; frame + stride <= length; frame += stride) {
            current += Math.clamp(goal - current, -step, step);
            for (int channel = 0; channel < channels; channel++) {
                int offset = frame + channel * 2;
                short source = (short) ((pcm.get(offset) & 255) | (pcm.get(offset + 1) << 8));
                int sample = compress(source, current);
                pcm.put(offset, (byte) sample);
                pcm.put(offset + 1, (byte) (sample >> 8));
            }
        }
    }

    static int compress(short sample, float gain) {
        if (gain == 1f) return sample;
        int magnitude = Math.abs((int) sample);
        int ceiling = sample < 0 ? 32768 : 32767;
        float requested = magnitude * gain;
        float knee = Math.max(magnitude, 24000);
        if (requested <= knee) return Math.round(requested) * (sample < 0 ? -1 : 1);
        float delta = requested - knee;
        float headroom = ceiling - knee;
        int result = Math.round(knee + headroom * delta / (headroom + delta));
        return sample < 0 ? -result : result;
    }
}
