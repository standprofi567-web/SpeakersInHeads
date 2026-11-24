package dev.klxsh.speakersinheads.audio;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages visual 3D sound waves and physical head animations for playing speaker heads:
 * <ul>
 *   <li><b>Custom 3D Wavefronts:</b> Renders clean, concentric acoustic wavefront arcs expanding
 *       from the front face of the head along its forward normal vector. Default vanilla particles
 *       (NOTE, TRIAL_SPAWNER sparks) are completely eliminated.</li>
 *   <li><b>Differentiated Head Motion:</b>
 *     <ul>
 *       <li><b>Floor Heads (PLAYER_HEAD):</b> Rhythmic vertical bounce (~0.035 blocks amplitude) and tilt (~0.02 rad).</li>
 *       <li><b>Wall Heads (PLAYER_WALL_HEAD):</b> Strictly zero vertical bounce (0.0 displacement) as it is bolted to the wall;
 *           subtle front face micro-vibration only.</li>
 *     </ul>
 *   </li>
 * </ul>
 */
public final class SpeakerAnimationManager {
    public static final long WAVE_INTERVAL_TICKS = 20; // 1 wave per second (20 ticks)
    public static final long WAVE_LIFETIME_MS = 1000L;
    public static final double MAX_RENDER_DISTANCE_SQ = 48.0 * 48.0;

    // Color: Cyan / Soft-blue (RGB: 48, 208, 240)
    public static final int WAVE_COLOR_RED = 48;
    public static final int WAVE_COLOR_GREEN = 208;
    public static final int WAVE_COLOR_BLUE = 240;

    private static long tickCounter = 0;
    private static final List<SoundWave> ACTIVE_WAVES = new CopyOnWriteArrayList<>();

