package dev.klxsh.speakersinheads.audio;

import dev.klxsh.speakersinheads.music.AudioMetadataResolver;
import dev.klxsh.speakersinheads.music.MusicTrack;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns independent local playback sessions, keyed by dimension and speaker block position.
 * Supports single-point mono/stereo playback, virtual stereo pairs on one head,
 * and synchronized physical stereo pairs spanning two distinct world blocks.
 *
 * <p>Full chunk persistence: if a chunk unloads or the listener moves beyond audible range,
 * the session's virtual timeline advances smoothly. When the listener re-enters audible range,
 * the audio stream seamlessly rebinds at the exact current elapsed timestamp.
 */
public final class SpeakerAudioManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("SpeakersInHeads-AudioSession");
    private static final Map<SpeakerKey, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<SpeakerKey, Float> HEAD_VOLUMES = new ConcurrentHashMap<>();
    private static volatile float masterVolume = 1.0f;
    private static long tickCounter = 0;

    private SpeakerAudioManager() {}

    public static void initialize() {
        OutputBoost.initialize();
        SpeakerGroupManager.initialize();
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> stopAll());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> shutdown());
        ClientTickEvents.END_CLIENT_TICK.register(SpeakerAudioManager::onClientTick);
    }

    private static void onClientTick(Minecraft client) {
        if (client == null || client.level == null || client.player == null) return;
        tickCounter++;
        boolean groupTick = tickCounter % 2 == 0;
        boolean otherTick = tickCounter % 5 == 0;
        if (!groupTick && !otherTick) return;

        Identifier currentDim = client.level.dimension().identifier();
        Set<Session> processedSessions = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        for (Map.Entry<SpeakerKey, Session> entry : new java.util.ArrayList<>(SESSIONS.entrySet())) {
            SpeakerKey key = entry.getKey();
            Session session = entry.getValue();

            if (session instanceof GroupAudioSession ? !groupTick : !otherTick) continue;
            if (!processedSessions.add(session)) continue;
            if (!key.dimension().equals(currentDim.toString())) {
                if (session instanceof GroupAudioSession) {
                    session.stopAndRelease();
                    SESSIONS.values().removeIf(existing -> existing == session);
                }
                continue;
            }

            BlockPos pos = key.position();
            if (session instanceof GroupAudioSession groupSession) {
                pos = groupSession.update(client);
                if (pos == null) {
                    session.stopAndRelease();
                    SESSIONS.values().removeIf(existing -> existing == session);
                    continue;
                }
            }

            // 1. Check if track has finished naturally
            if (session.isFinished()) {
                MusicTrack finished = session.track();
                session.stopAndRelease();
                SESSIONS.values().removeIf(s -> s == session);

                // Auto-advance to next track respecting Repeat / Shuffle settings
                List<MusicTrack> libraryTracks = dev.klxsh.speakersinheads.music.MusicLibrary.tracks();
                MusicTrack next = dev.klxsh.speakersinheads.music.PlaybackSettings.getNextTrack(finished, libraryTracks, false);
                if (next != null && next.available()) {
                    play(next, pos, masterVolume, headVolume(pos));
                }
                continue;
            }

            // 2. Physical destruction vs chunk unloading check
            if (session instanceof GroupAudioSession) continue;
            if (session instanceof PhysicalStereoSession pss) {
                // For a physical stereo pair: check both heads
                BlockPos leftPos = pss.pair().leftPos();
                BlockPos rightPos = pss.pair().rightPos();
                boolean leftPresent = SpeakerSoundInstance.isClientChunkLoaded(client.level, leftPos) && SpeakerSoundInstance.isSupportedHead(client.level.getBlockState(leftPos));
                boolean rightPresent = SpeakerSoundInstance.isClientChunkLoaded(client.level, rightPos) && SpeakerSoundInstance.isSupportedHead(client.level.getBlockState(rightPos));
                if (leftPresent || rightPresent) {
                    if (pss.needsRebind(client)) {
                        pss.rebind(client);
                    }
                }
            } else {
                // Single sound or virtual stereo
                if (SpeakerSoundInstance.isClientChunkLoaded(client.level, pos)) {
                    if (!SpeakerSoundInstance.isSupportedHead(client.level.getBlockState(pos))) {
                        session.stopAndRelease();
                        SESSIONS.remove(key);
                        continue;
                    }

                    double distSq = pos.distToCenterSqr(client.player.getX(), client.player.getY(), client.player.getZ());
                    SpatialSettings spatial = SpatialSettings.get();
                    float maxDist = spatial.maxDistance();
                    if (!spatial.isEnabled() || distSq <= maxDist * maxDist) {
                        if (session.needsRebind(client)) {
                            session.rebind(client);
                        }
                    }
                }
            }
        }
    }

    public static void play(MusicTrack track, BlockPos position, float masterVolume, float headVolume) {
        play(track, position, masterVolume, headVolume, 0.0);
    }

    public static void play(MusicTrack track, BlockPos position, float masterVolume, float headVolume, double startOffsetSeconds) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) throw new IllegalStateException("A world is required to play music at a head");

        Identifier dimension = client.level.dimension().identifier();
        SpeakerAudioManager.masterVolume = clamp(masterVolume);
        double offset = Math.max(0.0, startOffsetSeconds);

        // ── 1. Check for Speaker Group (arbitrary synchronized group of heads) ──
        Optional<SpeakerGroup> groupOpt = SpeakerGroupManager.findGroupFor(dimension, position);
        if (groupOpt.isPresent()) {
            SpeakerGroup group = groupOpt.get();
            for (BlockPos memberPos : group.members()) {
                SpeakerKey memberKey = new SpeakerKey(dimension.toString(), memberPos.immutable());
                Session prev = SESSIONS.remove(memberKey);
                if (prev != null) prev.stopAndRelease();
            }

            GroupAudioSession groupSession = new GroupAudioSession(group, track, dimension, offset);
            for (BlockPos memberPos : group.members()) {
                SpeakerKey memberKey = new SpeakerKey(dimension.toString(), memberPos.immutable());
                SESSIONS.put(memberKey, groupSession);
            }

            groupSession.start(client);
            return;
        }

        // ── 2. Check for Physical Stereo Pair (two world blocks) ───────
        Optional<PhysicalStereoPair> pairOpt = StereoPairManager.findPairFor(dimension, position);
        if (pairOpt.isPresent()) {
            PhysicalStereoPair pair = pairOpt.get();
            BlockPos leftPos = pair.isSwapped() ? pair.rightPos() : pair.leftPos();
            BlockPos rightPos = pair.isSwapped() ? pair.leftPos() : pair.rightPos();

            SpeakerKey leftKey = new SpeakerKey(dimension.toString(), leftPos.immutable());
            SpeakerKey rightKey = new SpeakerKey(dimension.toString(), rightPos.immutable());

            Session prevLeft = SESSIONS.remove(leftKey);
            if (prevLeft != null) prevLeft.stopAndRelease();
            Session prevRight = SESSIONS.remove(rightKey);
            if (prevRight != null && prevRight != prevLeft) prevRight.stopAndRelease();

            PhysicalStereoSession pairSession = new PhysicalStereoSession(pair, track, dimension, offset);
            SESSIONS.put(leftKey, pairSession);
            SESSIONS.put(rightKey, pairSession);

            pairSession.start(client);
            return;
        }

        // ── 3. Single Block: Virtual Stereo or Single Point Source ─────
        SpeakerKey key = new SpeakerKey(dimension.toString(), position.immutable());
        HEAD_VOLUMES.put(key, clamp(headVolume));

        Session previous = SESSIONS.remove(key);
        if (previous != null) previous.stopAndRelease();

        SpatialSettings.SpatialMode mode = SpatialSettings.get().mode();
        Session session;
        if (mode == SpatialSettings.SpatialMode.VIRTUAL_STEREO) {
            session = new VirtualStereoSession(track, position, dimension, offset, masterVolume * headVolume);
        } else {
            session = new SingleSoundSession(track, position, dimension, offset, masterVolume * headVolume);
        }

        SESSIONS.put(key, session);
        session.start(client);
    }

    public static void pause(BlockPos position) {
        session(position).ifPresent(s -> s.setPaused(true));
    }

    public static void resume(BlockPos position) {
        session(position).ifPresent(s -> s.setPaused(false));
    }

    public static void stop(BlockPos position) {
        Minecraft client = Minecraft.getInstance();
        Identifier dim = client.level != null ? client.level.dimension().identifier() : null;
        if (dim != null) {
            Optional<SpeakerGroup> groupOpt = SpeakerGroupManager.findGroupFor(dim, position);
            if (groupOpt.isPresent()) {
                SpeakerGroup group = groupOpt.get();
                Set<Session> sessions = new HashSet<>();
                for (BlockPos member : group.members()) {
                    Session existing = SESSIONS.get(new SpeakerKey(dim.toString(), member));
                    if (existing != null) sessions.add(existing);
                }
                for (Session existing : sessions) {
                    existing.stopAndRelease();
                    SESSIONS.values().removeIf(session -> session == existing);
                }
                return;
            }

            Optional<PhysicalStereoPair> pairOpt = StereoPairManager.findPairFor(dim, position);
            if (pairOpt.isPresent()) {
                PhysicalStereoPair pair = pairOpt.get();
                Session s1 = SESSIONS.remove(new SpeakerKey(dim.toString(), pair.leftPos().immutable()));
                Session s2 = SESSIONS.remove(new SpeakerKey(dim.toString(), pair.rightPos().immutable()));
                if (s1 != null) s1.stopAndRelease();
                if (s2 != null && s2 != s1) s2.stopAndRelease();
                return;
            }
        }
        Session current = SESSIONS.remove(key(position));
        if (current != null) current.stopAndRelease();
    }

    public static void setGain(BlockPos position, float masterVolume, float headVolume) {
        SpeakerKey key = key(position);
        float head = clamp(headVolume);
        HEAD_VOLUMES.put(key, head);
        setMasterVolume(masterVolume);
    }

    public static void setMasterVolume(float masterVolume) {
        SpeakerAudioManager.masterVolume = clamp(masterVolume);
        Set<Session> updated = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        SESSIONS.forEach((speaker, session) -> {
            if (!updated.add(session)) return;
            if (session instanceof GroupAudioSession gas) {
                gas.setGain(SpeakerAudioManager.masterVolume * gas.group.volume());
            } else if (session instanceof PhysicalStereoSession pss) {
                PhysicalStereoPair pair = pss.pair();
                float leftGain = SpeakerAudioManager.masterVolume * pair.pairMasterVolume() * pair.leftVolume();
                float rightGain = SpeakerAudioManager.masterVolume * pair.pairMasterVolume() * pair.rightVolume();
                pss.setPairGains(leftGain, rightGain);
            } else {
                session.setGain(SpeakerAudioManager.masterVolume * HEAD_VOLUMES.getOrDefault(speaker, 1.0f));
            }
        });
    }

    public static Optional<SpeakerGroup> getSpeakerGroup(BlockPos position) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return Optional.empty();
        return SpeakerGroupManager.findGroupFor(client.level.dimension().identifier(), position);
    }

    public static void setGroupVolume(BlockPos position, float volume) {
        getSpeakerGroup(position).ifPresent(group -> {
            group.setVolume(volume);
            session(position).ifPresent(active -> {
                if (active instanceof GroupAudioSession grouped && grouped.group.id().equals(group.id())) {
                    grouped.setGain(masterVolume * group.volume());
                }
            });
        });
    }

    public static void setPairVolume(BlockPos position, float volume) {
        getStereoPair(position).ifPresent(pair -> {
            pair.setPairMasterVolume(volume);
            session(position).ifPresent(active -> {
                if (active instanceof PhysicalStereoSession stereo && stereo.pair() == pair) {
                    stereo.setGain(masterVolume);
                }
            });
        });
    }

    public static void removeSpeakerFromGroup(BlockPos position) {
        stop(position);
        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            SpeakerGroupManager.removeFromAnyGroup(client.level.dimension().identifier(), position);
        }
    }

    public static Optional<PhysicalStereoPair> getStereoPair(BlockPos position) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return Optional.empty();
        return StereoPairManager.findPairFor(client.level.dimension().identifier(), position);
    }

    public static void swapStereoPair(BlockPos position) {
        getStereoPair(position).ifPresent(pair -> {
            pair.swap();
            StereoPairManager.save();
            MusicTrack activeTrack = currentTrack(position);
            if (activeTrack != null && !"Stopped".equals(state(position))) {
                play(activeTrack, position, masterVolume, headVolume(position));
            }
        });
    }

    public static void unlinkStereoPair(BlockPos position) {
        stop(position);
        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            StereoPairManager.unlink(client.level.dimension().identifier(), position);
        }
    }

    public static float masterVolume() {
        return masterVolume;
    }

    public static float headVolume(BlockPos position) {
        return HEAD_VOLUMES.getOrDefault(key(position), 1.0f);
    }

    public static MusicTrack currentTrack(BlockPos position) {
        return session(position).map(Session::track).orElse(null);
    }

    public static String state(BlockPos position) {
        return session(position).map(Session::playbackState).orElse("Stopped");
    }

    public static String error(BlockPos position) {
        return session(position).map(Session::error).orElse(null);
    }

    public static double secondsPlayed(BlockPos position) {
        return session(position).map(Session::secondsPlayed).orElse(0.0);
    }

    public static double totalDurationSeconds(BlockPos position) {
        return session(position).map(Session::durationSeconds).orElse(0.0);
    }

    public static void seek(BlockPos position, double targetSeconds) {
        session(position).ifPresent(s -> {
            MusicTrack active = s.track();
            if (active == null) return;
            boolean wasPaused = "Paused".equals(s.playbackState());
            play(active, position, masterVolume, headVolume(position), targetSeconds);
            if (wasPaused) {
                pause(position);
            }
        });
    }

    public static boolean isPlaying(BlockPos position) {
        return session(position).map(s -> "Playing".equals(s.playbackState()) || "Playing (Unloaded)".equals(s.playbackState())).orElse(false);
    }

    public static void resyncGroup(BlockPos position) {
        session(position).ifPresent(s -> {
            if (s instanceof GroupAudioSession gas) {
                gas.resyncAll(Minecraft.getInstance());
            }
        });
    }

    public static Set<BlockPos> activePlayingPositions() {
        Set<BlockPos> positions = new HashSet<>();
        SESSIONS.forEach((key, session) -> {
            String state = session.playbackState();
            if ("Playing".equals(state) || "Playing (Unloaded)".equals(state)) {
                positions.add(key.position());
            }
        });
        return positions;
    }

    public static void stopAll() {
        SESSIONS.values().forEach(Session::stopAndRelease);
        SESSIONS.clear();
    }

    static void onSoundInstanceStopped(SpeakerSoundInstance sound) {
        // Notification from sound instance: do not immediately kill session if still in progress
        for (Session session : SESSIONS.values()) {
            if (session.containsSound(sound)) {
                session.onSoundStopped(sound);
            }
        }
    }

    public static void shutdown() {
        stopAll();
        AudioStreamFactory.shutdown();
    }

    private static Optional<Session> session(BlockPos position) {
        return Optional.ofNullable(SESSIONS.get(key(position)));
    }

    private static SpeakerKey key(BlockPos position) {
        Minecraft client = null;
        try {
            client = Minecraft.getInstance();
        } catch (Throwable ignored) {}
        String dimension = (client == null || client.level == null) ? "none" : client.level.dimension().identifier().toString();
        return new SpeakerKey(dimension, position.immutable());
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private record SpeakerKey(String dimension, BlockPos position) {}

    // ─────────────────────────────────────────────────────────────────────────
    // Session Interface & Implementations
    // ─────────────────────────────────────────────────────────────────────────

    private interface Session {
        MusicTrack track();
        void start(Minecraft client);
        void setPaused(boolean value);
        void setGain(float value);
        void stopAndRelease();
        String playbackState();
        String error();
        double secondsPlayed();
        double durationSeconds();
        boolean isFinished();
        boolean needsRebind(Minecraft client);
        void rebind(Minecraft client);
        void onSoundStopped(SpeakerSoundInstance sound);
        boolean containsSound(SpeakerSoundInstance sound);
    }

    private static final class SingleSoundSession implements Session {
        private final MusicTrack track;
        private final BlockPos position;
        private final Identifier dimension;
        private final double durationSeconds;
        private volatile float gain;
        private volatile boolean paused = false;
        private volatile boolean stopped = false;
        private volatile long startNanos;
        private volatile long pauseStartNanos = 0;
        private volatile long totalPausedNanos = 0;
        private volatile long lastRebindNanos = 0;
        private volatile double baseOffsetSeconds;
        private volatile SpeakerSoundInstance sound;

        SingleSoundSession(MusicTrack track, BlockPos position, Identifier dimension, double offset, float gain) {
            this.track = track;
            this.position = position.immutable();
            this.dimension = dimension;
            this.baseOffsetSeconds = offset;
            this.gain = gain;
            this.durationSeconds = AudioMetadataResolver.getDurationSeconds(track.path());
            this.startNanos = System.nanoTime();
        }

        @Override public MusicTrack track() { return track; }
        @Override public double durationSeconds() { return durationSeconds; }

        @Override
        public void start(Minecraft client) {
            rebind(client);
        }

        @Override
        public void setPaused(boolean value) {
            if (this.paused == value) return;
            this.paused = value;
            if (value) {
                pauseStartNanos = System.nanoTime();
            } else {
                if (pauseStartNanos > 0) {
                    totalPausedNanos += (System.nanoTime() - pauseStartNanos);
                    pauseStartNanos = 0;
                }
            }
            if (sound != null) sound.setPaused(value);
        }

        @Override
        public void setGain(float value) {
            this.gain = value;
            if (sound != null) sound.setGain(value);
        }

        @Override
        public void stopAndRelease() {
            stopped = true;
            if (sound != null) {
                sound.stopAndRelease();
                sound = null;
            }
        }

        @Override
        public String playbackState() {
            if (stopped) return "Stopped";
            if (sound != null) return sound.playbackState();
            if (paused) return "Paused";
            if (isFinished()) return "Finished";
            return "Playing (Unloaded)";
        }

        @Override
        public String error() {
            return sound != null ? sound.error() : null;
        }

        @Override
        public double secondsPlayed() {
            long currentPaused = (paused && pauseStartNanos > 0) ? (System.nanoTime() - pauseStartNanos) : 0;
            double elapsed = baseOffsetSeconds + ((System.nanoTime() - startNanos - totalPausedNanos - currentPaused) / 1_000_000_000.0);
            return durationSeconds > 0 ? Math.min(durationSeconds, Math.max(0.0, elapsed)) : Math.max(0.0, elapsed);
        }

        @Override
        public boolean isFinished() {
            return durationSeconds > 0 && secondsPlayed() >= durationSeconds;
        }

        @Override
        public boolean needsRebind(Minecraft client) {
            if (stopped || paused || isFinished()) return false;
            long now = System.nanoTime();
            if (now - lastRebindNanos < 3_000_000_000L) return false;
            return sound == null || sound.isStopped() || !client.getSoundManager().isActive(sound);
        }

        @Override
        public void rebind(Minecraft client) {
            if (stopped || isFinished()) return;
            lastRebindNanos = System.nanoTime();
            double currentOffset = secondsPlayed();
            if (sound != null) {
                sound.stopAndRelease();
            }
            LOGGER.info("[AudioSession] Rebinding single sound at {} to offset {:.2f}s", position.toShortString(), currentOffset);
            sound = new SpeakerSoundInstance(track.path(), position,
                    new Vec3(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5),
                    dimension,
                    () -> AudioStreamFactory.openPrebuffered(track.path(), SpatialSettings.get().mode(), currentOffset));
            sound.setGain(gain);
            sound.setPaused(paused);
            client.getSoundManager().play(sound);
        }

        @Override
        public void onSoundStopped(SpeakerSoundInstance sound) {
            if (this.sound == sound) {
                // Sound stream stopped in OpenAL; timeline continues in virtual mode
                this.sound = null;
            }
        }

        @Override
        public boolean containsSound(SpeakerSoundInstance other) {
            return sound == other;
        }
    }

    private static final class VirtualStereoSession implements Session {
        private final MusicTrack track;
        private final BlockPos position;
        private final Identifier dimension;
        private final double durationSeconds;
        private volatile float gain;
        private volatile boolean paused = false;
        private volatile boolean stopped = false;
        private volatile long startNanos;
        private volatile long pauseStartNanos = 0;
        private volatile long totalPausedNanos = 0;
        private volatile long lastRebindNanos = 0;
        private volatile double baseOffsetSeconds;
        private volatile SpeakerSoundInstance leftSound;
        private volatile SpeakerSoundInstance rightSound;

        VirtualStereoSession(MusicTrack track, BlockPos position, Identifier dimension, double offset, float gain) {
            this.track = track;
            this.position = position.immutable();
            this.dimension = dimension;
            this.baseOffsetSeconds = offset;
            this.gain = gain;
            this.durationSeconds = AudioMetadataResolver.getDurationSeconds(track.path());
            this.startNanos = System.nanoTime();
        }

        @Override public MusicTrack track() { return track; }
        @Override public double durationSeconds() { return durationSeconds; }

        @Override
        public void start(Minecraft client) {
            rebind(client);
        }

        @Override
        public void setPaused(boolean value) {
            if (this.paused == value) return;
            this.paused = value;
            if (value) {
                pauseStartNanos = System.nanoTime();
            } else {
                if (pauseStartNanos > 0) {
                    totalPausedNanos += (System.nanoTime() - pauseStartNanos);
                    pauseStartNanos = 0;
                }
            }
            if (leftSound != null) leftSound.setPaused(value);
            if (rightSound != null) rightSound.setPaused(value);
        }

        @Override
        public void setGain(float value) {
            this.gain = value;
            if (leftSound != null) leftSound.setGain(value);
            if (rightSound != null) rightSound.setGain(value);
        }

        @Override
        public void stopAndRelease() {
            stopped = true;
            if (leftSound != null) { leftSound.stopAndRelease(); leftSound = null; }
            if (rightSound != null) { rightSound.stopAndRelease(); rightSound = null; }
        }

        @Override
        public String playbackState() {
            if (stopped) return "Stopped";
            if (leftSound != null) return leftSound.playbackState();
            if (paused) return "Paused";
            if (isFinished()) return "Finished";
            return "Playing (Unloaded)";
        }

        @Override
        public String error() {
            String err = leftSound != null ? leftSound.error() : null;
            return err != null ? err : (rightSound != null ? rightSound.error() : null);
        }

        @Override
        public double secondsPlayed() {
            long currentPaused = (paused && pauseStartNanos > 0) ? (System.nanoTime() - pauseStartNanos) : 0;
            double elapsed = baseOffsetSeconds + ((System.nanoTime() - startNanos - totalPausedNanos - currentPaused) / 1_000_000_000.0);
            return durationSeconds > 0 ? Math.min(durationSeconds, Math.max(0.0, elapsed)) : Math.max(0.0, elapsed);
        }

        @Override
        public boolean isFinished() {
            return durationSeconds > 0 && secondsPlayed() >= durationSeconds;
        }

        @Override
        public boolean needsRebind(Minecraft client) {
            if (stopped || paused || isFinished()) return false;
            long now = System.nanoTime();
            if (now - lastRebindNanos < 3_000_000_000L) return false;
            boolean leftNeeds = leftSound == null || leftSound.isStopped() || !client.getSoundManager().isActive(leftSound);
            boolean rightNeeds = rightSound == null || rightSound.isStopped() || !client.getSoundManager().isActive(rightSound);
            return leftNeeds || rightNeeds;
        }

        @Override
        public void rebind(Minecraft client) {
            if (stopped || isFinished()) return;
            lastRebindNanos = System.nanoTime();
            double currentOffset = secondsPlayed();
            if (leftSound != null) leftSound.stopAndRelease();
            if (rightSound != null) rightSound.stopAndRelease();

            LOGGER.info("[AudioSession] Rebinding virtual stereo at {} to offset {:.2f}s", position.toShortString(), currentOffset);
            SpeakerGeometry.StereoPositions pos = SpeakerGeometry.calculateStereoPositions(
                    client.level, position, SpeakerGeometry.DEFAULT_STEREO_OFFSET);

            CompletableFuture<AudioStreamFactory.VirtualStereoStreams> streamsFuture =
                    AudioStreamFactory.openVirtualStereoPrebuffered(track.path(), currentOffset);

            leftSound = new SpeakerSoundInstance(track.path(), position, pos.left(), dimension,
                    () -> streamsFuture.thenApply(AudioStreamFactory.VirtualStereoStreams::left));
            rightSound = new SpeakerSoundInstance(track.path(), position, pos.right(), dimension,
                    () -> streamsFuture.thenApply(AudioStreamFactory.VirtualStereoStreams::right));

            leftSound.setGain(gain);
            rightSound.setGain(gain);
            leftSound.setPaused(paused);
            rightSound.setPaused(paused);

            client.getSoundManager().play(leftSound);
            client.getSoundManager().play(rightSound);
        }

        @Override
        public void onSoundStopped(SpeakerSoundInstance sound) {
            if (leftSound == sound) leftSound = null;
            if (rightSound == sound) rightSound = null;
        }

        @Override
        public boolean containsSound(SpeakerSoundInstance other) {
            return leftSound == other || rightSound == other;
        }
    }

    private static final class PhysicalStereoSession implements Session {
        private final PhysicalStereoPair pair;
        private final MusicTrack track;
        private final Identifier dimension;
        private final double durationSeconds;
        private volatile boolean paused = false;
        private volatile boolean stopped = false;
        private volatile long startNanos;
        private volatile long pauseStartNanos = 0;
        private volatile long totalPausedNanos = 0;
        private volatile long lastRebindNanos = 0;
        private volatile double baseOffsetSeconds;
        private volatile SpeakerSoundInstance leftSound;
        private volatile SpeakerSoundInstance rightSound;

        PhysicalStereoSession(PhysicalStereoPair pair, MusicTrack track, Identifier dimension, double offset) {
            this.pair = pair;
            this.track = track;
            this.dimension = dimension;
            this.baseOffsetSeconds = offset;
            this.durationSeconds = AudioMetadataResolver.getDurationSeconds(track.path());
            this.startNanos = System.nanoTime();
        }

        public PhysicalStereoPair pair() { return pair; }
        @Override public MusicTrack track() { return track; }
        @Override public double durationSeconds() { return durationSeconds; }

        public void setPairGains(float leftGain, float rightGain) {
            if (leftSound != null) leftSound.setGain(leftGain);
            if (rightSound != null) rightSound.setGain(rightGain);
        }

        @Override
        public void start(Minecraft client) {
            rebind(client);
        }

        @Override
        public void setPaused(boolean value) {
            if (this.paused == value) return;
            this.paused = value;
            if (value) {
                pauseStartNanos = System.nanoTime();
            } else {
                if (pauseStartNanos > 0) {
                    totalPausedNanos += (System.nanoTime() - pauseStartNanos);
                    pauseStartNanos = 0;
                }
            }
            var sounds = new java.util.ArrayList<SpeakerSoundInstance>(2);
            if (leftSound != null) sounds.add(leftSound);
            if (rightSound != null) sounds.add(rightSound);
            GroupChannelControl.setPaused(Minecraft.getInstance(), sounds, value);
        }

        @Override
        public void setGain(float value) {
            float leftGain = SpeakerAudioManager.masterVolume * pair.pairMasterVolume() * pair.leftVolume();
            float rightGain = SpeakerAudioManager.masterVolume * pair.pairMasterVolume() * pair.rightVolume();
            setPairGains(leftGain, rightGain);
        }

        @Override
        public void stopAndRelease() {
            stopped = true;
            if (leftSound != null) { leftSound.stopAndRelease(); leftSound = null; }
            if (rightSound != null) { rightSound.stopAndRelease(); rightSound = null; }
        }

        @Override
        public String playbackState() {
            if (stopped) return "Stopped";
            if (paused) return "Paused";
            if (leftSound != null) return leftSound.playbackState();
            if (rightSound != null) return rightSound.playbackState();
            if (isFinished()) return "Finished";
            return "Playing (Unloaded)";
        }

        @Override
        public String error() {
            String err = leftSound != null ? leftSound.error() : null;
            return err != null ? err : (rightSound != null ? rightSound.error() : null);
        }

        @Override
        public double secondsPlayed() {
            long currentPaused = (paused && pauseStartNanos > 0) ? (System.nanoTime() - pauseStartNanos) : 0;
            double elapsed = baseOffsetSeconds + ((System.nanoTime() - startNanos - totalPausedNanos - currentPaused) / 1_000_000_000.0);
            return durationSeconds > 0 ? Math.min(durationSeconds, Math.max(0.0, elapsed)) : Math.max(0.0, elapsed);
        }

        @Override
        public boolean isFinished() {
            return durationSeconds > 0 && secondsPlayed() >= durationSeconds;
        }

        @Override
        public boolean needsRebind(Minecraft client) {
            if (stopped || paused || isFinished() || client.level == null) return false;
            long now = System.nanoTime();
            if (now - lastRebindNanos < 3_000_000_000L) return false;

            BlockPos leftPos = pair.isSwapped() ? pair.rightPos() : pair.leftPos();
            BlockPos rightPos = pair.isSwapped() ? pair.leftPos() : pair.rightPos();

            boolean leftLoaded = SpeakerSoundInstance.isClientChunkLoaded(client.level, leftPos) && SpeakerSoundInstance.isSupportedHead(client.level.getBlockState(leftPos));
            boolean rightLoaded = SpeakerSoundInstance.isClientChunkLoaded(client.level, rightPos) && SpeakerSoundInstance.isSupportedHead(client.level.getBlockState(rightPos));

            boolean leftNeeds = leftLoaded && (leftSound == null || leftSound.isStopped() || !client.getSoundManager().isActive(leftSound));
            boolean rightNeeds = rightLoaded && (rightSound == null || rightSound.isStopped() || !client.getSoundManager().isActive(rightSound));

            return leftNeeds || rightNeeds;
        }

        @Override
        public void rebind(Minecraft client) {
            if (stopped || isFinished() || client.level == null) return;
            lastRebindNanos = System.nanoTime();
            double currentOffset = secondsPlayed();

            BlockPos leftPos = pair.isSwapped() ? pair.rightPos() : pair.leftPos();
            BlockPos rightPos = pair.isSwapped() ? pair.leftPos() : pair.rightPos();

            boolean leftLoaded = SpeakerSoundInstance.isClientChunkLoaded(client.level, leftPos) && SpeakerSoundInstance.isSupportedHead(client.level.getBlockState(leftPos));
            boolean rightLoaded = SpeakerSoundInstance.isClientChunkLoaded(client.level, rightPos) && SpeakerSoundInstance.isSupportedHead(client.level.getBlockState(rightPos));

            if (!leftLoaded && !rightLoaded) {
                // Both chunks unloaded: virtual timeline continues ticking
                return;
            }

            LOGGER.info("[ChunkPersistence] Rebinding Physical Stereo Pair at {} ms (leftLoaded={}, rightLoaded={})",
                    Math.round(currentOffset * 1000), leftLoaded, rightLoaded);

            if (leftSound != null) {
                leftSound.stopAndRelease();
                leftSound = null;
            }
            if (rightSound != null) {
                rightSound.stopAndRelease();
                rightSound = null;
            }

            CompletableFuture<AudioStreamFactory.VirtualStereoStreams> streamsFuture =
                    AudioStreamFactory.openPhysicalStereoPairPrebuffered(track.path(), currentOffset);

            float leftGain = SpeakerAudioManager.masterVolume * pair.pairMasterVolume() * pair.leftVolume();
            float rightGain = SpeakerAudioManager.masterVolume * pair.pairMasterVolume() * pair.rightVolume();

            if (leftLoaded) {
                leftSound = new SpeakerSoundInstance(track.path(), leftPos,
                        new Vec3(leftPos.getX() + 0.5, leftPos.getY() + 0.5, leftPos.getZ() + 0.5),
                        dimension,
                        () -> streamsFuture.thenApply(AudioStreamFactory.VirtualStereoStreams::left));
                leftSound.setGain(leftGain);
                if (client.getSoundManager().play(leftSound) == net.minecraft.client.sounds.SoundEngine.PlayResult.NOT_STARTED) {
                    leftSound = null;
                }
            }
            if (leftSound == null) streamsFuture.thenAccept(streams -> closeUnused(streams.left()));

            if (rightLoaded) {
                rightSound = new SpeakerSoundInstance(track.path(), rightPos,
                        new Vec3(rightPos.getX() + 0.5, rightPos.getY() + 0.5, rightPos.getZ() + 0.5),
                        dimension,
                        () -> streamsFuture.thenApply(AudioStreamFactory.VirtualStereoStreams::right));
                rightSound.setGain(rightGain);
                if (client.getSoundManager().play(rightSound) == net.minecraft.client.sounds.SoundEngine.PlayResult.NOT_STARTED) {
                    rightSound = null;
                }
            }
            if (rightSound == null) streamsFuture.thenAccept(streams -> closeUnused(streams.right()));
        }

        private static void closeUnused(PcmQueueAudioStream stream) {
            try { stream.close(); }
            catch (java.io.IOException failure) { LOGGER.debug("Could not close an unused stereo stream", failure); }
        }
        @Override
        public void onSoundStopped(SpeakerSoundInstance sound) {
            if (leftSound == sound) leftSound = null;
            if (rightSound == sound) rightSound = null;
        }

        @Override
        public boolean containsSound(SpeakerSoundInstance other) {
            return leftSound == other || rightSound == other;
        }
    }

    private static final class GroupAudioSession implements Session {
        private final SpeakerGroup group;
        private final MusicTrack track;
        private final Identifier dimension;
        private final Map<BlockPos, SpeakerSoundInstance> memberSounds = new java.util.HashMap<>();
        private volatile PlaybackClock clock;
        private volatile double durationSeconds;
        private boolean paused;
        private boolean playbackStarted;
        private boolean stopped;
        private String error;
        private long nextChannelRetryNanos;
        private long nextManualSyncNanos;
        private CompletableFuture<GroupRingBroadcastStream> opening;
        private GroupRingBroadcastStream broadcast;

        GroupAudioSession(SpeakerGroup group, MusicTrack track, Identifier dimension, double offset) {
            this.group = group;
            this.track = track;
            this.dimension = dimension;
            this.clock = new PlaybackClock(offset, 0);
        }

        @Override public MusicTrack track() { return track; }
        @Override public double durationSeconds() { return durationSeconds; }

        @Override
        public void start(Minecraft client) {
            try {
                AudioDecoderExecutor.supply(() -> AudioMetadataResolver.getDurationSeconds(track.path()))
                        .thenAccept(duration -> durationSeconds = duration);
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                LOGGER.debug("Group {} duration lookup deferred until EOF", group.id());
            }
            rebind(client);
        }

        @Override
        public void setPaused(boolean value) {
            if (stopped || paused == value) return;
            paused = value;
            if (value) {
                clock = new PlaybackClock(clock.seconds(), 0);
                GroupChannelControl.setPaused(Minecraft.getInstance(), memberSounds.values(), true);
            } else {
                if (playbackStarted) clock = new PlaybackClock(clock.seconds(), System.nanoTime());
                GroupChannelControl.setPaused(Minecraft.getInstance(), memberSounds.values(), false);
                rebind(Minecraft.getInstance());
            }
        }

        @Override
        public void setGain(float value) {
            float groupGain = masterVolume * group.volume();
            memberSounds.values().forEach(sound -> sound.setGain(groupGain));
        }

        @Override
        public void stopAndRelease() {
            if (stopped) return;
            stopped = true;
            releaseSounds();
            releaseDecoder();
        }

        private void releaseSounds() {
            memberSounds.values().forEach(SpeakerSoundInstance::stopAndRelease);
            memberSounds.clear();
        }

        private void releaseDecoder() {
            if (opening != null) {
                // A decoder that completes during Stop or disconnect still belongs to this session.
                opening.thenAccept(GroupRingBroadcastStream::closeAll);
                opening = null;
            }
            broadcast = null;
        }

        @Override
        public String playbackState() {
            if (stopped) return "Stopped";
            if (error != null) return "Error: " + error;
            if (paused) return "Paused";
            if (isFinished()) return "Finished";
            for (SpeakerSoundInstance sound : memberSounds.values()) {
                if (sound.hasSubmittedGroupAudio()) return "Playing";
            }
            if (nextChannelRetryNanos > System.nanoTime()) return "Waiting for audio channel";
            if (clock.startedNanos() == 0 || !memberSounds.isEmpty()) return "Loading";
            return "Playing (Unloaded)";
        }

        @Override public String error() { return error; }

        @Override
        public double secondsPlayed() {
            double seconds = clock.seconds();
            return durationSeconds > 0 ? Math.min(seconds, durationSeconds) : seconds;
        }

        @Override
        public boolean isFinished() {
            return error == null && !paused && hasReachedEof() && memberSounds.isEmpty();
        }

        private boolean hasReachedEof() {
            return broadcast != null && broadcast.isEof()
                    && broadcast.getMasterPlaybackFrame() >= broadcast.getTotalFrames();
        }

        private boolean eligible(Minecraft client, BlockPos pos) {
            if (!SpeakerSoundInstance.isClientChunkLoaded(client.level, pos)
                    || !SpeakerSoundInstance.isSupportedHead(client.level.getBlockState(pos))) return false;
            SpatialSettings spatial = SpatialSettings.get();
            if (client.player == null || !spatial.isEnabled()) return true;
            double maxDistance = spatial.maxDistance();
            return client.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= maxDistance * maxDistance;
        }

        BlockPos update(Minecraft client) {
            Set<BlockPos> members = group.members();
            BlockPos anchor = null;
            for (BlockPos pos : members) {
                SpeakerKey key = new SpeakerKey(dimension.toString(), pos);
                Session owner = SESSIONS.get(key);
                if (owner == null || owner == this) {
                    SESSIONS.put(key, this);
                    if (anchor == null || eligible(client, pos)) anchor = pos;
                }
            }
            SESSIONS.entrySet().removeIf(entry -> entry.getValue() == this && !members.contains(entry.getKey().position()));
            if (anchor == null) return null;

            Iterator<Map.Entry<BlockPos, SpeakerSoundInstance>> sounds = memberSounds.entrySet().iterator();
            while (sounds.hasNext()) {
                Map.Entry<BlockPos, SpeakerSoundInstance> entry = sounds.next();
                SpeakerSoundInstance sound = entry.getValue();
                SpeakerKey key = new SpeakerKey(dimension.toString(), entry.getKey());
                if (SESSIONS.get(key) != this || !eligible(client, entry.getKey())
                        || sound.isStopped() || !client.getSoundManager().isActive(sound)) {
                    sound.stopAndRelease();
                    sounds.remove();
                }
            }
            rebind(client);
            return anchor;
        }

        void resyncAll(Minecraft client) {
            if (stopped || paused || error != null || client.level == null) return;
            long now = System.nanoTime();
            if (now < nextManualSyncNanos) return;
            nextManualSyncNanos = now + 250_000_000L;
            GroupChannelControl.align(client, memberSounds.values(), group.id().toString());
        }

        @Override public boolean needsRebind(Minecraft client) { return false; }

        @Override
        public void rebind(Minecraft client) {
            if (stopped || paused || error != null || client.level == null || hasReachedEof()) return;
            Set<BlockPos> positions = group.members();
            if (opening == null && positions.stream().noneMatch(pos -> eligible(client, pos))) return;
            if (opening == null) {
                opening = AudioStreamFactory.openGroupBroadcast(track.path(), clock.seconds(), () -> clock.seconds());
            }
            if (!opening.isDone()) return;
            try {
                if (broadcast == null) broadcast = opening.join();
                if (broadcast.failure() != null) throw new java.util.concurrent.CompletionException(broadcast.failure());
                if (!broadcast.ready().isDone()) return;
                broadcast.ready().join();
            } catch (java.util.concurrent.CompletionException failure) {
                Throwable cause = failure.getCause();
                error = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                LOGGER.warn("Could not play group {} track {}", group.id(), track.fileName(), cause);
                releaseSounds();
                releaseDecoder();
                return;
            }
            if (positions.stream().noneMatch(pos -> eligible(client, pos))) return;
            if (clock.startedNanos() == 0) {
                clock = new PlaybackClock(clock.seconds(), System.nanoTime());
                playbackStarted = true;
            }
            if (hasReachedEof()) return;
            long now = System.nanoTime();
            if (now < nextChannelRetryNanos) return;

            GroupRingBroadcastStream currentBroadcast = broadcast;
            for (BlockPos pos : positions) {
                if (memberSounds.containsKey(pos) || !eligible(client, pos)
                        || SESSIONS.get(new SpeakerKey(dimension.toString(), pos)) != this) continue;
                SpeakerSoundInstance sound = new SpeakerSoundInstance(track.path(), pos, Vec3.atCenterOf(pos),
                        dimension, () -> CompletableFuture.completedFuture(
                                currentBroadcast.createMemberStream(group.id().toString(), pos)));
            sound.setGain(masterVolume * group.volume());
                sound.initializeAcoustics(client);
                var result = client.getSoundManager().play(sound);
                if (result == net.minecraft.client.sounds.SoundEngine.PlayResult.NOT_STARTED) {
                    nextChannelRetryNanos = now + 1_000_000_000L;
                    LOGGER.debug("No streaming channel for group {} at {}", group.id(), pos);
                    break;
                }
                memberSounds.put(pos, sound);
            }
        }

        @Override
        public void onSoundStopped(SpeakerSoundInstance sound) {
            memberSounds.remove(sound.getBlockPosition(), sound);
        }

        @Override
        public boolean containsSound(SpeakerSoundInstance other) {
            return memberSounds.get(other.getBlockPosition()) == other;
        }

        private record PlaybackClock(double offsetSeconds, long startedNanos) {
            double seconds() {
                return startedNanos == 0 ? offsetSeconds
                        : offsetSeconds + (System.nanoTime() - startedNanos) / 1_000_000_000.0;
            }
        }
    }
}
