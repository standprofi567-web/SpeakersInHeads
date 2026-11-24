package dev.klxsh.speakersinheads.audio;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intelligent acoustic room detector that classifies whether a speaker is situated
 * indoors or outdoors by performing lightweight multi-directional obstacle raycasts.
 *
 * <p>Acoustic Profile:
 * <ul>
 *   <li><b>INDOOR:</b> Ceiling found within 6 blocks and walls detected in ≥ 3 horizontal directions.
 *       Audible distance is naturally contained to 24 blocks, high frequencies are absorbed (cutoff ~1600 Hz),
 *       and subtle early room reflections are introduced.</li>
 *   <li><b>OUTDOOR:</b> Open air. Full 48-block propagation distance and transparent high frequency response.</li>
 * </ul>
 *
 * <p>Scan results are cached with a 40-tick (2.0s) TTL per block position to ensure zero frame-rate overhead.
 */
public final class RoomAcousticsDetector {
    public static final int MAX_CEILING_SCAN_BLOCKS = 6;
    public static final int MAX_WALL_SCAN_BLOCKS = 8;
    public static final long CACHE_TTL_MS = 2000L; // 40 ticks = 2 seconds

    public record RoomProfile(
            boolean isIndoor,
            float maxAudibleDistance,
            float cutoffHz,
            float reflectionGain,
            float reflectionDelayMs
    ) {
        public static final RoomProfile OUTDOOR = new RoomProfile(
                false, 48.0f, 20000.0f, 0.0f, 0.0f
        );

        public static final RoomProfile INDOOR = new RoomProfile(
                true, 24.0f, 1600.0f, 0.15f, 16.0f
        );
    }

    private record CacheEntry(RoomProfile profile, long timestamp) {}

    private static final Map<BlockPos, CacheEntry> CACHE = new ConcurrentHashMap<>();

    private RoomAcousticsDetector() {}

    /**
     * Determines the acoustic room profile for a given speaker position.
     *
     * @param level the current Minecraft level (null-safe)
     * @param pos the block position of the speaker
     * @return the resolved {@link RoomProfile} (cached if available)
     */
    public static RoomProfile detect(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return RoomProfile.OUTDOOR;
        }

        long now = System.currentTimeMillis();
        CacheEntry cached = CACHE.get(pos);
        if (cached != null && (now - cached.timestamp()) < CACHE_TTL_MS) {
            return cached.profile();
        }

        RoomProfile resolved = scanRoom(level, pos);
        CACHE.put(pos.immutable(), new CacheEntry(resolved, now));
        return resolved;
    }

    /**
     * Evaluates room containment via 6-direction raycast scanning.
     */
    public static RoomProfile scanRoom(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return RoomProfile.OUTDOOR;
        }

        // 1. Scan upwards for ceiling (max 6 blocks)
        boolean hasCeiling = false;
        for (int dy = 1; dy <= MAX_CEILING_SCAN_BLOCKS; dy++) {
            BlockPos checkPos = pos.above(dy);
            if (!level.hasChunkAt(checkPos)) break;
            BlockState state = level.getBlockState(checkPos);
            if (isOccluding(state)) {
                hasCeiling = true;
                break;
            }
        }

        if (!hasCeiling) {
            return RoomProfile.OUTDOOR;
        }

        // 2. Scan horizontally in 4 cardinal directions (max 8 blocks)
        int wallCount = 0;
        Direction[] cardinals = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

        for (Direction dir : cardinals) {
            for (int dist = 1; dist <= MAX_WALL_SCAN_BLOCKS; dist++) {
                BlockPos checkPos = pos.relative(dir, dist);
                if (!level.hasChunkAt(checkPos)) break;
                BlockState state = level.getBlockState(checkPos);
                if (isOccluding(state)) {
                    wallCount++;
                    break;
                }
            }
        }

        // Indoor requires ceiling + at least 3 walls within 8 blocks
        if (wallCount >= 3) {
            return RoomProfile.INDOOR;
        }

        return RoomProfile.OUTDOOR;
    }

    private static boolean isOccluding(BlockState state) {
        if (state.isAir()) return false;
        return state.isSolid() || state.canOcclude() || state.isSolidRender();
    }

    /**
     * Invalidates the cache for a specific block or all entries.
     */
    public static void invalidate(BlockPos pos) {
        if (pos != null) CACHE.remove(pos);
    }

    public static void clearCache() {
        CACHE.clear();
    }
}
