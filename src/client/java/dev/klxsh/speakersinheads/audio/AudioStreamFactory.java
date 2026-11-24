package dev.klxsh.speakersinheads.audio;

import net.minecraft.client.sounds.AudioStream;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public final class AudioStreamFactory {
    private AudioStreamFactory() {}

    public static CompletableFuture<PcmQueueAudioStream> open(Path path, SpatialSettings.SpatialMode mode) {
        return open(path, mode, 0.0);
    }

    public static CompletableFuture<PcmQueueAudioStream> open(Path path, SpatialSettings.SpatialMode mode, double startOffsetSeconds) {
        try {
            return AudioDecoderExecutor.supply(() -> {
                AudioStream raw = LocalAudioDecoder.open(path);
                AudioStream processed = switch (mode) {
                    case MONO_SPATIAL -> new MonoDownmixAudioStream(raw);
                    case STEREO, VIRTUAL_STEREO -> raw;
                };
                String label = path.getFileName() != null ? path.getFileName().toString() : path.toString();
                return new PcmQueueAudioStream(processed, label + " [" + mode.displayName() + "]", startOffsetSeconds);
            });
        } catch (RuntimeException rejected) {
            return CompletableFuture.failedFuture(new IOException("Audio decoder workers are unavailable", rejected));
        }
    }

    public static CompletableFuture<PcmQueueAudioStream> openPrebuffered(Path path, SpatialSettings.SpatialMode mode) {
        return openPrebuffered(path, mode, 0.0);
    }

    public static CompletableFuture<PcmQueueAudioStream> openPrebuffered(Path path, SpatialSettings.SpatialMode mode, double startOffsetSeconds) {
        return open(path, mode, startOffsetSeconds).thenCompose(stream -> stream.prebuffered().handle((ignored, failure) -> {
            if (failure != null) {
                try {
                    stream.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw new java.util.concurrent.CompletionException(failure);
            }
            return stream;
        }));
    }

    public static CompletableFuture<PcmQueueAudioStream> openPrebuffered(Path path) {
        return openPrebuffered(path, SpatialSettings.get().mode(), 0.0);
    }

    public static CompletableFuture<PcmQueueAudioStream> openPrebuffered(Path path, double startOffsetSeconds) {
        return openPrebuffered(path, SpatialSettings.get().mode(), startOffsetSeconds);
    }

    public record VirtualStereoStreams(PcmQueueAudioStream left, PcmQueueAudioStream right) {}

    public static CompletableFuture<VirtualStereoStreams> openVirtualStereoPrebuffered(Path path) {
        return openVirtualStereoPrebuffered(path, 0.0);
    }

    public static CompletableFuture<VirtualStereoStreams> openVirtualStereoPrebuffered(Path path, double startOffsetSeconds) {
        return openStereoPrebuffered(path, startOffsetSeconds, false);
    }

    public static CompletableFuture<VirtualStereoStreams> openPhysicalStereoPairPrebuffered(Path path) {
        return openPhysicalStereoPairPrebuffered(path, 0.0);
    }

    public static CompletableFuture<VirtualStereoStreams> openPhysicalStereoPairPrebuffered(Path path, double startOffsetSeconds) {
        return openStereoPrebuffered(path, startOffsetSeconds, true);
    }

    private static CompletableFuture<VirtualStereoStreams> openStereoPrebuffered(
            Path path, double offset, boolean physicalPair) {
        try {
            return AudioDecoderExecutor.supply(() -> {
                AudioStream raw = LocalAudioDecoder.open(path);
                AudioStream leftSource;
                AudioStream rightSource;
                try {
                    if (raw.getFormat().getChannels() == 1) {
                        leftSource = raw;
                        rightSource = LocalAudioDecoder.open(path);
                    } else if (physicalPair) {
                        StereoRingBroadcastStream ring = new StereoRingBroadcastStream(raw);
                        leftSource = ring.leftStream();
                        rightSource = ring.rightStream();
                    } else {
                        StereoSplitter splitter = new StereoSplitter(raw);
                        leftSource = splitter.leftStream();
                        rightSource = splitter.rightStream();
                    }
                } catch (IOException | RuntimeException failure) {
                    closeAfterFailure(raw, failure);
                    throw failure;
                }
                String label = (path.getFileName() != null ? path.getFileName() : path) + (physicalPair ? " [Pair" : " [Virtual");
                PcmQueueAudioStream left;
                try {
                    left = new PcmQueueAudioStream(leftSource, label + "-L]", offset);
                } catch (RuntimeException failure) {
                    closeAfterFailure(rightSource, failure);
                    throw failure;
                }
                try {
                    return new VirtualStereoStreams(left, new PcmQueueAudioStream(rightSource, label + "-R]", offset));
                } catch (RuntimeException failure) {
                    closeAfterFailure(left, failure);
                    throw failure;
                }
            }).thenCompose(pair -> CompletableFuture.allOf(pair.left().prebuffered(), pair.right().prebuffered())
                    .handle((ignored, failure) -> {
                        if (failure != null) {
                            closeAfterFailure(pair.left(), failure);
                            closeAfterFailure(pair.right(), failure);
                            throw new java.util.concurrent.CompletionException(failure);
                        }
                        return pair;
                    }));
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            return CompletableFuture.failedFuture(new IOException("Audio decoder workers are unavailable", rejected));
        }
    }

    private static void closeAfterFailure(AudioStream stream, Throwable failure) {
        try {
            stream.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    static CompletableFuture<GroupRingBroadcastStream> openGroupBroadcast(
            Path path, double offsetSeconds, java.util.function.DoubleSupplier playbackSeconds) {
        try {
            return AudioDecoderExecutor.supply(() -> {
                AudioStream raw = LocalAudioDecoder.open(path);
                try {
                    double sampleRate = raw.getFormat().getSampleRate();
                    if (sampleRate < 8_000 || sampleRate > 192_000) {
                        throw new IOException("Group playback requires 8-192 kHz PCM");
                    }
                    return new GroupRingBroadcastStream(raw,
                            () -> Math.round(playbackSeconds.getAsDouble() * sampleRate), offsetSeconds);
                } catch (IOException | RuntimeException failure) {
                    try {
                        raw.close();
                    } catch (IOException closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                    throw failure;
                }
            });
        } catch (RuntimeException rejected) {
            return CompletableFuture.failedFuture(new IOException("Audio decoder workers are unavailable", rejected));
        }
    }

    public static CompletableFuture<AudioStream> openAsMinecraftStream(Path path) {
        return open(path, SpatialSettings.SpatialMode.STEREO).thenApply(stream -> stream);
    }

    public static void shutdown() {
        AudioDecoderExecutor.shutdown();
    }

    public static void validateLocalFile(java.nio.file.Path path) throws java.io.IOException {
        probeLocalFile(path);
    }

    public static javax.sound.sampled.AudioFormat probeLocalFile(java.nio.file.Path path) throws java.io.IOException {
        try (var decoder = LocalAudioDecoder.open(path)) {
            var pcm = decoder.read(4096);
            if (pcm == null || !pcm.hasRemaining()) {
                throw new java.io.IOException("The decoder returned no audio.");
            }
            return decoder.getFormat();
        }
    }
}
