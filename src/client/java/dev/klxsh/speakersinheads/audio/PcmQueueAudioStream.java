package dev.klxsh.speakersinheads.audio;

import dev.klxsh.speakersinheads.SpeakersInHeadsClient;
import net.minecraft.client.sounds.AudioStream;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/** Bounded single-producer/single-consumer PCM bridge for Minecraft's streaming sound thread. */
public final class PcmQueueAudioStream implements ManagedAudioStream {
    private static final int QUEUE_CHUNKS = 12;
    private static final int BUFFER_MILLIS = 100;
    private static final int PREBUFFER_MILLIS = 250;
    private static final long REPORT_INTERVAL_NANOS = 30_000_000_000L;

    private final AudioStream decoder;
    private final AudioFormat format;
    private final int frameSize;
    private final int chunkBytes;
    private final long prebufferFrames;
    private final BufferRing pcm = new BufferRing(QUEUE_CHUNKS);
    // One spare is the chunk currently retained by the consumer until Minecraft uploads it.
    private final BufferRing free = new BufferRing(QUEUE_CHUNKS + 1);
    private final ByteBuffer silence;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean decoderClosed = new AtomicBoolean();
    private final AtomicBoolean paused = new AtomicBoolean();
    private final AtomicBoolean producerDone = new AtomicBoolean();
    private final AtomicBoolean ended = new AtomicBoolean();
    private final AtomicBoolean diagnosticsLogged = new AtomicBoolean();
    private final AtomicReference<IOException> failure = new AtomicReference<>();
    private final AtomicLong producedFrames = new AtomicLong();
    private final AtomicLong consumedFrames = new AtomicLong();
    private final AtomicLong maxQueuedChunks = new AtomicLong();
    private final AtomicLong decoderFailures = new AtomicLong();
    private final AtomicLong decoderReadNanos = new AtomicLong();
    private final LongAdder underruns = new LongAdder();
    private final LongAdder streamReadCount = new LongAdder();
    private final LongAdder streamReadNanos = new LongAdder();
    private final AtomicLong maxStreamReadNanos = new AtomicLong();
    private final CompletableFuture<Void> prebuffered = new CompletableFuture<>();
    private final String sourceLabel;
    private final double startOffsetSeconds;
    private final UnderwaterAudioFilter underwaterFilter;
    private final IndoorAudioFilter indoorFilter;
    private final OutputBoost outputBoost;

    private volatile float gain = 1.0f;
    private float currentGain = 1.0f;
    private volatile Thread producer;
    private volatile long nextReportNanos = System.nanoTime() + REPORT_INTERVAL_NANOS;
    private ByteBuffer current = ByteBuffer.allocate(0);
    private ByteBuffer lastReturned;

    public PcmQueueAudioStream(AudioStream decoder) {
        this(decoder, "local audio", 0.0);
    }

    public PcmQueueAudioStream(AudioStream decoder, String sourceLabel) {
        this(decoder, sourceLabel, 0.0);
    }

    public PcmQueueAudioStream(AudioStream decoder, String sourceLabel, double startOffsetSeconds) {
        this.decoder = decoder;
        this.sourceLabel = sourceLabel;
        this.startOffsetSeconds = Math.max(0.0, startOffsetSeconds);
        this.format = decoder.getFormat();
        this.frameSize = format.getFrameSize();
        this.underwaterFilter = new UnderwaterAudioFilter(format.getSampleRate(), format.getChannels());
        this.indoorFilter = new IndoorAudioFilter(format.getSampleRate(), format.getChannels());
        this.outputBoost = new OutputBoost(format.getSampleRate(), format.getChannels());
        if (!AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding()) || format.getSampleSizeInBits() != 16
                || format.isBigEndian() || format.getChannels() < 1 || format.getChannels() > 2
                || frameSize <= 0 || !Float.isFinite(format.getFrameRate())
                || format.getFrameRate() < 8_000 || format.getFrameRate() > 192_000) {
            closeInvalidDecoder(decoder, new IllegalArgumentException(
                    "PCM queue requires 8–192 kHz mono/stereo little-endian PCM16: " + format));
            throw new IllegalArgumentException("Unsupported Minecraft PCM format: " + format);
        }
        long bytesPerChunk = Math.round(format.getFrameRate() * frameSize * BUFFER_MILLIS / 1000.0);
        this.chunkBytes = (int) Math.max(frameSize, bytesPerChunk - bytesPerChunk % frameSize);
        this.prebufferFrames = Math.max(1, Math.round(format.getFrameRate() * PREBUFFER_MILLIS / 1000.0));
        this.silence = ByteBuffer.allocateDirect(chunkBytes);
        for (int i = 0; i < QUEUE_CHUNKS + 1; i++) {
            if (!free.offer(ByteBuffer.allocateDirect(chunkBytes))) {
                throw new IllegalStateException("Could not initialize bounded PCM buffer pool");
            }
        }
        try {
            AudioDecoderExecutor.execute(this::produce);
        } catch (RuntimeException exception) {
            closeInvalidDecoder(decoder, exception);
            throw exception;
        }
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    /** Completes when 250 ms of PCM is ready, or a shorter file reaches EOF. */
    public CompletableFuture<Void> prebuffered() {
        return prebuffered;
    }

