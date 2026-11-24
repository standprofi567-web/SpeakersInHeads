package dev.klxsh.speakersinheads.audio;

/** Accessed only on Minecraft's sound executor. */
public interface GroupPauseChannel {
    int sih$source();
    void sih$setGroupPaused(boolean paused);
    boolean sih$hasPendingPlay();
    void sih$clearPendingPlay();
}
