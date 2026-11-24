package dev.klxsh.speakersinheads.mixin;

import com.mojang.blaze3d.audio.Channel;
import dev.klxsh.speakersinheads.audio.ManagedAudioStream;
import dev.klxsh.speakersinheads.audio.SprAcoustics;
import net.minecraft.client.sounds.AudioStream;
import dev.klxsh.speakersinheads.audio.GroupPauseChannel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Channel.class)
abstract class GroupChannelMixin implements GroupPauseChannel {
    @Shadow @Final private int source;
    @Shadow public abstract void play();
    @Unique private boolean sih$groupPaused;
    @Unique private boolean sih$pendingPlay;

    @Override public int sih$source() { return source; }
    @Override public void sih$setGroupPaused(boolean paused) { sih$groupPaused = paused; }
    @Override public boolean sih$hasPendingPlay() { return sih$pendingPlay; }
    @Override public void sih$clearPendingPlay() { sih$pendingPlay = false; }

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void sih$holdNewSource(CallbackInfo callback) {
        if (sih$groupPaused) {
            sih$pendingPlay = true;
            callback.cancel();
        }
    }

    @Inject(method = "unpause", at = @At("HEAD"), cancellable = true)
    private void sih$keepGroupPaused(CallbackInfo callback) {
        if (sih$groupPaused) {
            callback.cancel();
        } else if (sih$pendingPlay) {
            sih$pendingPlay = false;
            play();
            callback.cancel();
        }
    }

    @Inject(method = "attachBufferStream", at = @At("HEAD"))
    private void sih$attachAcoustics(AudioStream stream, CallbackInfo callback) {
        if (stream instanceof ManagedAudioStream) SprAcoustics.attach(source);
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void sih$detachAcoustics(CallbackInfo callback) {
        SprAcoustics.detach(source);
    }
}
