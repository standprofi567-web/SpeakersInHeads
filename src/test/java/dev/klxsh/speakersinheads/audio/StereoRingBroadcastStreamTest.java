package dev.klxsh.speakersinheads.audio;

import net.minecraft.client.sounds.AudioStream;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StereoRingBroadcastStreamTest {
    private static final AudioFormat STEREO_FORMAT = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED, 44100, 16, 2, 4, 44100, false);

    @Test
    void extractsSynchronizedDiscreteChannelsFromStereoSource() throws IOException {
        // Create 4 frames: L0=100, R0=200; L1=300, R1=400; L2=500, R2=600; L3=700, R3=800
        byte[] raw = new byte[16];
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 100).putShort((short) 200);
        buf.putShort((short) 300).putShort((short) 400);
        buf.putShort((short) 500).putShort((short) 600);
        buf.putShort((short) 700).putShort((short) 800);

        MockStereoStream source = new MockStereoStream(STEREO_FORMAT, raw);
        StereoRingBroadcastStream broadcaster = new StereoRingBroadcastStream(source);

        AudioStream leftStream = broadcaster.leftStream();
        AudioStream rightStream = broadcaster.rightStream();

        assertEquals(1, leftStream.getFormat().getChannels());
        assertEquals(1, rightStream.getFormat().getChannels());

        // Read 2 frames (4 bytes) from Left
        ByteBuffer leftChunk = leftStream.read(4);
        assertEquals(4, leftChunk.remaining());
        leftChunk.order(ByteOrder.LITTLE_ENDIAN);
        assertEquals((short) 100, leftChunk.getShort());
        assertEquals((short) 300, leftChunk.getShort());

        // Read 2 frames (4 bytes) from Right
        ByteBuffer rightChunk = rightStream.read(4);
        assertEquals(4, rightChunk.remaining());
        rightChunk.order(ByteOrder.LITTLE_ENDIAN);
        assertEquals((short) 200, rightChunk.getShort());
        assertEquals((short) 400, rightChunk.getShort());

        // Delta should be 0 frames
        assertEquals(0, broadcaster.getReaderFrameDelta());

        // Read remaining 2 frames
        ByteBuffer leftRest = leftStream.read(4);
        leftRest.order(ByteOrder.LITTLE_ENDIAN);
        assertEquals((short) 500, leftRest.getShort());
        assertEquals((short) 700, leftRest.getShort());

        ByteBuffer rightRest = rightStream.read(4);
        rightRest.order(ByteOrder.LITTLE_ENDIAN);
        assertEquals((short) 600, rightRest.getShort());
        assertEquals((short) 800, rightRest.getShort());

        // EOF check
        ByteBuffer leftEof = leftStream.read(4);
        assertEquals(0, leftEof.remaining());

        ByteBuffer rightEof = rightStream.read(4);
        assertEquals(0, rightEof.remaining());

        leftStream.close();
        assertEquals(1, source.closeCount.get());
    }

    @Test
    void handlesReaderDriftAndLagOverrunSafely() throws IOException {
        // Prepare a stream with 70,000 frames (exceeding ring capacity of 65536)
        int sampleCount = 70000;
        byte[] bigData = new byte[sampleCount * 4];
        ByteBuffer buf = ByteBuffer.wrap(bigData).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < sampleCount; i++) {
            buf.putShort((short) i);        // L
            buf.putShort((short) (i + 10)); // R
        }

        MockStereoStream source = new MockStereoStream(STEREO_FORMAT, bigData);
        StereoRingBroadcastStream broadcaster = new StereoRingBroadcastStream(source);

        AudioStream leftStream = broadcaster.leftStream();
        AudioStream rightStream = broadcaster.rightStream();

        // Left reads 68000 frames
        int leftBytes = 68000 * 2;
        while (leftBytes > 0) {
            int toRead = Math.min(leftBytes, 8192);
            ByteBuffer readBuf = leftStream.read(toRead);
            if (!readBuf.hasRemaining()) break;
            leftBytes -= readBuf.remaining();
        }

        // Right has not read anything yet (extreme lag/overrun scenario)
        // Now Right attempts to read: it should be gracefully caught up without crashing
        ByteBuffer rightChunk = rightStream.read(2048);
        assertTrue(rightChunk.remaining() > 0);

        broadcaster.close();
    }

    private static class MockStereoStream implements AudioStream {
        private final AudioFormat format;
        private final byte[] data;
        private int position = 0;
        final AtomicInteger closeCount = new AtomicInteger();

        MockStereoStream(AudioFormat format, byte[] data) {
            this.format = format;
            this.data = data;
        }

        @Override
        public AudioFormat getFormat() {
            return format;
        }

        @Override
        public ByteBuffer read(int size) {
            int available = data.length - position;
            if (available <= 0) return ByteBuffer.allocate(0);
            int toRead = Math.min(size, available);
            ByteBuffer out = ByteBuffer.allocate(toRead);
            out.put(data, position, toRead);
            position += toRead;
            out.flip();
            return out;
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }
}