    private SpeakerAnimationManager() {}

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(SpeakerAnimationManager::onClientTick);
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(SpeakerAnimationManager::onRenderLevelFeatures);
    }

    private static void onClientTick(Minecraft client) {
        if (client.isPaused()) return;
        ClientLevel level = client.level;
        if (level == null) {
            ACTIVE_WAVES.clear();
            return;
        }

        tickCounter++;
        cleanExpiredWaves();

        if (!SpatialSettings.get().isAnimationsEnabled()) {
            ACTIVE_WAVES.clear();
            return;
        }

        // Spawn periodic 3D sound wave every 20 ticks (~1 second)
        if (tickCounter % WAVE_INTERVAL_TICKS == 0) {
            Set<BlockPos> playingHeads = SpeakerAudioManager.activePlayingPositions();
            if (playingHeads.isEmpty()) return;

            Vec3 playerPos = client.player != null ? client.player.position() : Vec3.ZERO;

            for (BlockPos pos : playingHeads) {
                if (!level.hasChunkAt(pos)) continue;

                double distSq = pos.distToCenterSqr(playerPos.x, playerPos.y, playerPos.z);
                if (distSq > MAX_RENDER_DISTANCE_SQ) continue; // Distance cull

                Vec3 faceOrigin = SpeakerGeometry.getFrontFaceOrigin(level, pos);
                Vec3 forward = SpeakerGeometry.getForwardVector(level, pos);
                Vec3 right = SpeakerGeometry.getRightVector(level, pos);
                Vec3 up = SpeakerGeometry.getUpVector(level, pos);

                long now = System.currentTimeMillis();
                ACTIVE_WAVES.add(new SoundWave(pos, faceOrigin, forward, right, up, now));
            }
        }
    }

    private static void onRenderLevelFeatures(LevelRenderContext context) {
        if (!SpatialSettings.get().isAnimationsEnabled() || ACTIVE_WAVES.isEmpty()) return;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.gameRenderer == null) return;
        Camera camera = client.gameRenderer.mainCamera();
        if (camera == null) return;

        Vec3 camPos = camera.position();
        long now = System.currentTimeMillis();

        context.submitNodeCollector().submitCustomGeometry(
                context.poseStack(),
                RenderTypes.linesTranslucent(),
                (pose, consumer) -> {
                    for (SoundWave wave : ACTIVE_WAVES) {
                        float p = wave.progress(now);
                        if (p >= 1.0f) continue;

                        // Radial expansion and forward propagation
                        double radius = 0.15 + (0.50 * p);
                        double forwardDist = 0.05 + (0.45 * p);
                        float alpha = (1.0f - p) * 0.70f;
                        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));

                        Vec3 center = wave.origin().add(wave.forward().scale(forwardDist));
                        Vec3 right = wave.right();
                        Vec3 up = wave.up();

                        // Draw concentric 3D arc (10 segments over 120° field)
                        int segments = 10;
                        double arcAngle = Math.PI * (2.0 / 3.0); // 120 degrees
                        double startAngle = -arcAngle / 2.0;
                        double angleStep = arcAngle / segments;

                        Vec3 prevPoint = null;
                        float lineWidth = 2.5f;

                        for (int i = 0; i <= segments; i++) {
                            double angle = startAngle + (i * angleStep);
                            double xOffset = Math.sin(angle) * radius;
                            double yOffset = Math.cos(angle) * radius * 0.35; // gentle vertical curve

                            Vec3 pt = center.add(right.scale(xOffset)).add(up.scale(yOffset));

                            if (prevPoint != null) {
                                float dx = (float) (pt.x - prevPoint.x);
                                float dy = (float) (pt.y - prevPoint.y);
                                float dz = (float) (pt.z - prevPoint.z);
                                float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                                float nx = len > 0.0001f ? dx / len : (float) wave.forward().x;
                                float ny = len > 0.0001f ? dy / len : (float) wave.forward().y;
                                float nz = len > 0.0001f ? dz / len : (float) wave.forward().z;

                                consumer.addVertex(pose,
                                                (float) (prevPoint.x - camPos.x),
                                                (float) (prevPoint.y - camPos.y),
                                                (float) (prevPoint.z - camPos.z))
                                        .setColor(WAVE_COLOR_RED, WAVE_COLOR_GREEN, WAVE_COLOR_BLUE, a)
                                        .setNormal(pose, nx, ny, nz)
                                        .setLineWidth(lineWidth);

                                consumer.addVertex(pose,
                                                (float) (pt.x - camPos.x),
                                                (float) (pt.y - camPos.y),
                                                (float) (pt.z - camPos.z))
                                        .setColor(WAVE_COLOR_RED, WAVE_COLOR_GREEN, WAVE_COLOR_BLUE, a)
                                        .setNormal(pose, nx, ny, nz)
                                        .setLineWidth(lineWidth);
                            }
                            prevPoint = pt;
                        }
                    }
                }
        );
    }

    private static void cleanExpiredWaves() {
        long now = System.currentTimeMillis();
        Iterator<SoundWave> it = ACTIVE_WAVES.iterator();
        while (it.hasNext()) {
            SoundWave wave = it.next();
            if (now - wave.spawnTimeMs >= WAVE_LIFETIME_MS) {
                ACTIVE_WAVES.remove(wave);
            }
        }
    }

    public static List<SoundWave> activeWaves() {
        return ACTIVE_WAVES;
    }

    /**
     * Calculates the subtle vertical bounce amplitude for a head model.
     * Floor heads bounce noticeably (~0.035 blocks amplitude); wall heads have strictly 0.0 vertical bounce.
     */
    public static float getHeadBounce(BlockPos pos, float partialTicks) {
        if (!SpatialSettings.get().isAnimationsEnabled()) return 0.0f;
        if (!SpeakerAudioManager.isPlaying(pos)) return 0.0f;

        Minecraft client = null;
        try {
            client = Minecraft.getInstance();
        } catch (Throwable ignored) {}

        if (client != null && client.level != null) {
            BlockState state = client.level.getBlockState(pos);
            // Wall heads are bolted to the wall: strictly zero vertical bounce
            if (state.is(Blocks.PLAYER_WALL_HEAD)) {
                return 0.0f;
            }
        }

        double seconds = SpeakerAudioManager.secondsPlayed(pos) + (partialTicks * 0.05);
        return (float) (Math.sin(seconds * 7.5) * 0.035);
    }

    /**
     * Calculates the tilt angle (in radians) for a head model.
     * Floor heads tilt with rhythmic sway (~0.02 rad); wall heads exhibit subtle micro-vibration (~0.005 rad).
     */
    public static float getHeadTilt(BlockPos pos, float partialTicks) {
        if (!SpatialSettings.get().isAnimationsEnabled()) return 0.0f;
        if (!SpeakerAudioManager.isPlaying(pos)) return 0.0f;

        Minecraft client = null;
        try {
            client = Minecraft.getInstance();
        } catch (Throwable ignored) {}

        float amplitude = 0.020f;
        if (client != null && client.level != null) {
            BlockState state = client.level.getBlockState(pos);
            if (state.is(Blocks.PLAYER_WALL_HEAD)) {
                amplitude = 0.005f; // subtle micro-vibration for wall heads
            }
        }

        double seconds = SpeakerAudioManager.secondsPlayed(pos) + (partialTicks * 0.05);
        return (float) (Math.sin(seconds * 3.75) * amplitude);
    }

    public record SoundWave(BlockPos headPos, Vec3 origin, Vec3 forward, Vec3 right, Vec3 up, long spawnTimeMs) {
        public float progress(long nowMs) {
            return Math.min(1.0f, Math.max(0.0f, (nowMs - spawnTimeMs) / (float) WAVE_LIFETIME_MS));
        }
    }
}
