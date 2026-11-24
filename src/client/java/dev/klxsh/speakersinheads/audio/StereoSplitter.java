package dev.klxsh.speakersinheads.audio;

import net.minecraft.client.sounds.AudioStream;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Splits a single stereo AudioStream into synchronized discrete left and right mono AudioStreams.
 *
 * <p>Both virtual channels share the same underlying decoder worker and timeline, guaranteeing
 * zero temporal or phase drift between left and right stereo speakers without duplicating
 * decoder CPU or memory consumption.
 */
public final class StereoSplitter {
    private final AudioStream stereoSource;
    private final AudioFormat monoFormat;
    private final Object lock = new Object();

    private final Queue<ByteBuffer> leftQueue = new ArrayDeque<>();
    private final Queue<ByteBuffer> rightQueue = new ArrayDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private boolean eofReached = false;

    private final ChannelAudioStream leftChannelStream;
    private final ChannelAudioStream rightChannelStream;

    public StereoSplitter(AudioStream stereoSource) {
        this.stereoSource = stereoSource;
        AudioFormat srcFormat = stereoSource.getFormat();
        if (srcFormat.getChannels() != 2) {
            throw new IllegalArgumentException("StereoSplitter requires a 2-channel stereo stream, got: " + srcFormat);
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
        this.leftChannelStream = new ChannelAudioStream(true);
        this.rightChannelStream = new ChannelAudioStream(false);
    }

    public AudioStream leftStream() {
        return leftChannelStream;
    }

    public AudioStream rightStream() {
        return rightChannelStream;
    }

    private ByteBuffer readFromChannel(boolean isLeft, int requestedBytes) throws IOException {
        if (requestedBytes <= 0 || closed.get()) {
            return ByteBuffer.allocate(0);
        }

        int alignedBytes = requestedBytes - (requestedBytes % 2);
        if (alignedBytes == 0) {
            return ByteBuffer.allocate(0);
        }

        synchronized (lock) {
            Queue<ByteBuffer> myQueue = isLeft ? leftQueue : rightQueue;

            while (availableBytes(myQueue) < alignedBytes && !eofReached && !closed.get()) {
                readAndSplitNextChunk(alignedBytes * 2);
            }

            return extractBytes(myQueue, alignedBytes);
        }
    }

    private void readAndSplitNextChunk(int stereoBytesToRequest) throws IOException {
        int chunkSize = Math.max(2048, stereoBytesToRequest);
        chunkSize -= chunkSize % 4; // align to stereo frame (4 bytes)

        ByteBuffer stereoChunk = stereoSource.read(chunkSize);
        if (stereoChunk == null || !stereoChunk.hasRemaining()) {
            eofReached = true;
            return;
        }

        stereoChunk.order(ByteOrder.LITTLE_ENDIAN);
        int frames = stereoChunk.remaining() / 4;
        if (frames == 0) {
            eofReached = true;
            return;
        }

        ByteBuffer leftBuf = ByteBuffer.allocate(frames * 2);
        ByteBuffer rightBuf = ByteBuffer.allocate(frames * 2);
        leftBuf.order(ByteOrder.LITTLE_ENDIAN);
        rightBuf.order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < frames; i++) {
            leftBuf.putShort(stereoChunk.getShort());
            rightBuf.putShort(stereoChunk.getShort());
        }

        leftBuf.flip();
        rightBuf.flip();

        leftQueue.offer(leftBuf);
        rightQueue.offer(rightBuf);
    }

    private int availableBytes(Queue<ByteBuffer> queue) {
        int sum = 0;
        for (ByteBuffer b : queue) {
            sum += b.remaining();
        }
        return sum;
    }

    private ByteBuffer extractBytes(Queue<ByteBuffer> queue, int count) {
        int available = availableBytes(queue);
        int toRead = Math.min(count, available);
        if (toRead <= 0) {
            return ByteBuffer.allocate(0);
        }

        ByteBuffer out = ByteBuffer.allocate(toRead);
        out.order(ByteOrder.LITTLE_ENDIAN);

        while (out.hasRemaining() && !queue.isEmpty()) {
            ByteBuffer head = queue.peek();
            if (head == null) break;

            int n = Math.min(out.remaining(), head.remaining());
            int oldLimit = head.limit();
            head.limit(head.position() + n);
            out.put(head);
            head.limit(oldLimit);

            if (!head.hasRemaining()) {
                queue.poll();
            }
        }

        out.flip();
        return out;
    }

    private void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (lock) {
            leftQueue.clear();
            rightQueue.clear();
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
            StereoSplitter.this.close();
        }
    }
}
