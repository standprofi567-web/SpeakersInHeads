package dev.klxsh.speakersinheads.ui;

import dev.klxsh.speakersinheads.audio.PhysicalStereoPair;
import dev.klxsh.speakersinheads.audio.StereoPairManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Screen for discovering nearby player heads and binding them into a physical stereo pair.
 */
public final class StereoPairSetupScreen extends StudioScreen {
    private static final int PANEL_WIDTH = 380;
    private static final int PANEL_HEIGHT = 240;
    private static final int SCAN_RADIUS = 16;

    private final Screen parent;
    private final BlockPos currentHeadPos;
    private final String currentHeadType;
    private final List<DiscoveredHead> nearbyHeads = new ArrayList<>();

    private EditBox nameInput;
    private int scrollOffset;

    public StereoPairSetupScreen(Screen parent, BlockPos currentHeadPos, String currentHeadType) {
        super(Component.literal("Create Stereo Pair"));
        this.parent = parent;
        this.currentHeadPos = currentHeadPos.immutable();
        this.currentHeadType = currentHeadType;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(PANEL_WIDTH, width - 20);
        int panelHeight = Math.min(PANEL_HEIGHT, height - 20);
        int left = (width - panelWidth) / 2;
        int top = (height - panelHeight) / 2;
        int contentWidth = panelWidth - 28;

        // Scan nearby world for other player heads
        scanNearbyHeads();

        // 1. Name input field
        int nameY = top + 34;
        nameInput = new EditBox(font, left + 14, nameY, contentWidth, 18, Component.literal("Pair Name"));
        nameInput.setValue("Stereo Pair " + (StereoPairManager.allPairs().size() + 1));
        nameInput.setMaxLength(32);
        addRenderableWidget(nameInput);

        // 2. Discovered heads list
        int listTop = top + 74;
        int visibleRows = 5;
        int rowHeight = 20;

        for (int i = 0; i < visibleRows && (scrollOffset + i) < nearbyHeads.size(); i++) {
            DiscoveredHead target = nearbyHeads.get(scrollOffset + i);
            String distText = String.format("%.1f blocks", target.distance);
            String btnText = "RIGHT  /  " + target.pos.getX() + ", " + target.pos.getY() + ", " + target.pos.getZ() + " (" + distText + ")";

            Button pairBtn = StudioUi.button(Component.literal(btnText), button -> {
                String pairName = nameInput.getValue().trim();
                if (pairName.isEmpty()) pairName = "Stereo Pair";
                Minecraft client = Minecraft.getInstance();
                Identifier dim = client.level != null ? client.level.dimension().identifier() : Identifier.parse("minecraft:overworld");

                StereoPairManager.createPair(pairName, dim, currentHeadPos, target.pos);
                onClose();
            }).bounds(left + 14, listTop + i * rowHeight, contentWidth, rowHeight - 2).build();

            pairBtn.setTooltip(Tooltip.create(Component.literal(
                    "Designate current head as LEFT channel\nand " + target.type + " as RIGHT channel"
            )));
            addRenderableWidget(pairBtn);
        }

        // 3. Cancel button
        int bottomY = top + panelHeight - 24;
        addRenderableWidget(StudioUi.button(Component.literal("Cancel"), button -> onClose())
                .bounds(width / 2 - 50, bottomY, 100, 18)
                .build());
    }

    private void scanNearbyHeads() {
        nearbyHeads.clear();
        Minecraft client = Minecraft.getInstance();
        Level level = client.level;
        if (level == null) return;

        int cx = currentHeadPos.getX();
        int cy = currentHeadPos.getY();
        int cz = currentHeadPos.getZ();

        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dy = -SCAN_RADIUS; dy <= SCAN_RADIUS; dy++) {
                for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos p = new BlockPos(cx + dx, cy + dy, cz + dz);
                    if (!level.hasChunkAt(p)) continue;

                    BlockState state = level.getBlockState(p);
                    if (state.is(Blocks.PLAYER_HEAD) || state.is(Blocks.PLAYER_WALL_HEAD)) {
                        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        String type = state.is(Blocks.PLAYER_HEAD) ? "PLAYER_HEAD" : "PLAYER_WALL_HEAD";
                        nearbyHeads.add(new DiscoveredHead(p, type, dist));
                    }
                }
            }
        }

        nearbyHeads.sort(Comparator.comparingDouble(h -> h.distance));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = Math.max(0, nearbyHeads.size() - 5);
        if (max > 0) {
            int next = Math.max(0, Math.min(max, scrollOffset + (scrollY < 0 ? 1 : -1)));
            if (next != scrollOffset) {
                scrollOffset = next;
                clearWidgets();
                init();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float deltaTracker) {

        int panelWidth = Math.min(PANEL_WIDTH, width - 20);
        int panelHeight = Math.min(PANEL_HEIGHT, height - 20);
        int left = (width - panelWidth) / 2;
        int top = (height - panelHeight) / 2;

        // Background panel & borders
        drawPanel(graphics, width, height, left, top, panelWidth, panelHeight);

        // Header
        graphics.centeredText(font, "Create Physical Stereo Pair", width / 2, top + 10, StudioUi.text());
        graphics.text(font, StudioUi.fit(font, "LEFT  /  " + currentHeadPos.toShortString(), panelWidth - 28), left + 14, top + 23, StudioUi.muted());
        graphics.text(font, "Select Right Speaker Head (within 16 blocks):", left + 14, top + 60, StudioUi.accent());

        // Empty notice
        if (nearbyHeads.isEmpty()) {
            graphics.centeredText(font, "No other player heads found nearby.", width / 2, top + 100, StudioUi.danger());
            graphics.centeredText(font, "Place another head within 16 blocks to pair.", width / 2, top + 116, StudioUi.muted());
        }

        super.extractRenderState(graphics, mouseX, mouseY, deltaTracker);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record DiscoveredHead(BlockPos pos, String type, double distance) {}
}
