package dev.klxsh.speakersinheads.audio;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents a persistent binding between two physical Minecraft player heads
 * configured as a dedicated left-right stereo speaker system.
 */
public final class PhysicalStereoPair {
    private final UUID id;
    private String name;
    private final Identifier dimension;
    private final BlockPos leftPos;
    private final BlockPos rightPos;
    private volatile boolean swapped;
    private volatile float leftVolume = 1.0f;
    private volatile float rightVolume = 1.0f;
    private volatile float pairMasterVolume = 1.0f;

    public PhysicalStereoPair(UUID id, String name, Identifier dimension, BlockPos leftPos, BlockPos rightPos) {
        this.id = id != null ? id : UUID.randomUUID();
        this.name = name != null && !name.isBlank() ? name.trim() : "Stereo Pair";
        this.dimension = Objects.requireNonNull(dimension, "dimension cannot be null");
        this.leftPos = leftPos.immutable();
        this.rightPos = rightPos.immutable();
        this.swapped = false;
    }

    public PhysicalStereoPair(UUID id, String name, Identifier dimension, BlockPos leftPos, BlockPos rightPos,
                              boolean swapped, float leftVolume, float rightVolume, float pairMasterVolume) {
        this.id = id != null ? id : UUID.randomUUID();
        this.name = name != null && !name.isBlank() ? name.trim() : "Stereo Pair";
        this.dimension = Objects.requireNonNull(dimension, "dimension cannot be null");
        this.leftPos = leftPos.immutable();
        this.rightPos = rightPos.immutable();
        this.swapped = swapped;
        this.leftVolume = clamp(leftVolume);
        this.rightVolume = clamp(rightVolume);
        this.pairMasterVolume = clamp(pairMasterVolume);
    }

    public UUID id() { return id; }
    public String name() { return name; }
    public void setName(String name) { this.name = name != null && !name.isBlank() ? name.trim() : "Stereo Pair"; }

    public Identifier dimension() { return dimension; }
    public BlockPos leftPos() { return leftPos; }
    public BlockPos rightPos() { return rightPos; }

    public boolean isSwapped() { return swapped; }
    public void setSwapped(boolean value) { this.swapped = value; }
    public void swap() { this.swapped = !this.swapped; }

    public float leftVolume() { return leftVolume; }
    public void setLeftVolume(float value) { this.leftVolume = clamp(value); }

    public float rightVolume() { return rightVolume; }
    public void setRightVolume(float value) { this.rightVolume = clamp(value); }

    public float pairMasterVolume() { return pairMasterVolume; }
    public void setPairMasterVolume(float value) { this.pairMasterVolume = clamp(value); }

    /** Returns true if the specified block position in the given dimension is part of this pair. */
    public boolean contains(Identifier dim, BlockPos pos) {
        return this.dimension.equals(dim) && (this.leftPos.equals(pos) || this.rightPos.equals(pos));
    }

    /** Returns true if the specified position is currently designated as the left audio emitter. */
    public boolean isLeft(BlockPos pos) {
        return swapped ? rightPos.equals(pos) : leftPos.equals(pos);
    }

    /** Returns true if the specified position is currently designated as the right audio emitter. */
    public boolean isRight(BlockPos pos) {
        return swapped ? leftPos.equals(pos) : rightPos.equals(pos);
    }

    /** Returns the physical partner block position for the given head in this pair. */
    public BlockPos getPartner(BlockPos pos) {
        if (leftPos.equals(pos)) return rightPos;
        if (rightPos.equals(pos)) return leftPos;
        return null;
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
