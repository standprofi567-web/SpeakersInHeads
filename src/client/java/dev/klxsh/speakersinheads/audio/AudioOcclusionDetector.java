package dev.klxsh.speakersinheads.audio;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.function.ToDoubleFunction;

/** Accumulates loss along a line through loaded collision shapes. */
public final class AudioOcclusionDetector {
    private AudioOcclusionDetector() {}

    public static float calculateOcclusion(Level level, Vec3 listener, Vec3 emitter) {
        if (level == null || listener == null || emitter == null) return 0f;
        return trace(listener, emitter, pos -> {
            if (level instanceof ClientLevel client && !SpeakerSoundInstance.isClientChunkLoaded(client, pos)) {
                return -1;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.getCollisionShape(level, pos).clip(listener, emitter, pos) == null) return 0;
            if (state.getBlock() instanceof DoorBlock) return state.getValue(DoorBlock.OPEN) ? 0.08 : 0.28;
            if (state.getBlock() instanceof TrapDoorBlock) return state.getValue(TrapDoorBlock.OPEN) ? 0.08 : 0.24;
            if (state.is(Blocks.GLASS) || state.is(Blocks.TINTED_GLASS) || state.is(Blocks.GLASS_PANE)
                    || state.is(Blocks.IRON_BARS)) return 0.15;
            return state.canOcclude() || state.isSolidRender() ? 0.45 : 0.2;
        });
    }

    static float trace(Vec3 listener, Vec3 emitter, ToDoubleFunction<BlockPos> lossAt) {
        BlockPos first = BlockPos.containing(listener);
        BlockPos last = BlockPos.containing(emitter);
        float[] loss = {0f};
        return BlockGetter.traverseBlocks(listener, emitter, loss, (sum, pos) -> {
            if (pos.equals(first) || pos.equals(last)) return null;
            double cell = lossAt.applyAsDouble(pos);
            if (cell < 0) return sum[0];
            sum[0] += (float) cell;
            return sum[0] >= 1f ? 1f : null;
        }, sum -> sum[0]);
    }
}
