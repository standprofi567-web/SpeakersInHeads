package dev.klxsh.speakersinheads.audio;

import net.minecraft.client.sounds.AudioStream;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StereoSplitterTest {
    private static final AudioFormat STEREO_FORMAT = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED, 44100, 16, 2, 4, 44100, false);

    @Test
    void splitsStereoChannelsSynchronouslyIntoLeftAndRightStreams() throws IOException {
        // Prepare 4 stereo frames: L0=111, R0=222, L1=333, R1=444, L2=555, R2=666, L3=777, R3=888
        byte[] bytes = new byte[16];
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 111).putShort((short) 222);
        buf.putShort((short) 333).putShort((short) 444);
        buf.putShort((short) 555).putShort((short) 666);
        buf.putShort((short) 777).putShort((short) 888);

        CountingStream source = new CountingStream(STEREO_FORMAT, bytes);
        StereoSplitter splitter = new StereoSplitter(source);

        AudioStream leftStream = splitter.leftStream();
        AudioStream rightStream = splitter.rightStream();

        assertEquals(1, leftStream.getFormat().getChannels());
        assertEquals(1, rightStream.getFormat().getChannels());

        // Read left channel first
        ByteBuffer leftChunk = leftStream.read(8);
        assertEquals(8, leftChunk.remaining());
        leftChunk.order(ByteOrder.LITTLE_ENDIAN);
        assertEquals((short) 111, leftChunk.getShort());
        assertEquals((short) 333, leftChunk.getShort());
        assertEquals((short) 555, leftChunk.getShort());
        assertEquals((short) 777, leftChunk.getShort());

        // Read right channel second: must receive the exact matching R frames from the same chunk
        ByteBuffer rightChunk = rightStream.read(8);
        assertEquals(8, rightChunk.remaining());
        rightChunk.order(ByteOrder.LITTLE_ENDIAN);
        assertEquals((short) 222, rightChunk.getShort());
        assertEquals((short) 444, rightChunk.getShort());
        assertEquals((short) 666, rightChunk.getShort());
        assertEquals((short) 888, rightChunk.getShort());

        // Closing either stream closes the underlying stereo decoder
        leftStream.close();
        assertEquals(1, source.closeCalls.get());
    }

    private static class CountingStream implements AudioStream {
        private final AudioFormat format;
        private final byte[] data;
        private int pos = 0;
        private final AtomicInteger closeCalls = new AtomicInteger();

        private CountingStream(AudioFormat format, byte[] data) {
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

        @Override public void close() {
            closeCalls.incrementAndGet();
        }
    }
}
