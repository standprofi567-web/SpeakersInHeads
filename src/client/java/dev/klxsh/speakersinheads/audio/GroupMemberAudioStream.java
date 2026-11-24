package dev.klxsh.speakersinheads.audio;

import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/** Supplies sequential mono PCM to one Minecraft streaming channel. */
public final class GroupMemberAudioStream implements ManagedAudioStream {
    private static final Logger LOGGER = LoggerFactory.getLogger("SpeakersInHeads-SpeakerGroupSync");
    private static final int BUFFERS_PER_SECOND = 10;
    private static final boolean DIAGNOSTICS = Boolean.getBoolean("sih.groupDiagnostics");

    private final GroupRingBroadcastStream broadcast;
    private final String name;
    private final BlockPos position;
    private final AudioFormat format;
    private final int frameSize;
    private final int framesPerBuffer;
    private final ByteBuffer output;
    private final UnderwaterAudioFilter underwaterFilter;
    private final IndoorAudioFilter indoorFilter;
    private final OutputBoost outputBoost;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean paused;
    private volatile float targetGain = 1.0f;
    private float currentGain = 1.0f;
    private volatile long nextFrame = -1;
    private long contiguousFrames;
    private int lastSubmission;
    private volatile boolean submittedPcm;
    private long nextReportNanos;
    private long underrunFrames;
    private long overwrittenFrames;

    public GroupMemberAudioStream(GroupRingBroadcastStream broadcast, String name, BlockPos position) {
        this.broadcast = broadcast;
        this.name = name;
        this.position = position.immutable();
        this.format = broadcast.getFormat();
        this.frameSize = format.getFrameSize();
        this.framesPerBuffer = Math.max(1, Math.round(format.getSampleRate() / BUFFERS_PER_SECOND));
        this.output = ByteBuffer.allocateDirect(framesPerBuffer * frameSize).order(ByteOrder.LITTLE_ENDIAN);
        this.underwaterFilter = new UnderwaterAudioFilter(format.getSampleRate(), format.getChannels());
        this.indoorFilter = new IndoorAudioFilter(format.getSampleRate(), format.getChannels());
        this.outputBoost = new OutputBoost(format.getSampleRate(), format.getChannels());
    }

    @Override
    public AudioFormat getFormat() { return format; }

    @Override
    public ByteBuffer read(int requestedBytes) throws IOException {
        int requestedFrames = Math.min(requestedBytes / frameSize, framesPerBuffer);
        if (requestedFrames <= 0 || closed.get() || broadcast.isClosed()) return null;
        IOException error = failure();
        if (error != null) throw error;

        output.clear();
        if (paused) {
            contiguousFrames = 0;
            lastSubmission = 0;
            return appendSilence(requestedFrames).flip();
        }

        long master = broadcast.getMasterPlaybackFrame();
        if (nextFrame < 0) {
            currentGain = targetGain;
            nextFrame = Math.max(master, broadcast.oldestFrame());
            LOGGER.debug("Group member {} at {} started at frame {}", name, position.toShortString(), nextFrame);
        } else if (nextFrame < broadcast.oldestFrame()) {
            long recoveredFrame = Math.max(master, broadcast.oldestFrame());
            overwrittenFrames += recoveredFrame - nextFrame;
            nextFrame = recoveredFrame;
            contiguousFrames = 0;
        }

        int delivered = broadcast.copyPcm(nextFrame, requestedFrames, output);
        if (delivered < requestedFrames && !broadcast.isEof()) {
            // A native slot is already due. Consume the missing media interval as silence so it
            // cannot be played late while other sources have already moved on.
            underrunFrames += requestedFrames - delivered;
            appendSilence(requestedFrames - delivered);
        }
        if (output.position() == 0) return null;
        if (delivered > 0) submittedPcm = true;
        lastSubmission = output.position() / frameSize;
        contiguousFrames += lastSubmission;
        nextFrame += lastSubmission;
        output.flip();
        applyGain();
        underwaterFilter.process(output, output.remaining());
        indoorFilter.process(output, output.remaining());
        outputBoost.process(output, output.remaining());

        long now = System.nanoTime();
        if (DIAGNOSTICS && now >= nextReportNanos) {
            nextReportNanos = now + 5_000_000_000L;
            LOGGER.info("Group member {} at {}: rate={}, clockFrame={}, decoderFrame={}, nextSubmittedFrame={}, submittedFrames={}, underrunFrames={}, overwrittenFrames={}",
                    name, position.toShortString(), format.getSampleRate(), master, broadcast.getTotalFrames(),
                    nextFrame, output.remaining() / frameSize, underrunFrames, overwrittenFrames);
        }
        // Minecraft uploads this buffer synchronously before calling read again on this channel.
        return output.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    }

    private ByteBuffer appendSilence(int frames) {
        for (int i = 0; i < frames; i++) output.putShort((short) 0);
        return output;
    }

    private void applyGain() {
        float target = targetGain;
        if (currentGain == 1.0f && target == 1.0f) return;
        float step = (target - currentGain) / (output.remaining() / frameSize);
        float gain = currentGain;
        for (int i = 0; i < output.limit(); i += frameSize) {
            output.putShort(i, (short) Math.round(output.getShort(i) * gain));
            gain += step;
        }
        currentGain = target;
    }

    long currentFrameIndex() { return nextFrame; }
    // Read only on the sound executor, after Minecraft has uploaded the last submission.
    long queueStartFrame(int queued) {
        if (closed.get() || paused || queued < 1 || lastSubmission == 0) return -1;
        long frames = (queued - 1L) * framesPerBuffer + lastSubmission;
        return frames <= contiguousFrames ? nextFrame - frames : -1;
    }
    boolean hasSubmittedPcm() { return submittedPcm; }

    @Override public void setPaused(boolean value) { paused = value; }
    @Override public void setGain(float value) { targetGain = Math.clamp(value, 0.0f, 1.0f); }
    @Override public void setUnderwater(boolean value) { underwaterFilter.setUnderwater(value); }
    @Override public void setOcclusion(float value) { indoorFilter.setOcclusion(value); }
    @Override public double secondsPlayed() { return Math.max(0, nextFrame) / (double) format.getSampleRate(); }
    @Override public boolean isEnded() { return broadcast.isEof() && nextFrame >= broadcast.getTotalFrames(); }
    @Override public IOException failure() { return broadcast.failure(); }
    @Override public void close() { closed.set(true); }

}
