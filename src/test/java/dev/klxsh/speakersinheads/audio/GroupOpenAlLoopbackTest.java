package dev.klxsh.speakersinheads.audio;

import com.mojang.blaze3d.audio.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.AL11.AL_SAMPLE_OFFSET;
import static org.lwjgl.openal.ALC10.*;
import static org.lwjgl.openal.SOFTLoopback.*;

@EnabledIfEnvironmentVariable(named = "SIH_OPENAL_LOOPBACK", matches = "1")
class GroupOpenAlLoopbackTest {
    private static final int RATE = 48_000;

    @Test
    void minecraftChannelUploadsAndRendersGroupPcmIncludingLateJoin() throws Exception {
        long device = alcLoopbackOpenDeviceSOFT((ByteBuffer) null);
        assertNotEquals(0, device);
        long context = 0;
        List<Channel> channels = new ArrayList<>();
        AtomicLong clock = new AtomicLong();
        GroupRingBroadcastStream ring = null;
        try {
            assertTrue(alcIsRenderFormatSupportedSOFT(device, RATE, ALC_STEREO_SOFT, ALC_SHORT_SOFT));
            context = alcCreateContext(device, new int[]{
                    ALC_FORMAT_CHANNELS_SOFT, ALC_STEREO_SOFT,
                    ALC_FORMAT_TYPE_SOFT, ALC_SHORT_SOFT,
                    ALC_FREQUENCY, RATE, 0
            });
            assertNotEquals(0, context);
            assertTrue(alcMakeContextCurrent(context));
            AL.createCapabilities(ALC.createCapabilities(device));

            ring = new GroupRingBroadcastStream(new DistanceIndependentGroupSyncTest.Ramp(RATE, RATE * 3L), clock::get, 0);
            ring.ready().get(3, TimeUnit.SECONDS);
            Method create = Channel.class.getDeclaredMethod("create");
            create.setAccessible(true);
            Field source = Channel.class.getDeclaredField("source");
            source.setAccessible(true);

            List<GroupMemberAudioStream> groupStreams = new ArrayList<>();
            for (int i = 0; i < 4; i++) groupStreams.add(startChannel(ring, channels, create, source, i * 0.18f));
            short[] samples = new short[1200 * 2];
            alcRenderSamplesSOFT(device, samples, 1200);
            assertTrue(containsAudio(samples));
            int[] groupSources = new int[4];
            for (int i = 0; i < groupSources.length; i++) groupSources[i] = source.getInt(channels.get(i));
            for (int cycle = 0; cycle < 6; cycle++) {
                GroupChannelControl.setSourcesPaused(groupSources, true);
                alcRenderSamplesSOFT(device, samples, 1200);
                for (int id : groupSources) {
                    assertEquals(AL_PAUSED, alGetSourcei(id, AL_SOURCE_STATE));
                    assertEquals(1200, alGetSourcei(id, AL_SAMPLE_OFFSET));
                    assertEquals(4, alGetSourcei(id, AL_BUFFERS_QUEUED));
                }
                GroupChannelControl.setSourcesPaused(groupSources, false);
                for (int id : groupSources) assertEquals(AL_PLAYING, alGetSourcei(id, AL_SOURCE_STATE));
            }
            clock.addAndGet(1200);

            GroupMemberAudioStream late = startChannel(ring, channels, create, source, 0.4f);
            assertEquals(1200 + 4 * 4800, late.currentFrameIndex());
            alcRenderSamplesSOFT(device, samples, 1200);
            for (int i = 0; i < channels.size(); i++) {
                int sourceId = source.getInt(channels.get(i));
                assertEquals(i < 4 ? 2400 : 1200, alGetSourcei(sourceId, AL_SAMPLE_OFFSET));
                assertTrue(channels.get(i).playing());
            }
            // The first queued frame for the late source already included the 1,200-frame delay.
            assertEquals(2400, late.currentFrameIndex() - 4 * 4800 + alGetSourcei(source.getInt(channels.getLast()), AL_SAMPLE_OFFSET));
            int played = 0;
            for (float recordsGain : new float[]{0f, 0.5f, 1f, 0.25f}) {
                channels.forEach(channel -> channel.setVolume(0.1f * recordsGain));
                alcRenderSamplesSOFT(device, samples, 300);
                played += 300;
                for (int i = 0; i < channels.size(); i++) {
                    int id = source.getInt(channels.get(i));
                    assertEquals(AL_PLAYING, alGetSourcei(id, AL_SOURCE_STATE));
                    assertEquals((i < 4 ? 2400 : 1200) + played, alGetSourcei(id, AL_SAMPLE_OFFSET));
                    assertEquals(4, alGetSourcei(id, AL_BUFFERS_QUEUED));
                }
            }
            List<GroupOffsetSync.Member> members = new ArrayList<>();
            for (int i = 0; i < groupSources.length; i++) members.add(new GroupOffsetSync.Member(groupSources[i], groupStreams.get(i)));
            for (int cycle = 0; cycle < 4; cycle++) {
                int position = 4200 + cycle * 700;
                for (int i = 0; i < groupSources.length; i++) {
                    alSourcei(groupSources[i], AL_SAMPLE_OFFSET, position - i * 110);
                }
                assertEquals("aligned in place", GroupOffsetSync.align(members).status());
                assertEquals("already aligned", GroupOffsetSync.align(members).status());
                alcRenderSamplesSOFT(device, samples, 120);
                for (var member : members) {
                    int id = member.source();
                    assertEquals((long) position + 120, member.stream().queueStartFrame(alGetSourcei(id, AL_BUFFERS_QUEUED))
                            + alGetSourcei(id, AL_SAMPLE_OFFSET));
                    assertEquals(4, alGetSourcei(id, AL_BUFFERS_QUEUED));
                    assertEquals(0.025f, alGetSourcef(id, AL_GAIN), 0.0001f);
                }
            }
            int[] stereoSources = {groupSources[0], groupSources[1]};
            int pauseFrame = alGetSourcei(stereoSources[0], AL_SAMPLE_OFFSET);
            int unaffected = alGetSourcei(groupSources[2], AL_SAMPLE_OFFSET);
            GroupChannelControl.setSourcesPaused(stereoSources, true);
            alcRenderSamplesSOFT(device, samples, 120);
            for (int id : stereoSources) {
                assertEquals(AL_PAUSED, alGetSourcei(id, AL_SOURCE_STATE));
                assertEquals(pauseFrame, alGetSourcei(id, AL_SAMPLE_OFFSET));
            }
            assertEquals(unaffected + 120, alGetSourcei(groupSources[2], AL_SAMPLE_OFFSET));
            GroupChannelControl.setSourcesPaused(stereoSources, false);
            alcRenderSamplesSOFT(device, samples, 120);
            for (int id : stereoSources) {
                assertEquals(AL_PLAYING, alGetSourcei(id, AL_SOURCE_STATE));
                assertEquals(pauseFrame + 120, alGetSourcei(id, AL_SAMPLE_OFFSET));
            }
            assertEquals(AL_NO_ERROR, alGetError());
            assertEquals(ALC_NO_ERROR, alcGetError(device));
        } finally {
            channels.forEach(Channel::destroy);
            if (ring != null) ring.closeAll();
            if (context != 0) {
                AL.setCurrentThread(null);
                AL.setCurrentProcess(null);
                alcMakeContextCurrent(0);
                alcDestroyContext(context);
            }
            alcCloseDevice(device);
        }
    }

    private static GroupMemberAudioStream startChannel(GroupRingBroadcastStream ring, List<Channel> channels,
                                                        Method create, Field source, float occlusion) throws Exception {
        Channel channel = (Channel) create.invoke(null);
        assertNotNull(channel);
        channels.add(channel);
        GroupMemberAudioStream member = ring.createMemberStream();
        member.setUnderwater(true);
        member.setOcclusion(occlusion);
        channel.disableAttenuation();
        channel.setVolume(0.1f);
        channel.attachBufferStream(member);
        assertEquals(4, alGetSourcei(source.getInt(channel), AL_BUFFERS_QUEUED));
        channel.play();
        return member;
    }

    private static boolean containsAudio(short[] samples) {
        for (short sample : samples) {
            if (Math.abs((int) sample) > 100) return true;
        }
        return false;
    }
}
