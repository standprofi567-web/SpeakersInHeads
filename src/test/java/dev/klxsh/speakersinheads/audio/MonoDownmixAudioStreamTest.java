package dev.klxsh.speakersinheads.audio;

import net.minecraft.client.sounds.AudioStream;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonoDownmixAudioStreamTest {
    private static final AudioFormat STEREO_FORMAT = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED, 44100, 16, 2, 4, 44100, false);
    private static final AudioFormat MONO_FORMAT = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED, 44100, 16, 1, 2, 44100, false);

    @Test
    void downmixesStereoToMonoCorrectly() throws IOException {
        // Create 4 stereo frames: (L0, R0), (L1, R1), (L2, R2), (L3, R3)
        // Values: (1000, 3000) -> 2000, (-1000, 1000) -> 0, (10000, 20000) -> 15000, (-4000, -2000) -> -3000
        byte[] stereoBytes = new byte[16];
        ByteBuffer buf = ByteBuffer.wrap(stereoBytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 1000).putShort((short) 3000);
        buf.putShort((short) -1000).putShort((short) 1000);
        buf.putShort((short) 10000).putShort((short) 20000);
        buf.putShort((short) -4000).putShort((short) -2000);

        MockStream mockStream = new MockStream(STEREO_FORMAT, stereoBytes);
        try (MonoDownmixAudioStream monoStream = new MonoDownmixAudioStream(mockStream)) {
            assertEquals(1, monoStream.getFormat().getChannels());
            assertEquals(44100, monoStream.getFormat().getSampleRate());

            ByteBuffer monoResult = monoStream.read(8); // request 4 mono frames (8 bytes)
            assertEquals(8, monoResult.remaining());
            monoResult.order(ByteOrder.LITTLE_ENDIAN);

            assertEquals((short) 2000, monoResult.getShort());
            assertEquals((short) 0, monoResult.getShort());
            assertEquals((short) 15000, monoResult.getShort());
            assertEquals((short) -3000, monoResult.getShort());
        }
    }

    @Test
    void monoSourcePassesThroughUnchanged() throws IOException {
        byte[] monoBytes = new byte[8];
        ByteBuffer buf = ByteBuffer.wrap(monoBytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 100);
        buf.putShort((short) 200);
        buf.putShort((short) 300);
        buf.putShort((short) 400);

        MockStream mockStream = new MockStream(MONO_FORMAT, monoBytes);
        try (MonoDownmixAudioStream monoStream = new MonoDownmixAudioStream(mockStream)) {
            assertEquals(1, monoStream.getFormat().getChannels());
            ByteBuffer res = monoStream.read(8);
            assertEquals(8, res.remaining());
        }
    }

    private static class MockStream implements AudioStream {
        private final AudioFormat format;
        private final byte[] data;
        private int pos = 0;

        private MockStream(AudioFormat format, byte[] data) {
            this.format = format;
            this.data = data;
        }

        @Override public AudioFormat getFormat() { return format; }

        @Override public ByteBuffer read(int size) {
            int available = Math.min(size, data.length - pos);
            if (available <= 0) return ByteBuffer.allocate(0);
            ByteBuffer out = ByteBuffer.wrap(data, pos, available).slice();
            pos += available;
            return out;
        }

        @Override public void close() {}
    }
}
