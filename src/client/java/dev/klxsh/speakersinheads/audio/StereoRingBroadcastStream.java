package dev.klxsh.speakersinheads.audio;

import net.minecraft.client.sounds.AudioStream;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * High-performance circular stereo ring broadcast stream for synchronized multi-emitter playback.
 *
 * <p>A single stereo decoder writes interleaved PCM frames into a shared ring buffer with a unified
 * frame-index timeline. Discrete left and right mono AudioStreams read their respective channels
 * via independent cursors aligned to the exact same audio frame index, eliminating phase drift,
 * temporal divergence, and redundant decoder overhead.
 */
public final class StereoRingBroadcastStream {
    private static final int CAPACITY = 65536; // power of 2: ~1.48s at 44.1kHz
    private static final int MASK = CAPACITY - 1;
    private static final int CHUNK_STEREO_FRAMES = 2048;

    private final AudioStream stereoSource;
    private final AudioFormat monoFormat;
    private final Object lock = new Object();

    private final short[] ringL = new short[CAPACITY];
    private final short[] ringR = new short[CAPACITY];

    private long writeIndex = 0;
    private long readIndexL = 0;
    private long readIndexR = 0;

    private boolean eofReached = false;
    private final AtomicBoolean closed = new AtomicBoolean();

    private final ChannelAudioStream leftStream;
    private final ChannelAudioStream rightStream;

    public StereoRingBroadcastStream(AudioStream stereoSource) {
        this.stereoSource = stereoSource;
        AudioFormat srcFormat = stereoSource.getFormat();
        if (srcFormat.getChannels() != 2) {
            throw new IllegalArgumentException("StereoRingBroadcastStream requires a 2-channel stereo stream, got: " + srcFormat);
        }
        this.monoFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                srcFormat.getSampleRate(),
                16,
                1,
                2,
                srcFormat.getSampleRate(),
                false
        );
        this.leftStream = new ChannelAudioStream(true);
        this.rightStream = new ChannelAudioStream(false);
    }

    public AudioStream leftStream() { return leftStream; }
    public AudioStream rightStream() { return rightStream; }

    public AudioFormat getMonoFormat() { return monoFormat; }

    private ByteBuffer readFromChannel(boolean isLeft, int requestedBytes) throws IOException {
        if (requestedBytes <= 0 || closed.get()) {
            return ByteBuffer.allocate(0);
        }

        int requestedFrames = requestedBytes / 2; // 2 bytes per 16-bit mono frame
        if (requestedFrames == 0) {
            return ByteBuffer.allocate(0);
        }

        synchronized (lock) {
            long myReadIndex = isLeft ? readIndexL : readIndexR;

            // Lag / overrun protection: if a reader falls too far behind, advance cursor to stay within ring
            if (writeIndex - myReadIndex > CAPACITY - 4096) {
                myReadIndex = writeIndex - (CAPACITY / 2);
            }

            // Fill ring if this reader needs more frames
            while ((writeIndex - myReadIndex) < requestedFrames && !eofReached && !closed.get()) {
                readMoreStereoFrames();
            }

            long availableFrames = writeIndex - myReadIndex;
            if (availableFrames <= 0) {
                if (eofReached) {
                    return ByteBuffer.allocate(0);
                }
                // Underrun: return silence without advancing cursor so playback resumes cleanly
                ByteBuffer silence = ByteBuffer.allocate(requestedFrames * 2);
                silence.order(ByteOrder.LITTLE_ENDIAN);
                return silence;
            }

            int framesToDeliver = (int) Math.min(requestedFrames, availableFrames);
            ByteBuffer out = ByteBuffer.allocate(framesToDeliver * 2);
            out.order(ByteOrder.LITTLE_ENDIAN);

            short[] targetRing = isLeft ? ringL : ringR;
            for (int i = 0; i < framesToDeliver; i++) {
                int ringSlot = (int) ((myReadIndex + i) & MASK);
                out.putShort(targetRing[ringSlot]);
            }

            myReadIndex += framesToDeliver;
            if (isLeft) {
                readIndexL = myReadIndex;
            } else {
                readIndexR = myReadIndex;
            }

            out.flip();
            return out;
        }
    }

    private void readMoreStereoFrames() throws IOException {
        int bytesToRead = CHUNK_STEREO_FRAMES * 4; // 2 channels * 2 bytes per sample
        ByteBuffer buf = stereoSource.read(bytesToRead);
        if (buf == null || !buf.hasRemaining()) {
            eofReached = true;
            return;
        }

        buf.order(ByteOrder.LITTLE_ENDIAN);
        int frames = buf.remaining() / 4;
        if (frames == 0) {
            eofReached = true;
            return;
        }

        for (int i = 0; i < frames; i++) {
            short l = buf.getShort();
            short r = buf.getShort();
            int slot = (int) ((writeIndex + i) & MASK);
            ringL[slot] = l;
            ringR[slot] = r;
        }

        writeIndex += frames;
    }

    public long getTimelineFrames() {
        synchronized (lock) {
            return writeIndex;
        }
    }

    public long getReaderFrameDelta() {
        synchronized (lock) {
            return Math.abs(readIndexL - readIndexR);
        }
    }

    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (lock) {
            stereoSource.close();
        }
    }

    private final class ChannelAudioStream implements AudioStream {
        private final boolean isLeft;

        private ChannelAudioStream(boolean isLeft) {
            this.isLeft = isLeft;
        }

        @Override
        public AudioFormat getFormat() {
            return monoFormat;
        }

        @Override
        public ByteBuffer read(int size) throws IOException {
            return readFromChannel(isLeft, size);
        }

        @Override
        public void close() throws IOException {
            StereoRingBroadcastStream.this.close();
        }
    }
}