    @Override
    public ByteBuffer read(int requestedBytes) throws IOException {
        long started = System.nanoTime();
        try {
            return readChunk(requestedBytes);
        } finally {
            long elapsed = System.nanoTime() - started;
            streamReadCount.increment();
            streamReadNanos.add(elapsed);
            updateMax(maxStreamReadNanos, elapsed);
        }
    }

    private ByteBuffer readChunk(int requestedBytes) throws IOException {
        if (requestedBytes <= 0 || closed.get() || ended.get()) return null;
        int outputLimit = Math.min(requestedBytes, chunkBytes);
        outputLimit -= outputLimit % frameSize;
        if (outputLimit == 0) return null;
        if (paused.get()) return silenceView(outputLimit);

        recyclePreviousChunk();
        if (!current.hasRemaining()) {
            ByteBuffer available = pcm.poll();
            if (available == null) {
                current = ByteBuffer.allocate(0);
                IOException decodeFailure = failure.get();
                if (producerDone.get()) {
                    if (decodeFailure != null) throw decodeFailure;
                    ended.set(true);
                    return null;
                }
                underruns.increment();
                return silenceView(outputLimit);
            }
            current = available;
        }

        int count = Math.min(outputLimit, current.remaining());
        count -= count % frameSize;
        if (count <= 0) {
            underruns.increment();
            return silenceView(outputLimit);
        }

        ByteBuffer result = current.slice();
        result.limit(count);
        applyGainWithSmoothing(result, count, gain);
        underwaterFilter.process(result, count);
        indoorFilter.process(result, count);
        outputBoost.process(result, count);
        current.position(current.position() + count);
        if (!current.hasRemaining()) {
            lastReturned = current;
            current = ByteBuffer.allocate(0);
        }
        consumedFrames.addAndGet(count / frameSize);
        return result;
    }

    private ByteBuffer silenceView(int count) {
        ByteBuffer result = silence.asReadOnlyBuffer();
        result.position(0);
        result.limit(count);
        return result;
    }

    private void recyclePreviousChunk() {
        ByteBuffer previous = lastReturned;
        if (previous == null) return;
        previous.clear();
        if (!free.offer(previous) && !closed.get()) {
            failure.compareAndSet(null, new IOException("PCM buffer pool invariant failed while recycling a chunk"));
        }
        lastReturned = null;
    }

    public void setPaused(boolean value) {
        paused.set(value);
    }

    public void setGain(float value) {
        gain = Math.max(0.0f, Math.min(1.0f, value));
    }

    public void setUnderwater(boolean value) {
        underwaterFilter.setUnderwater(value);
    }

    public boolean isUnderwater() {
        return underwaterFilter.isUnderwater();
    }

    public void setIndoor(boolean value) {
        indoorFilter.setIndoor(value);
    }

    public boolean isIndoor() {
        return indoorFilter.isIndoor();
    }

    public void setOcclusion(float value) {
        indoorFilter.setOcclusion(value);
    }

    public float getOcclusion() {
        return indoorFilter.getOcclusion();
    }

    public double secondsPlayed() {
        return startOffsetSeconds + (consumedFrames.get() / format.getFrameRate());
    }

    public boolean isEnded() {
        return ended.get();
    }

    public IOException failure() {
        return failure.get();
    }

    public int queuedChunks() {
        return pcm.size();
    }

