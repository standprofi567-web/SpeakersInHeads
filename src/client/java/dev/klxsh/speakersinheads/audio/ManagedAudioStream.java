package dev.klxsh.speakersinheads.audio;

import net.minecraft.client.sounds.AudioStream;

import java.io.IOException;

/**
 * Common contract for managed audio streams consumed by {@link SpeakerSoundInstance}.
 * Decouples sound instance management from specific streaming strategies (e.g. queue vs. direct ring).
 */
public interface ManagedAudioStream extends AudioStream {

    /** Sets paused state without closing the underlying stream. */
    void setPaused(boolean value);

    /** Sets the effective gain (master × head × spatial) in [0, 1]. */
    void setGain(float value);

    /** Enables or disables the underwater low-pass DSP filter. */
    void setUnderwater(boolean value);

    /** Sets acoustic occlusion level through solid blocks in [0, 1]. */
    void setOcclusion(float value);

    /** Returns the current playback timestamp in seconds. */
    double secondsPlayed();

    /** Indicates whether the stream has reached natural end of track. */
    boolean isEnded();

    /** Returns any fatal decoding or streaming failure, or null if healthy. */
    IOException failure();

    /** Periodic diagnostic reporting hook called from client tick. */
    default void reportDiagnosticsIfDue() {}
}
