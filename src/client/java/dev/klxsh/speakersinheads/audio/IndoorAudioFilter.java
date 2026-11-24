package dev.klxsh.speakersinheads.audio;

import java.nio.ByteBuffer;

/** Wall absorption with a continuous dry/filtered blend, independent of the playback clock. */
public final class IndoorAudioFilter {
    private final float sampleRate;
    private final int channels;

    private volatile float occlusion;
    private float currentOcclusion;
    private float currentAlpha;
    private final float[] filtered;

    public IndoorAudioFilter(float sampleRate, int channels) {
        this.sampleRate = sampleRate > 0 ? sampleRate : 44100.0f;
        this.channels = Math.clamp(channels, 1, 2);
        this.filtered = new float[this.channels];
        reset();
    }

    public void setOcclusion(float value) { occlusion = Math.clamp(value, 0f, 1f); }
    public float getOcclusion() { return occlusion; }
    public float occlusion() { return occlusion; }
    public void setIndoor(boolean value) { setOcclusion(value ? 0.95f : 0f); }
    public boolean isIndoor() { return occlusion > 0.01f; }

    public void process(ByteBuffer bytes, int length) {
        float target = occlusion;
        int frames = length / (channels * 2);
        if (frames == 0 || (target == 0.0f && currentOcclusion == 0.0f)) return;
        float cutoff = 900f + (1f - target) * (1f - target) * 8800f;
        float targetAlpha = alphaFor(cutoff);
        float alpha = currentAlpha;
        float alphaStep = (targetAlpha - alpha) / frames;
        float absorption = currentOcclusion;
        float step = (target - absorption) / frames;
        for (int frame = 0; frame < frames; frame++) {
            absorption += step;
            alpha += alphaStep;
            float wet = Math.min(1f, absorption * 1.4f);
            float gain = 1f - absorption * 0.65f;
            for (int c = 0; c < channels; c++) {
                int i = (frame * channels + c) * 2;
                short dry = (short) ((bytes.get(i) & 0xff) | (bytes.get(i + 1) << 8));
                filtered[c] += alpha * (dry - filtered[c]);
                int sample = Math.round((dry + wet * (filtered[c] - dry)) * gain);
                bytes.put(i, (byte) sample);
                bytes.put(i + 1, (byte) (sample >> 8));
            }
        }
        currentOcclusion = target;
        currentAlpha = targetAlpha;
        if (target == 0f) reset();
    }

    public void reset() {
        currentOcclusion = 0f;
        currentAlpha = alphaFor(9700f);
        java.util.Arrays.fill(filtered, 0f);
    }
    private float alphaFor(float cutoff) {
        return (float) -Math.expm1(-2 * Math.PI * cutoff / sampleRate);
    }
}
