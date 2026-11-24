package dev.klxsh.speakersinheads.audio;

import dev.klxsh.speakersinheads.music.MusicLibrary;
import dev.klxsh.speakersinheads.music.MusicTrack;
import dev.klxsh.speakersinheads.music.PlaybackSettings;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.SelectedGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.AudioFileFormat;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class GroupClientSmoke implements FabricClientGameTest {
    private static final String TRACK_NAME = "test-48k-transients.wav";
    private static final BlockPos ANCHOR = new BlockPos(13, 100, 12);
    private static final Set<BlockPos> HEADS = Set.of(ANCHOR, new BlockPos(16,100,12),
            new BlockPos(13,100,16), new BlockPos(16,100,16));

    @Override
    public void runTest(ClientGameTestContext context) {
        createTrack();
        try (var world = context.worldBuilder().adjustSettings(settings -> {
            settings.setGameMode(SelectedGameMode.CREATIVE);
            settings.setAllowCommands(true);
        }).create()) {
            var server = world.getServer();
            server.runCommand("tp @a 15 100 15");
            server.runCommand("fill 10 99 10 20 99 20 minecraft:stone");
            server.runCommand("fill 10 100 10 20 104 20 minecraft:air");
            for (BlockPos head : HEADS) server.runCommand("setblock " + head.getX() + " 100 " + head.getZ() + " minecraft:player_head");
            context.waitFor(client -> client.level != null && HEADS.stream()
                    .allMatch(pos -> client.level.getBlockState(pos).is(Blocks.PLAYER_HEAD)), 2000);
            context.waitFor(client -> MusicLibrary.tracks().stream().anyMatch(track -> track.fileName().equals(TRACK_NAME)), 300);
            context.runOnClient(client -> {
                MusicTrack track = testTrack();
                SpeakerGroupManager.createGroup("Client game test", client.level.dimension().identifier(), HEADS);
                SpeakerAudioManager.play(track, ANCHOR, 0.10f, 1.0f);
            });
            context.waitFor(client -> liveChannels(client) == 4, 1000);
            System.out.println("SIH_SMOKE: all four group channels submitted PCM");

            context.runOnClient(client -> SpeakerAudioManager.pause(ANCHOR));
            double paused = context.computeOnClient(client -> SpeakerAudioManager.secondsPlayed(ANCHOR));
            context.waitTicks(6);
            context.runOnClient(client -> check(paused == SpeakerAudioManager.secondsPlayed(ANCHOR)
                    && liveChannels(client) == 4, "Pause should freeze the clock and retain all channels"));
            context.runOnClient(client -> SpeakerAudioManager.seek(ANCHOR, 4.0));
            context.runOnClient(client -> check("Paused".equals(SpeakerAudioManager.state(ANCHOR))
                    && SpeakerAudioManager.secondsPlayed(ANCHOR) == 4.0, "Seeking paused must preserve Pause"));
            context.runOnClient(client -> SpeakerAudioManager.resume(ANCHOR));
            context.waitFor(client -> liveChannels(client) == 4, 1000);
            context.runOnClient(client -> {
                SpeakerAudioManager.resyncGroup(ANCHOR);
            });
            context.waitFor(client -> liveChannels(client) == 4, 1000);
            System.out.println("SIH_SMOKE: Pause, seek while paused, Resume and Sync Now");

            server.runCommand("tp @a 400 100 400");
            server.runCommand("effect give @a minecraft:slow_falling 45 0 true");
            context.waitFor(client -> HEADS.stream().noneMatch(pos -> SpeakerSoundInstance.isClientChunkLoaded(client.level, pos)) && liveChannels(client) == 0, 2000);
            double away = context.computeOnClient(client -> SpeakerAudioManager.secondsPlayed(ANCHOR));
            long backAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            context.waitFor(client -> System.nanoTime() >= backAt, 3000);
            double returning = context.computeOnClient(client -> SpeakerAudioManager.secondsPlayed(ANCHOR));
            check(returning - away >= 24.5, "Group clock must advance while unloaded");
            server.runCommand("tp @a 15 100 15");
            context.waitFor(client -> liveChannels(client) == 4, 2000);
            double rejoined = context.computeOnClient(client -> SpeakerAudioManager.secondsPlayed(ANCHOR));
            check(rejoined >= returning, "Chunk rejoin must preserve playhead");
            System.out.println("SIH_SMOKE: all four chunks rejoined after 25 seconds at " + rejoined + " seconds");

            context.runOnClient(client -> {
                SpeakerAudioManager.stop(ANCHOR);
                SpeakerAudioManager.stop(ANCHOR);
                check(liveChannels(client) == 0 && "Stopped".equals(SpeakerAudioManager.state(ANCHOR)), "Stop must be idempotent");
            });
            context.runOnClient(client -> {
                PlaybackSettings.setRepeatMode(PlaybackSettings.RepeatMode.REPEAT_ONE);
                SpeakerAudioManager.play(testTrack(), ANCHOR, 0.10f, 1.0f, 74.8);
            });
            context.waitFor(client -> liveChannels(client) == 4
                    && SpeakerAudioManager.secondsPlayed(ANCHOR) < 4.0, 1000);
            System.out.println("SIH_SMOKE: Repeat One restarted the group after native EOF");
            context.runOnClient(client -> {
                PlaybackSettings.setRepeatMode(PlaybackSettings.RepeatMode.OFF);
                SpeakerAudioManager.stop(ANCHOR);
            });
            context.runOnClient(client -> SpeakerAudioManager.play(
                    testTrack(), ANCHOR, 0.10f, 1.0f));
            // Disconnect while opening to exercise cancellation on the actual Fabric client.
        }
        context.runOnClient(client -> check(SpeakerAudioManager.activePlayingPositions().isEmpty(), "Disconnect must clear sessions"));
        System.out.println("SIH_SMOKE: Stop and disconnect cleaned up sessions");
    }

    private static MusicTrack testTrack() {
        return MusicLibrary.tracks().stream().filter(track -> track.fileName().equals(TRACK_NAME))
                .findFirst().orElseThrow();
    }

    private static void createTrack() {
        int rate = 48_000;
        int seconds = 75;
        int frames = rate * seconds;
        AudioFormat format = new AudioFormat(rate, 16, 1, true, false);
        ByteBuffer pcm = ByteBuffer.allocate(frames * format.getFrameSize()).order(ByteOrder.LITTLE_ENDIAN);
        for (int frame = 0; frame < frames; frame++) {
            int phase = frame % rate;
            int frequency = 600 + frame / rate % 5 * 100;
            short sample = phase < rate / 10
                    ? (short) (11_000 * Math.sin(2 * Math.PI * frequency * phase / rate) * (1 - phase / (rate / 10.0)))
                    : 0;
            pcm.putShort(sample);
        }
        Path path = MusicLibrary.getMusicDirectory().resolve(TRACK_NAME);
        try (AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(pcm.array()), format, frames)) {
            Files.createDirectories(path.getParent());
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, path.toFile());
            MusicLibrary.refresh();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static int liveChannels(Minecraft client) {
        try {
            var registry = SpeakerAudioManager.class.getDeclaredField("SESSIONS");
            registry.setAccessible(true);
            var sessions = (Map<?, ?>) registry.get(null);
            for (Object session : sessions.values()) {
                if (!session.getClass().getSimpleName().equals("GroupAudioSession")) continue;
                var field = session.getClass().getDeclaredField("memberSounds");
                field.setAccessible(true);
                var sounds = (Map<?, ?>) field.get(session);
                return (int) sounds.values().stream().map(SpeakerSoundInstance.class::cast)
                        .filter(sound -> sound.hasSubmittedGroupAudio() && client.getSoundManager().isActive(sound)).count();
            }
            return 0;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
