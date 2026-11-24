package dev.klxsh.speakersinheads.interaction;

import dev.klxsh.speakersinheads.SpeakersInHeadsClient;
import dev.klxsh.speakersinheads.ui.SpeakersInHeadsScreen;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public final class PlayerHeadInteraction {
    private PlayerHeadInteraction() {
    }

    public static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide() || Minecraft.getInstance().player != player) {
                return InteractionResult.PASS;
            }

            Minecraft client = Minecraft.getInstance();
            BlockPos pos = hitResult.getBlockPos();
            Block block = world.getBlockState(pos).getBlock();
            if (block != Blocks.PLAYER_HEAD && block != Blocks.PLAYER_WALL_HEAD) {
                return InteractionResult.PASS;
            }

            if (!client.options.keyShift.isDown()) {
                return InteractionResult.PASS;
            }

            if (client.gui.screen() instanceof SpeakersInHeadsScreen) {
                return InteractionResult.FAIL;
            }

            String type = block == Blocks.PLAYER_HEAD ? "PLAYER_HEAD" : "PLAYER_WALL_HEAD";
            SpeakersInHeadsClient.LOGGER.info("Recognized {} at block position {}", type, pos);
            client.gui.setScreen(new SpeakersInHeadsScreen(pos, type));

            return InteractionResult.FAIL;
        });
    }
}
