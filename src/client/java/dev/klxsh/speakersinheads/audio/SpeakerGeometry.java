package dev.klxsh.speakersinheads.audio;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.WallSkullBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Spatial orientation and geometry calculations for player head blocks.
 * Accurately aligns speaker front faces and sound wave propagation vectors
 * for all 16 floor rotations and all 4 wall orientations.
 */
public final class SpeakerGeometry {
    public static final double DEFAULT_STEREO_OFFSET = 0.35; // blocks to left/right of head center

    private SpeakerGeometry() {}

    /**
     * Calculates the right-facing unit vector for a player head in world space.
     * Perpendicular to the front face (90° clockwise when viewed from above).
     */
    public static Vec3 getRightVector(Level level, BlockPos pos) {
        if (level == null) return new Vec3(1, 0, 0);

        BlockState state = level.getBlockState(pos);

        if (state.getBlock() instanceof SkullBlock && state.hasProperty(SkullBlock.ROTATION)) {
            int rotation = state.getValue(SkullBlock.ROTATION);
            double rad = Math.toRadians((rotation * 360.0) / 16.0);
            return new Vec3(Math.cos(rad), 0, Math.sin(rad)).normalize();
        } else if (state.getBlock() instanceof WallSkullBlock && state.hasProperty(WallSkullBlock.FACING)) {
            Direction facing = state.getValue(WallSkullBlock.FACING);
            return new Vec3(-facing.getStepZ(), 0, facing.getStepX()).normalize();
        }

        return new Vec3(1, 0, 0);
    }

    /**
     * Calculates the forward-facing unit vector (normal to the face) for a player head in world space.
     * Points outward from the front face surface into the world.
     */
    public static Vec3 getForwardVector(Level level, BlockPos pos) {
        if (level == null) return new Vec3(0, 0, -1);

        BlockState state = level.getBlockState(pos);

        if (state.getBlock() instanceof SkullBlock && state.hasProperty(SkullBlock.ROTATION)) {
            int rotation = state.getValue(SkullBlock.ROTATION);
            double rad = Math.toRadians((rotation * 360.0) / 16.0);
            // Minecraft floor head: rotation 0 faces North (0, 0, -1), 4 faces East (1, 0, 0),
            // 8 faces South (0, 0, 1), 12 faces West (-1, 0, 0)
            return new Vec3(Math.sin(rad), 0, -Math.cos(rad)).normalize();
        } else if (state.getBlock() instanceof WallSkullBlock && state.hasProperty(WallSkullBlock.FACING)) {
            Direction facing = state.getValue(WallSkullBlock.FACING);
            // Wall skull attached to wall: faces outward into the room along its facing direction
            return new Vec3(facing.getStepX(), 0, facing.getStepZ()).normalize();
        }

        return new Vec3(0, 0, -1);
    }

    /**
     * Calculates the orthogonal up vector for the head block forming a canonical 3D basis.
     */
    public static Vec3 getUpVector(Level level, BlockPos pos) {
        Vec3 forward = getForwardVector(level, pos);
        Vec3 right = getRightVector(level, pos);
        return right.cross(forward).normalize();
    }

    /**
     * Returns the exact 3D coordinates of the front face surface of the player head.
     */
    public static Vec3 getFrontFaceOrigin(Level level, BlockPos pos) {
        Vec3 forward = getForwardVector(level, pos);
        double centerX = pos.getX() + 0.5;
        double centerY = pos.getY() + 0.25;
        double centerZ = pos.getZ() + 0.5;

        if (level != null) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof WallSkullBlock && state.hasProperty(WallSkullBlock.FACING)) {
                Direction facing = state.getValue(WallSkullBlock.FACING);
                // Center is shifted away from the supporting wall towards the room
                centerX = pos.getX() + 0.5 - (facing.getStepX() * 0.25);
                centerZ = pos.getZ() + 0.5 - (facing.getStepZ() * 0.25);
            }
        }

        Vec3 center = new Vec3(centerX, centerY, centerZ);
        // Face is 0.26 blocks forward from center of the 0.5-wide skull cube
        return center.add(forward.scale(0.26));
    }

    /**
     * Calculates discrete stereo emitter positions (left and right) for virtual stereo.
     */
    public static StereoPositions calculateStereoPositions(Level level, BlockPos pos, double offset) {
        double centerX = pos.getX() + 0.5;
        double centerY = pos.getY() + 0.25;
        double centerZ = pos.getZ() + 0.5;

        if (level != null) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof WallSkullBlock && state.hasProperty(WallSkullBlock.FACING)) {
                Direction facing = state.getValue(WallSkullBlock.FACING);
                centerX = pos.getX() + 0.5 - (facing.getStepX() * 0.25);
                centerZ = pos.getZ() + 0.5 - (facing.getStepZ() * 0.25);
            }
        }

        Vec3 center = new Vec3(centerX, centerY, centerZ);
        Vec3 right = getRightVector(level, pos);
        Vec3 leftPos = center.subtract(right.scale(offset));
        Vec3 rightPos = center.add(right.scale(offset));
        return new StereoPositions(center, leftPos, rightPos);
    }

    /**
     * Container holding the center, left, and right virtual speaker positions for a head block.
     */
    public record StereoPositions(Vec3 center, Vec3 left, Vec3 right) {}
}
