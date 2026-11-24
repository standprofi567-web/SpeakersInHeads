package dev.klxsh.speakersinheads.audio;

import net.minecraft.client.sounds.AudioStream;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Streaming audio wrapper that converts 16-bit signed stereo PCM to mono PCM on the fly.
 *
 * <p>This enables OpenAL to treat the audio source as a genuine 3D point source with
 * real-time spatial panning and listener orientation (including HRTF where supported),
 * while preserving the original source files unmodified on disk.
 */
public final class MonoDownmixAudioStream implements AudioStream {
    private final AudioStream source;
    private final AudioFormat monoFormat;
    private final boolean passThrough;

    public MonoDownmixAudioStream(AudioStream source) {
        this.source = source;
        AudioFormat srcFormat = source.getFormat();
        if (srcFormat.getChannels() == 1) {
            this.passThrough = true;
            this.monoFormat = srcFormat;
        } else if (srcFormat.getChannels() == 2) {
            this.passThrough = false;
            this.monoFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    srcFormat.getSampleRate(),
                    16,
                    1,
                    2,
                    srcFormat.getSampleRate(),
                    false
            );
        } else {
            throw new IllegalArgumentException("Unsupported channel count for mono downmix: " + srcFormat.getChannels());
        }
    }

    @Override
    public AudioFormat getFormat() {
        return monoFormat;
    }

    @Override
    public ByteBuffer read(int requestedMonoBytes) throws IOException {
        if (passThrough) {
            return source.read(requestedMonoBytes);
        }

        if (requestedMonoBytes <= 0) {
            return ByteBuffer.allocate(0);
        }

        // Ensure alignment to 16-bit mono frame size (2 bytes)
        int monoBytes = requestedMonoBytes - (requestedMonoBytes % 2);
        if (monoBytes == 0) {
            return ByteBuffer.allocate(0);
        }

        // Each mono frame (2 bytes) requires one stereo frame (4 bytes: L + R)
        int requestedStereoBytes = monoBytes * 2;
        ByteBuffer stereoBuffer = source.read(requestedStereoBytes);
        if (stereoBuffer == null || !stereoBuffer.hasRemaining()) {
            return ByteBuffer.allocate(0);
        }

        stereoBuffer.order(ByteOrder.LITTLE_ENDIAN);
        int availableStereoBytes = stereoBuffer.remaining();
        int availableStereoFrames = availableStereoBytes / 4;
        if (availableStereoFrames == 0) {
            return ByteBuffer.allocate(0);
        }

        ByteBuffer monoBuffer = ByteBuffer.allocate(availableStereoFrames * 2);
        monoBuffer.order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < availableStereoFrames; i++) {
            short left = stereoBuffer.getShort();
            short right = stereoBuffer.getShort();
            // Downmix to mono: arithmetic mean (L + R) / 2 with 16-bit clamping
            int mixed = (left + right) / 2;
            short mono = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, mixed));
            monoBuffer.putShort(mono);
        }

        monoBuffer.flip();
        return monoBuffer;
    }

    @Override
    public void close() throws IOException {
        source.close();
    }
}