    public AudioDiagnostics diagnostics() {
        return new AudioDiagnostics(producedFrames.get(), consumedFrames.get(), pcm.size(),
                maxQueuedChunks.get(), underruns.sum(), decoderFailures.get(), decoderReadNanos.get(),
                streamReadCount.sum(), streamReadNanos.sum(), maxStreamReadNanos.get());
    }

    /** Called from the client tick, never from the Sound Engine read callback. */
    public void reportDiagnosticsIfDue() {
        long now = System.nanoTime();
        if (now >= nextReportNanos) {
            nextReportNanos = now + REPORT_INTERVAL_NANOS;
            logDiagnosticsIfUseful("periodic");
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        prebuffered.completeExceptionally(new IOException("Audio stream was closed before prebuffer completed"));
        Thread worker = producer;
        if (worker != null) worker.interrupt();
        pcm.clear();
        free.clear();
        try {
            AudioDecoderExecutor.execute(this::closeDecoderAndLog);
        } catch (RuntimeException rejected) {
            // Executor shutdown is terminal; use the shared JDK pool rather than block the Sound Engine thread.
            CompletableFuture.runAsync(this::closeDecoderAndLog);
        }
    }

    private void produce() {
        producer = Thread.currentThread();
        ByteBuffer target = null;
        try {
            if (startOffsetSeconds > 0) {
                long framesToSkip = Math.round(startOffsetSeconds * format.getFrameRate());
                long bytesToSkip = framesToSkip * frameSize;
                while (bytesToSkip > 0 && !closed.get()) {
                    int toRead = (int) Math.min(bytesToSkip, 16384);
                    ByteBuffer skipped = decoder.read(toRead);
                    if (skipped == null || !skipped.hasRemaining()) break;
                    bytesToSkip -= skipped.remaining();
                }
            }

            while (!closed.get()) {
                target = free.poll();
                if (target == null) {
                    waitForConsumer();
                    continue;
                }
                target.clear();
                long decodeStarted = System.nanoTime();
                ByteBuffer decoded;
                try {
                    decoded = decoder.read(chunkBytes);
                } finally {
                    decoderReadNanos.addAndGet(System.nanoTime() - decodeStarted);
                }
                if (decoded == null || !decoded.hasRemaining()) {
                    free.offer(target);
                    target = null;
                    break;
                }
                int count = Math.min(decoded.remaining(), target.remaining());
                count -= count % frameSize;
                if (count == 0) throw new IOException("Decoder returned an incomplete PCM frame");
                decoded.limit(decoded.position() + count);
                target.put(decoded).flip();
                if (!pcm.offer(target)) {
                    // There is only one producer. A full queue is ordinary backpressure, not a dropped chunk.
                    while (!closed.get() && !pcm.offer(target)) waitForConsumer();
                    if (closed.get()) break;
                }
                producedFrames.addAndGet(count / frameSize);
                target = null;
                updateMax(maxQueuedChunks, pcm.size());
                if (producedFrames.get() - consumedFrames.get() >= prebufferFrames) prebuffered.complete(null);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException exception) {
            if (!closed.get()) {
                IOException wrapped = exception instanceof IOException io ? io
                        : new IOException("Audio decoder failed", exception);
                if (failure.compareAndSet(null, wrapped)) decoderFailures.incrementAndGet();
            }
        } finally {
            if (target != null && !closed.get()) {
                target.clear();
                free.offer(target);
            }
            producerDone.set(true);
            if (!prebuffered.isDone()) {
                if (failure.get() != null && producedFrames.get() == 0) {
                    prebuffered.completeExceptionally(failure.get());
                } else {
                    prebuffered.complete(null);
                }
            }
            try {
                closeDecoder();
            } catch (IOException exception) {
                if (!closed.get() && failure.compareAndSet(null, exception)) decoderFailures.incrementAndGet();
            }
            if (closed.get()) logDiagnosticsIfUseful("closed");
        }
    }

    private static void waitForConsumer() throws InterruptedException {
        LockSupport.parkNanos(1_000_000L);
        if (Thread.interrupted()) throw new InterruptedException();
    }

    private void closeDecoder() throws IOException {
        if (decoderClosed.compareAndSet(false, true)) decoder.close();
    }

    private void closeDecoderAndLog() {
        try {
            closeDecoder();
        } catch (IOException exception) {
            if (!closed.get() && failure.compareAndSet(null, exception)) decoderFailures.incrementAndGet();
        } finally {
            logDiagnosticsIfUseful("closed");
        }
    }

    private void logDiagnosticsIfUseful(String reason) {
        AudioDiagnostics stats = diagnostics();
        if (stats.underruns() == 0 && stats.decoderFailures() == 0) return;
        if (!diagnosticsLogged.compareAndSet(false, true)) return;
        double decoderToAudioRatio = stats.producedFrames() == 0 ? 0.0
                : (double) stats.decoderReadNanos() * format.getFrameRate()
                    / (stats.producedFrames() * 1_000_000_000.0);
        long averageReadMicros = stats.streamReadCount() == 0 ? 0
                : stats.streamReadNanos() / stats.streamReadCount() / 1_000;
        SpeakersInHeadsClient.LOGGER.info(
                "Audio diagnostics ({}): source={}, producedFrames={}, consumedFrames={}, queuedChunks={}/{}, "
                        + "underrunReads={}, decoderFailures={}, decodeToAudioRatio={}, reads={}, avgReadUs={}, maxReadUs={}",
                reason, sourceLabel, stats.producedFrames(), stats.consumedFrames(), stats.queuedChunks(),
                QUEUE_CHUNKS, stats.underruns(), stats.decoderFailures(), decoderToAudioRatio,
                stats.streamReadCount(), averageReadMicros, stats.maxStreamReadNanos() / 1_000);
    }

    private void applyGainWithSmoothing(ByteBuffer bytes, int length, float targetGain) {
        if (currentGain >= 0.9999f && targetGain >= 0.9999f) {
            currentGain = 1.0f;
            return;
        }

        int samples = length / 2;
        if (samples <= 0) return;

        boolean smooth = Math.abs(currentGain - targetGain) >= 0.0005f;
        float step = smooth ? (targetGain - currentGain) / samples : 0.0f;
        float g = currentGain;

        for (int i = 0; i + 1 < length; i += 2) {
            if (smooth) {
                g += step;
            }
            int low = bytes.get(i) & 0xFF;
            int high = bytes.get(i + 1);
            short sample = (short) (low | high << 8);
            int scaled = Math.round(sample * g);
            scaled = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
            bytes.put(i, (byte) scaled);
            bytes.put(i + 1, (byte) (scaled >> 8));
        }

        currentGain = targetGain;
    }

    private static void updateMax(AtomicLong maximum, long value) {
        long previous = maximum.get();
        while (value > previous && !maximum.compareAndSet(previous, value)) previous = maximum.get();
    }

    private static void closeInvalidDecoder(AudioStream decoder, RuntimeException reason) {
        try {
            decoder.close();
        } catch (IOException closeFailure) {
            reason.addSuppressed(closeFailure);
        }
    }

    public record AudioDiagnostics(long producedFrames, long consumedFrames, int queuedChunks,
                                   long maxQueuedChunks, long underruns, long decoderFailures,
                                   long decoderReadNanos, long streamReadCount, long streamReadNanos,
                                   long maxStreamReadNanos) {}

    /** Lock-free bounded ring: one decoder producer, one Minecraft stream consumer. */
    private static final class BufferRing {
        private final AtomicReferenceArray<ByteBuffer> buffers;
        private final AtomicLong writeCursor = new AtomicLong();
        private final AtomicLong readCursor = new AtomicLong();

        private BufferRing(int capacity) {
            buffers = new AtomicReferenceArray<>(capacity);
        }

        private boolean offer(ByteBuffer value) {
            long write = writeCursor.get();
            if (write - readCursor.get() >= buffers.length()) return false;
            int slot = (int) (write % buffers.length());
            buffers.set(slot, value);
            writeCursor.set(write + 1);
            return true;
        }

        private ByteBuffer poll() {
            long read = readCursor.get();
            if (read >= writeCursor.get()) return null;
            int slot = (int) (read % buffers.length());
            ByteBuffer value = buffers.getAndSet(slot, null);
            if (value == null) return null;
            readCursor.set(read + 1);
            return value;
        }

        private int size() {
            return (int) Math.min(buffers.length(), Math.max(0, writeCursor.get() - readCursor.get()));
        }

        private void clear() {
            for (int i = 0; i < buffers.length(); i++) buffers.set(i, null);
            readCursor.set(writeCursor.get());
        }
    }

}
