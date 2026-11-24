package dev.klxsh.speakersinheads.audio;

import net.minecraft.client.sounds.AudioStream;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

/** One background decoder and a bounded ring of absolute mono PCM frames. */
public final class GroupRingBroadcastStream {
    private static final Logger LOGGER = LoggerFactory.getLogger("SpeakersInHeads-SpeakerGroup");
    static final int CAPACITY = 1 << 21;
    private static final int MASK = CAPACITY - 1;
    private static final int READ_CHUNK_FRAMES = 4096;

    private final AudioStream source;
    private final AudioFormat format;
    private final LongSupplier masterFrameSupplier;
    private final long startFrame;
    private final int leadFrames;
    private final short[] ring = new short[CAPACITY];
    private final Object ringLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private volatile long writeFrame;
    private volatile boolean eof;
    private volatile IOException failure;
    private volatile Thread producer;

    public GroupRingBroadcastStream(AudioStream source) {
        this(source, () -> 0L, 0);
    }

    GroupRingBroadcastStream(AudioStream source, LongSupplier masterFrameSupplier, double startOffsetSeconds) {
        this.source = new MonoDownmixAudioStream(source);
        this.format = this.source.getFormat();
        this.masterFrameSupplier = masterFrameSupplier;
        this.startFrame = Math.max(0, Math.round(startOffsetSeconds * format.getSampleRate()));
        this.leadFrames = Math.round(format.getSampleRate() * 2);
        AudioDecoderExecutor.execute(this::produce);
    }

    public AudioFormat getFormat() { return format; }
    CompletableFuture<Void> ready() { return ready; }
    public boolean isEof() { return eof; }
    public long getTotalFrames() { return writeFrame; }
    public IOException failure() { return failure; }
    boolean isClosed() { return closed.get(); }

    public long getMasterPlaybackFrame() {
        return Math.max(startFrame, masterFrameSupplier.getAsLong());
    }

    long oldestFrame() {
        long end = writeFrame;
        return Math.max(Math.min(startFrame, end), end - CAPACITY);
    }

    public GroupMemberAudioStream createMemberStream() {
        return createMemberStream("Member", BlockPos.ZERO);
    }

    public GroupMemberAudioStream createMemberStream(String name, BlockPos pos) {
        return new GroupMemberAudioStream(this, name, pos);
    }

    int copyPcm(long fromFrame, int requestedFrames, ByteBuffer dest) {
        synchronized (ringLock) {
            if (closed.get() || fromFrame < oldestFrame() || fromFrame >= writeFrame) return 0;
            int count = (int) Math.min(requestedFrames, writeFrame - fromFrame);
            for (int i = 0; i < count; i++) {
                dest.putShort(ring[(int) ((fromFrame + i) & MASK)]);
            }
            return count;
        }
    }

    private void produce() {
        producer = Thread.currentThread();
        try (source) {
            while (!closed.get() && writeFrame < startFrame) {
                int frames = (int) Math.min(READ_CHUNK_FRAMES, startFrame - writeFrame);
                ByteBuffer skipped = readPcm(frames);
                if (skipped == null) {
                    eof = true;
                    break;
                }
                writeFrame += skipped.remaining() / format.getFrameSize();
            }
            while (!closed.get() && !eof) {
                if (writeFrame - getMasterPlaybackFrame() >= leadFrames) {
                    LockSupport.parkNanos(10_000_000L);
                    continue;
                }
                ByteBuffer pcm = readPcm(READ_CHUNK_FRAMES);
                if (pcm == null) {
                    eof = true;
                    break;
                }
                synchronized (ringLock) {
                    long next = writeFrame;
                    while (pcm.hasRemaining()) ring[(int) (next++ & MASK)] = pcm.getShort();
                    writeFrame = next;
                }
                if (writeFrame - startFrame >= Math.round(format.getSampleRate())) ready.complete(null);
            }
        } catch (IOException | RuntimeException exception) {
            if (!closed.get()) {
                failure = exception instanceof IOException io ? io : new IOException("Group decoder failed", exception);
                LOGGER.warn("Group PCM decoding failed at frame {}", writeFrame, failure);
            }
        } finally {
            producer = null;
            IOException error = failure;
            if (error != null) ready.completeExceptionally(error);
            else if (closed.get()) ready.completeExceptionally(new IOException("Group stream was closed"));
            else ready.complete(null);
        }
    }

    private ByteBuffer readPcm(int frames) throws IOException {
        int bytes = frames * format.getFrameSize();
        ByteBuffer pcm = source.read(bytes);
        // LocalAudioDecoder and Minecraft's finite Vorbis reader signal EOF with an empty result.
        if (pcm == null || !pcm.hasRemaining()) return null;
        if (pcm.remaining() > bytes || pcm.remaining() % format.getFrameSize() != 0) {
            throw new IOException("Group decoder returned an invalid PCM frame count");
        }
        return pcm.order(ByteOrder.LITTLE_ENDIAN);
    }

    public void closeAll() {
        if (!closed.compareAndSet(false, true)) return;
        LockSupport.unpark(producer);
    }
}
