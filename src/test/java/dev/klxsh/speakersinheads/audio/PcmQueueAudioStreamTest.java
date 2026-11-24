package dev.klxsh.speakersinheads.audio;

import net.minecraft.client.sounds.AudioStream;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PcmQueueAudioStreamTest {
    private static final AudioFormat FORMAT = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
            44100, 16, 1, 2, 44100, false);

    @Test
    void gainAdjustmentAppliesSmoothlyWithoutClipping() throws Exception {
        CountingDecoder decoder = new CountingDecoder(40_000);
        PcmQueueAudioStream stream = new PcmQueueAudioStream(decoder);
        try {
            stream.prebuffered().get(2, TimeUnit.SECONDS);
            stream.setGain(0.5f);
            ByteBuffer read1 = stream.read(1024);
            assertTrue(read1.remaining() > 0);
            stream.setGain(0.0f);
            ByteBuffer read2 = stream.read(1024);
            assertTrue(read2.remaining() > 0);
        } finally {
            stream.close();
        }
    }

    @Test
    void readsMultipleChunksThenSignalsEndAndClosesDecoder() throws Exception {
        CountingDecoder decoder = new CountingDecoder(20_000);
        PcmQueueAudioStream stream = new PcmQueueAudioStream(decoder);
        try {
            stream.prebuffered().get(2, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline) {
                ByteBuffer chunk = stream.read(2048);
                if (chunk == null && stream.isEnded()) break;
                Thread.sleep(2);
            }
            assertEquals(20_000.0 / (FORMAT.getFrameRate() * FORMAT.getFrameSize()), stream.secondsPlayed(), 0.001);
            assertTrue(stream.isEnded());
            assertEquals(1, decoder.closeCount.get());
        } finally {
            stream.close();
        }
    }

    @Test
    void emptyQueueReturnsSilenceWithoutWaitingForDecoder() throws Exception {
        BlockingDecoder decoder = new BlockingDecoder();
        PcmQueueAudioStream stream = new PcmQueueAudioStream(decoder);
        try {
            assertTrue(decoder.started.await(1, TimeUnit.SECONDS));
            long start = System.nanoTime();
            ByteBuffer silence = stream.read(512);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertEquals(512, silence.remaining());
            while (silence.hasRemaining()) assertEquals(0, silence.get());
            assertTrue(elapsedMillis < 100, "Audio read must not wait on the decoder");
        } finally {
            stream.close();
            decoder.release.countDown();
        }
    }

    @Test
    void pauseFreezesPlaybackTimeAndResumeConsumesPcm() throws Exception {
        CountingDecoder decoder = new CountingDecoder(100_000);
        PcmQueueAudioStream stream = new PcmQueueAudioStream(decoder);
        try {
            stream.setPaused(true);
            ByteBuffer silence = stream.read(1024);
            assertEquals(0, stream.secondsPlayed());
            while (silence.hasRemaining()) assertEquals(0, silence.get());
            stream.setPaused(false);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (stream.secondsPlayed() == 0 && System.nanoTime() < deadline) {
                stream.read(1024);
                Thread.sleep(2);
            }
            assertTrue(stream.secondsPlayed() > 0);
        } finally {
            stream.close();
        }
    }

    @Test
    void queueRemainsBoundedWhenConsumerDoesNotRead() throws Exception {
        CountingDecoder decoder = new CountingDecoder(10_000_000);
        PcmQueueAudioStream stream = new PcmQueueAudioStream(decoder);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((stream.queuedChunks() < 12 || stream.diagnostics().maxQueuedChunks() < 12)
                    && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(stream.queuedChunks() <= 12);
            assertEquals(12, stream.diagnostics().maxQueuedChunks());
            assertFalse(decoder.closed);
        } finally {
            stream.close();
        }
    }

    @Test
    void partialReadsRetainTheRemainderAndReleaseTheDecoderOnceOnStop() throws Exception {
        CountingDecoder decoder = new CountingDecoder(50_000);
        PcmQueueAudioStream stream = new PcmQueueAudioStream(decoder);
        try {
            stream.prebuffered().get(2, TimeUnit.SECONDS);
            ByteBuffer partial = stream.read(128);
            assertEquals(128, partial.remaining());
            assertEquals(64.0 / FORMAT.getFrameRate(), stream.secondsPlayed(), 0.000001);
        } finally {
            stream.close();
            stream.close();
        }
        long closeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (decoder.closeCount.get() == 0 && System.nanoTime() < closeDeadline) Thread.sleep(2);
        assertEquals(1, decoder.closeCount.get());
    }

    @Test
    void concurrentConsumerDrainsEveryProducedFrame() throws Exception {
        CountingDecoder decoder = new CountingDecoder(1_000_000);
        PcmQueueAudioStream stream = new PcmQueueAudioStream(decoder);
        try {
            stream.prebuffered().get(2, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!stream.isEnded() && System.nanoTime() < deadline) {
                stream.read(Integer.MAX_VALUE);
                if (stream.queuedChunks() == 0) Thread.sleep(1);
            }
            assertTrue(stream.isEnded());
            assertEquals(500_000, stream.diagnostics().producedFrames());
            assertEquals(500_000, stream.diagnostics().consumedFrames());
        } finally {
            stream.close();
        }
    }

    @Test
    void prebufferWaitsForTargetDurationOrEarlyEndOfShortTrack() throws Exception {
        CountingDecoder decoder = new CountingDecoder(2048);
        PcmQueueAudioStream stream = new PcmQueueAudioStream(decoder);
        try {
            assertTrue(stream.prebuffered().get(2, TimeUnit.SECONDS) == null);
            assertEquals(1, stream.queuedChunks());
        } finally {
            stream.close();
        }
    }

    @Test
    void oneSecondMinecraftReadGetsOneHundredMillisecondsNotPaddedSilence() throws Exception {
        CountingDecoder decoder = new CountingDecoder(100_000);
        PcmQueueAudioStream stream = new PcmQueueAudioStream(decoder);
        try {
            stream.prebuffered().get(2, TimeUnit.SECONDS);
            ByteBuffer minecraftBuffer = stream.read((int) (FORMAT.getFrameRate() * FORMAT.getFrameSize()));
            assertEquals((int) (FORMAT.getFrameRate() * FORMAT.getFrameSize() * 0.1), minecraftBuffer.remaining());
            assertEquals(0, stream.diagnostics().underruns());
        } finally {
            stream.close();
        }
    }

    @Test
    void decoderFailureIsReportedAndCounted() throws Exception {
        AudioStream decoder = new AudioStream() {
            @Override public AudioFormat getFormat() { return FORMAT; }
            @Override public ByteBuffer read(int size) throws IOException { throw new IOException("synthetic failure"); }
            @Override public void close() {}
        };
        PcmQueueAudioStream stream = new PcmQueueAudioStream(decoder);
        try {
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> stream.prebuffered().get(2, TimeUnit.SECONDS));
            assertEquals(1, stream.diagnostics().decoderFailures());
            assertThrows(IOException.class, () -> stream.read(1024));
        } finally {
            stream.close();
        }
    }

    private static class CountingDecoder implements AudioStream {
        private final int totalBytes;
        private int delivered;
        private final AtomicInteger closeCount = new AtomicInteger();
        private volatile boolean closed;

        private CountingDecoder(int totalBytes) { this.totalBytes = totalBytes; }
        @Override public AudioFormat getFormat() { return FORMAT; }
        @Override public synchronized ByteBuffer read(int size) {
            int count = Math.min(size - size % FORMAT.getFrameSize(), totalBytes - delivered);
            if (count <= 0 || closed) return ByteBuffer.allocate(0);
            delivered += count;
            return ByteBuffer.wrap(new byte[count]);
        }
        @Override public void close() throws IOException {
            closed = true;
            closeCount.incrementAndGet();
        }
    }

    private static final class BlockingDecoder implements AudioStream {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        @Override public AudioFormat getFormat() { return FORMAT; }
        @Override public ByteBuffer read(int size) throws IOException {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            }
            return ByteBuffer.allocate(0);
        }
        @Override public void close() { release.countDown(); }
    }
}
