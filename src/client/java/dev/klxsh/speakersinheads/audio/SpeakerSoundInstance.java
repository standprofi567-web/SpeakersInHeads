package dev.klxsh.speakersinheads.audio;

import dev.klxsh.speakersinheads.SpeakersInHeadsClient;
import net.fabricmc.fabric.api.client.sound.v1.FabricSoundInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A positioned Minecraft sound whose sample stream is decoded from a local file.
 *
 * <p>Supports single-point mono/stereo sources as well as discrete virtual stereo speaker
 * emitters with precise world-space positioning and sample-level gain smoothing.
 */
public final class SpeakerSoundInstance extends AbstractSoundInstance
        implements FabricSoundInstance, TickableSoundInstance {

    public static final int ATTENUATION_DISTANCE = 256;
    private static final Identifier SOUND = Identifier.fromNamespaceAndPath(SpeakersInHeadsClient.MOD_ID, "local_stream");

    public enum HeadState {
        ACTIVE,
        TEMPORARILY_UNLOADED,
        DESTROYED
    }

    private final Path file;
    private final BlockPos blockPosition;
    private final Identifier dimension;
    private final Supplier<CompletableFuture<? extends ManagedAudioStream>> streamSupplier;
    private final AtomicBoolean paused = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicReference<String> error = new AtomicReference<>();
    private volatile ManagedAudioStream stream;
    private volatile HeadState headState = HeadState.ACTIVE;

    /** User-controlled gain: master × head, in [0, 1]. */
    private volatile float userGain = 1.0f;

    /** Distance-based gain computed every tick, in [0, 1]. */
    private volatile float spatialGain = 1.0f;
    private volatile boolean underwater;
    private volatile float occlusion;

    public SpeakerSoundInstance(Path file, BlockPos blockPosition, Identifier dimension) {
        this(file, blockPosition, new Vec3(blockPosition.getX() + 0.5, blockPosition.getY() + 0.5, blockPosition.getZ() + 0.5),
                dimension, () -> AudioStreamFactory.openPrebuffered(file));
    }

    public SpeakerSoundInstance(Path file, BlockPos blockPosition, Vec3 emitterPosition, Identifier dimension,
                                Supplier<CompletableFuture<? extends ManagedAudioStream>> streamSupplier) {
        super(SOUND, SoundSource.RECORDS, SoundInstance.createUnseededRandom());
        this.file = file.toAbsolutePath().normalize();
        this.blockPosition = blockPosition.immutable();
        this.dimension = dimension;
        this.streamSupplier = streamSupplier;
        this.x = emitterPosition.x;
        this.y = emitterPosition.y;
        this.z = emitterPosition.z;
        this.volume = 1.0f;
        this.pitch = 1.0f;
        this.relative = false;
        // Attenuation.NONE delegates distance attenuation to our PCM pipeline to prevent
        // double-attenuation, while OpenAL retains genuine 3D stereo panning for mono formats.
        this.attenuation = Attenuation.NONE;
    }

    @Override
    public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary library, Identifier id, boolean repeatInstantly) {
        if (stopped.get()) return CompletableFuture.failedFuture(new IOException("Playback was stopped"));
        return streamSupplier.get().thenApply(created -> {
            if (stopped.get()) {
                try {
                    created.close();
                } catch (IOException ignored) {}
                throw new CompletionException(new IOException("Playback was stopped"));
            }
            stream = created;
            // Stop can race with publication of a stream created on a decoder worker.
            if (stopped.get()) {
                closeStream();
                throw new CompletionException(new IOException("Playback was stopped"));
            }
            created.setPaused(paused.get());
            created.setGain(effectiveGain());
            created.setUnderwater(underwater);
            created.setOcclusion(occlusion * HybridAcoustics.wall());
            return (AudioStream) created;
        }).whenComplete((created, failure) -> {
            if (failure != null && !stopped.get()) {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                        ? failure.getCause() : failure;
                String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                error.set(message);
                SpeakersInHeadsClient.LOGGER.warn("Could not open local audio stream for {}: {}", file, message);
            }
        });
    }

    public void setPaused(boolean value) {
        paused.set(value);
        ManagedAudioStream current = stream;
        if (current != null) current.setPaused(value);
    }

    /** Sets the user-controlled gain (master × head). Spatial gain is applied on top. */
    public void setGain(float value) {
        userGain = Math.max(0.0f, Math.min(1.0f, value));
        applyEffectiveGain();
    }

    public void stopAndRelease() {
        if (!stopped.compareAndSet(false, true)) return;
        closeStream();
        Minecraft.getInstance().getSoundManager().stop(this);
    }

    private void closeStream() {
        ManagedAudioStream current = stream;
        if (current != null) {
            try {
                current.close();
            } catch (IOException exception) {
                error.compareAndSet(null, exception.getMessage());
            }
        }
    }

    public String playbackState() {
        if (error.get() != null) return "Error: " + error.get();
        if (stopped.get()) return "Stopped";
        ManagedAudioStream current = stream;
        if (current != null && current.failure() != null) return "Error: " + current.failure().getMessage();
        if (current != null && current.isEnded()) return "Finished";
        if (paused.get()) return "Paused";
        if (current == null) return "Loading";
        if (headState == HeadState.TEMPORARILY_UNLOADED) return "Playing (Unloaded)";
        return "Playing";
    }

    public HeadState headState() {
        return headState;
    }

    boolean hasSubmittedGroupAudio() {
        return stream instanceof GroupMemberAudioStream member && member.hasSubmittedPcm();
    }

    GroupMemberAudioStream groupStream() {
        return stream instanceof GroupMemberAudioStream member ? member : null;
    }

    public boolean isChunkLoaded() {
        return headState != HeadState.TEMPORARILY_UNLOADED;
    }

    public BlockPos getBlockPosition() {
        return blockPosition;
    }

    public String error() {
        String currentError = error.get();
        ManagedAudioStream current = stream;
        return currentError != null ? currentError
                : current != null && current.failure() != null ? current.failure().getMessage() : null;
    }

    public double secondsPlayed() {
        ManagedAudioStream current = stream;
        return current == null ? 0 : current.secondsPlayed();
    }

    public boolean isStopped() {
        return stopped.get();
    }

    @Override
    public void tick() {
        if (stopped.get()) return;
        Minecraft client = Minecraft.getInstance();

        // ── World and dimension check ──────────────────────────────────
        if (client.level == null || !client.level.dimension().identifier().equals(dimension)) {
            if (stopped.compareAndSet(false, true)) {
                closeStream();
                SpeakerAudioManager.onSoundInstanceStopped(this);
            }
            return;
        }

        // ── Chunk unloading vs. physical destruction ───────────────────
        boolean chunkLoaded = isClientChunkLoaded(client.level, blockPosition);
        if (!chunkLoaded) {
            // State 2: Chunk is temporarily unloaded.
            // Do NOT mute or stop. Session continues, PCM keeps playing, playback clock moves forward.
            // Spatial attenuation MUST continue calculating using the preserved world emitter coordinates (x, y, z).
            headState = HeadState.TEMPORARILY_UNLOADED;
            updateSpatialGain(client);
            updateUnderwaterState(client);
            return;
        }

        // Chunk is loaded: verify that the head block physically exists
        boolean headExists = isSupportedHead(client.level.getBlockState(blockPosition));
        if (headExists) {
            // State 1: Head exists and chunk is loaded. Re-bind / active.
            headState = HeadState.ACTIVE;
        } else {
            // State 3: Chunk is loaded, but head block is genuinely destroyed or replaced!
            headState = HeadState.DESTROYED;
            if (stopped.compareAndSet(false, true)) {
                closeStream();
                SpeakerAudioManager.onSoundInstanceStopped(this);
            }
            return;
        }

        ManagedAudioStream current = stream;
        if (current != null) current.reportDiagnosticsIfDue();
        boolean failed = current != null && current.failure() != null;
        boolean finishedAndDrained = current != null && current.isEnded()
                && !client.getSoundManager().isActive(this);

        if (failed || finishedAndDrained) {
            if (stopped.compareAndSet(false, true)) {
                closeStream();
                SpeakerAudioManager.onSoundInstanceStopped(this);
            }
            return;
        }

        // ── Spatial distance attenuation and underwater filter ─────────
        updateSpatialGain(client);
        updateUnderwaterState(client);
    }

    void initializeAcoustics(Minecraft client) {
        updateSpatialGain(client);
        updateUnderwaterState(client);
    }

    private void updateUnderwaterState(Minecraft client) {
        underwater = isListenerUnderwater(client);
        ManagedAudioStream current = stream;
        if (current != null) {
            current.setUnderwater(underwater);
        }
    }

    public static boolean isListenerUnderwater(Minecraft client) {
        if (client == null) return false;
        if (client.gameRenderer != null && client.gameRenderer.mainCamera() != null) {
            if (client.gameRenderer.mainCamera().getFluidInCamera() == net.minecraft.world.level.material.FogType.WATER) {
                return true;
            }
        }
        if (client.player != null && client.player.isUnderWater()) {
            return true;
        }
        return false;
    }

    private void updateSpatialGain(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return;

        Vec3 listenerPos = player.getEyePosition();
        Vec3 emitterPos = new Vec3(x, y, z);
        SpatialSettings settings = SpatialSettings.get();
        float distance = (float) listenerPos.distanceTo(emitterPos);
        float newSpatialGain = settings.gainForDistance(distance);
        occlusion = newSpatialGain > 0.0f
                ? AudioOcclusionDetector.calculateOcclusion(client.level, listenerPos, emitterPos) : 0.0f;
        ManagedAudioStream current = stream;
        if (current != null) current.setOcclusion(occlusion * HybridAcoustics.wall());

        if (Float.compare(newSpatialGain, spatialGain) != 0) {
            spatialGain = newSpatialGain;
            applyEffectiveGain();
        }
    }

    private float effectiveGain() {
        return userGain * spatialGain;
    }

    private void applyEffectiveGain() {
        ManagedAudioStream current = stream;
        if (current != null) current.setGain(effectiveGain());
    }

    static boolean isClientChunkLoaded(ClientLevel level, BlockPos pos) {
        // ClientLevel.hasChunk always returns true in Minecraft 26.3.
        return level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ())) != null;
    }

    static boolean isSupportedHead(BlockState state) {
        return state.is(Blocks.PLAYER_HEAD) || state.is(Blocks.PLAYER_WALL_HEAD);
    }

    public boolean isEnded() {
        ManagedAudioStream current = stream;
        return current != null && current.isEnded();
    }

    @Override
    public boolean isLooping() {
        return false;
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }
}
