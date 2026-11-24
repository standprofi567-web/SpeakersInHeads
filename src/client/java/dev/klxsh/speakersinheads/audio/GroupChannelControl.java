package dev.klxsh.speakersinheads.audio;

import dev.klxsh.speakersinheads.mixin.ChannelHandleAccessor;
import dev.klxsh.speakersinheads.mixin.SoundEngineAccessor;
import dev.klxsh.speakersinheads.mixin.SoundManagerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.ChannelAccess;
import org.lwjgl.openal.AL10;

import java.util.ArrayList;
import java.util.Collection;

final class GroupChannelControl {
    private GroupChannelControl() {}

    static void align(Minecraft client, Collection<SpeakerSoundInstance> sounds, String group) {
        if (sounds.size() < 2 || client.isPaused()) return;
        var engine = (SoundEngineAccessor) ((SoundManagerAccessor) client.getSoundManager()).getSoundEngine();
        record Pending(ChannelAccess.ChannelHandle handle, SpeakerSoundInstance sound) {}
        var pending = new ArrayList<Pending>(sounds.size());
        for (var sound : sounds) {
            var handle = engine.getInstanceToChannel().get(sound);
            if (handle == null) return;
            pending.add(new Pending(handle, sound));
        }
        engine.getExecutor().execute(() -> {
            var members = new ArrayList<GroupOffsetSync.Member>(pending.size());
            for (var entry : pending) {
                var channel = ((ChannelHandleAccessor) entry.handle()).getChannel();
                var stream = entry.sound().groupStream();
                if (channel == null || stream == null || entry.sound().isStopped()) return;
                members.add(new GroupOffsetSync.Member(((GroupPauseChannel) channel).sih$source(), stream));
            }
            var result = GroupOffsetSync.align(members);
            org.slf4j.LoggerFactory.getLogger("SpeakersInHeads-SpeakerGroupSync").info(
                    "Group {} Sync Now: {}; members={}, initialSpreadFrames={}", group,
                    result.status(), members.size(), result.spreadFrames());
        });
    }

    static void setPaused(Minecraft client, Collection<SpeakerSoundInstance> sounds, boolean paused) {
        if (sounds.isEmpty()) return;
        var engine = (SoundEngineAccessor) ((SoundManagerAccessor) client.getSoundManager()).getSoundEngine();
        var handles = new ArrayList<ChannelAccess.ChannelHandle>(sounds.size());
        for (var sound : sounds) {
            var handle = engine.getInstanceToChannel().get(sound);
            if (handle != null) handles.add(handle);
        }
        boolean gamePaused = client.isPaused();
        engine.getExecutor().execute(() -> {
            var sources = new int[handles.size()];
            int count = 0;
            for (var handle : handles) {
                var channel = ((ChannelHandleAccessor) handle).getChannel();
                if (channel == null) continue; // Released by Minecraft on the same executor.
                var groupChannel = (GroupPauseChannel) channel;
                groupChannel.sih$setGroupPaused(paused);
                int source = groupChannel.sih$source();
                int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
                if (state == (paused ? AL10.AL_PLAYING : AL10.AL_PAUSED)) sources[count++] = source;
                else if (!paused && !gamePaused && state == AL10.AL_INITIAL && groupChannel.sih$hasPendingPlay()) {
                    groupChannel.sih$clearPendingPlay();
                    sources[count++] = source;
                }
            }
            if (count > 0 && (paused || !gamePaused)) {
                setSourcesPaused(java.util.Arrays.copyOf(sources, count), paused);
            }
        });
    }

    static void setSourcesPaused(int[] sources, boolean paused) {
        if (paused) AL10.alSourcePausev(sources);
        else AL10.alSourcePlayv(sources);
    }
}
