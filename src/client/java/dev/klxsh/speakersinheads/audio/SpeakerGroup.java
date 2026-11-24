package dev.klxsh.speakersinheads.audio;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Model representing a synchronized speaker group of multiple Minecraft player heads.
 */
public final class SpeakerGroup {
    private final UUID id;
    private String name;
    private final Identifier dimension;
    private final Set<BlockPos> members;
    private float volume;

    public SpeakerGroup(UUID id, String name, Identifier dimension, Set<BlockPos> members, float volume) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = name != null && !name.isBlank() ? name : "Group " + id.toString().substring(0, 6);
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.members = new LinkedHashSet<>();
        if (members != null) {
            for (BlockPos pos : members) {
                this.members.add(pos.immutable());
            }
        }
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
    }

    public SpeakerGroup(UUID id, String name, Identifier dimension, Set<BlockPos> members) {
        this(id, name, dimension, members, 1.0f);
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
    }

    public Identifier dimension() {
        return dimension;
    }

    public synchronized Set<BlockPos> members() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(members));
    }

    public synchronized boolean contains(Identifier dim, BlockPos pos) {
        return this.dimension.equals(dim) && members.contains(pos);
    }

    public synchronized boolean addMember(BlockPos pos) {
        if (pos != null) {
            return members.add(pos.immutable());
        }
        return false;
    }

    public synchronized boolean removeMember(BlockPos pos) {
        return members.remove(pos);
    }

    public synchronized int size() {
        return members.size();
    }

    public float volume() {
        return volume;
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SpeakerGroup that = (SpeakerGroup) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
