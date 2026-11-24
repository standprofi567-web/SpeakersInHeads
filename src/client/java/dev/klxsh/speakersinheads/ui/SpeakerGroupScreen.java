package dev.klxsh.speakersinheads.ui;

import dev.klxsh.speakersinheads.audio.SpeakerAudioManager;
import dev.klxsh.speakersinheads.audio.SpeakerGroup;
import dev.klxsh.speakersinheads.audio.SpeakerGroupManager;
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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Screen for managing multi-speaker groups: creating groups, adding/removing members,
 * and listing nearby heads to build large synchronized acoustic clusters.
 */
public final class SpeakerGroupScreen extends StudioScreen {
    private static final int PANEL_WIDTH = 400;
    private static final int PANEL_HEIGHT = 260;
    private static final int SCAN_RADIUS = 24;

    private final Screen parent;
    private final BlockPos currentHeadPos;
    private final String currentHeadType;
    private final List<DiscoveredHead> nearbyHeads = new ArrayList<>();

    private EditBox nameInput;
    private String groupNameDraft;
    private int scrollOffset;
    private List<BlockPos> memberRows = List.of();
    private int memberOffset;
    private int visibleHeads;

    public SpeakerGroupScreen(Screen parent, BlockPos currentHeadPos, String currentHeadType) {
        super(Component.literal("Speaker Group Setup"));
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

        Minecraft client = Minecraft.getInstance();
        Identifier dim = client.level != null ? client.level.dimension().identifier() : Identifier.parse("minecraft:overworld");
        Optional<SpeakerGroup> currentGroup = SpeakerGroupManager.findGroupFor(dim, currentHeadPos);

        scanNearbyHeads();
        memberRows = currentGroup.map(group -> group.members().stream()
                .sorted(Comparator.comparingLong(BlockPos::asLong)).toList()).orElseGet(List::of);
        visibleHeads = Math.max(0, (panelHeight - 104) / 20);

        if (currentGroup.isPresent()) {
            SpeakerGroup group = currentGroup.get();
            // Group detail mode
            int actionY = top + 34;

            // Header info & leave/sync/disband buttons
            int bWidth = (contentWidth - 12) / 3;
            Button leaveBtn = StudioUi.button(Component.literal("Leave Group"), button -> {
                SpeakerAudioManager.removeSpeakerFromGroup(currentHeadPos);
                onClose();
            }).bounds(left + 14, actionY, bWidth, 18).build();
            leaveBtn.setTooltip(Tooltip.create(Component.literal("Remove only this head from the group")));
            addRenderableWidget(leaveBtn);

            Button syncBtn = StudioUi.button(Component.literal("Sync Now"), button -> {
                SpeakerAudioManager.resyncGroup(currentHeadPos);
            }).bounds(left + 14 + bWidth + 6, actionY, bWidth, 18).build();
            syncBtn.setTooltip(Tooltip.create(Component.literal("Re-align all group speakers to the master playback timeline")));
            addRenderableWidget(syncBtn);

            Button disbandBtn = StudioUi.button(Component.literal("Disband Group"), button -> {
                SpeakerAudioManager.stop(currentHeadPos);
                SpeakerGroupManager.deleteGroup(group.id());
                onClose();
            }).bounds(left + 14 + (bWidth + 6) * 2, actionY, bWidth, 18).build();
            disbandBtn.setTooltip(Tooltip.create(Component.literal("Permanently delete this group and stop all speakers")));
            addRenderableWidget(disbandBtn);

            // Nearby heads that can be added to this existing group
            int listTop = top + 76;
            int visibleRows = visibleHeads;
            int rowHeight = 20;

            List<DiscoveredHead> availableToAdd = nearbyHeads.stream()
                    .filter(h -> !group.members().contains(h.pos))
                    .toList();

            scrollOffset = Math.clamp(scrollOffset, 0, Math.max(0, availableToAdd.size() - visibleRows));
            int addX = left + 20 + contentWidth / 2;
            for (int i = 0; i < visibleRows && (scrollOffset + i) < availableToAdd.size(); i++) {
                DiscoveredHead target = availableToAdd.get(scrollOffset + i);
                String distText = String.format("%.1f blocks", target.distance);
                String btnText = "+ Add " + target.pos.getX() + ", " + target.pos.getY() + ", " + target.pos.getZ() + " (" + distText + ")";

                Button addBtn = StudioUi.button(Component.literal(btnText), button -> {
                    group.addMember(target.pos);
                    SpeakerGroupManager.save();
                    clearWidgets();
                    init();
                }).bounds(addX, listTop + i * rowHeight, contentWidth / 2 - 6, rowHeight - 2).build();
                addRenderableWidget(addBtn);
            }
        } else {
            // Group creation mode
            int nameY = top + 34;
            nameInput = new EditBox(font, left + 14, nameY, contentWidth, 18, Component.literal("Group Name"));
            nameInput.setValue(groupNameDraft == null ? "Group " + (SpeakerGroupManager.allGroups().size() + 1) : groupNameDraft);
            nameInput.setResponder(value -> groupNameDraft = value);
            nameInput.setMaxLength(32);
            addRenderableWidget(nameInput);

            int listTop = top + 76;
            int visibleRows = visibleHeads;
            int rowHeight = 20;

            for (int i = 0; i < visibleRows && (scrollOffset + i) < nearbyHeads.size(); i++) {
                DiscoveredHead target = nearbyHeads.get(scrollOffset + i);
                String distText = String.format("%.1f blocks", target.distance);
                String btnText = "Create Group with " + target.pos.getX() + ", " + target.pos.getY() + ", " + target.pos.getZ() + " (" + distText + ")";

                Button createBtn = StudioUi.button(Component.literal(btnText), button -> {
                    String groupName = nameInput.getValue().trim();
                    if (groupName.isEmpty()) groupName = "Speaker Group";

                    Set<BlockPos> members = new HashSet<>();
                    members.add(currentHeadPos);
                    members.add(target.pos);

                    SpeakerGroupManager.createGroup(groupName, dim, members);
                    onClose();
                }).bounds(left + 14, listTop + i * rowHeight, contentWidth, rowHeight - 2).build();

                createBtn.setTooltip(Tooltip.create(Component.literal(
                        "Form a synchronized speaker group containing this head and " + target.type
                )));
                addRenderableWidget(createBtn);
            }
        }

        // Back / Close button
        int bottomY = top + panelHeight - 24;
        addRenderableWidget(StudioUi.button(Component.literal("Back"), button -> onClose())
                .bounds(width / 2 + 4, bottomY, 86, 18)
                .build());
        addRenderableWidget(UnifiedMusicPlayer.button("Settings", "Open settings for this group",
                width / 2 - 90, bottomY, 86,
                () -> minecraft.gui.setScreen(new SettingsScreen(this, currentHeadPos, currentHeadType))));
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        int top = (height - Math.min(PANEL_HEIGHT, height - 20)) / 2;
        if (scrollY == 0 || y < top + 76 || y >= top + 76 + visibleHeads * 20) {
            return super.mouseScrolled(x, y, scrollX, scrollY);
        }
        int step = scrollY < 0 ? 1 : -1;
        if (!memberRows.isEmpty() && x < width / 2) {
            memberOffset = Math.clamp(memberOffset + step, 0, Math.max(0, memberRows.size() - visibleHeads));
        } else {
            long available = nearbyHeads.stream().filter(head -> !memberRows.contains(head.pos)).count();
            int next = Math.clamp(scrollOffset + step, 0, Math.max(0, (int) available - visibleHeads));
            if (next != scrollOffset) {
                scrollOffset = next;
                clearWidgets();
                init();
            }
        }
        return true;
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
            for (int dy = -SCAN_RADIUS / 2; dy <= SCAN_RADIUS / 2; dy++) {
                for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos candidate = new BlockPos(cx + dx, cy + dy, cz + dz);
                    if (!level.hasChunkAt(candidate)) continue;

                    BlockState state = level.getBlockState(candidate);
                    if (state.is(Blocks.PLAYER_HEAD) || state.is(Blocks.PLAYER_WALL_HEAD)) {
                        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        String type = state.is(Blocks.PLAYER_WALL_HEAD) ? "Wall Head" : "Floor Head";
                        nearbyHeads.add(new DiscoveredHead(candidate.immutable(), type, dist));
                    }
                }
            }
        }

        nearbyHeads.sort(Comparator.comparingDouble(h -> h.distance));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float deltaTracker) {

        int panelWidth = Math.min(PANEL_WIDTH, width - 20);
        int panelHeight = Math.min(PANEL_HEIGHT, height - 20);
        int left = (width - panelWidth) / 2;
        int top = (height - panelHeight) / 2;

        drawPanel(graphics, width, height, left, top, panelWidth, panelHeight);

        Minecraft client = Minecraft.getInstance();
        Identifier dim = client.level != null ? client.level.dimension().identifier() : Identifier.parse("minecraft:overworld");
        Optional<SpeakerGroup> currentGroup = SpeakerGroupManager.findGroupFor(dim, currentHeadPos);

        String title = currentGroup.isPresent()
                ? "Speaker Group: " + currentGroup.get().name() + " (" + currentGroup.get().size() + " heads)"
                : "Create Speaker Group";
        graphics.centeredText(font, title, width / 2, top + 10, StudioUi.text());

        if (currentGroup.isPresent()) {
            int column = (panelWidth - 28) / 2;
            int memberX = left + 14;
            graphics.text(font, "Members / " + memberRows.size(), memberX, top + 60, StudioUi.accent());
            graphics.text(font, "Add nearby head", memberX + column + 6, top + 60, StudioUi.accent());
            int offset = Math.clamp(memberOffset, 0, Math.max(0, memberRows.size() - visibleHeads));
            for (int i = 0; i < visibleHeads && offset + i < memberRows.size(); i++) {
                BlockPos pos = memberRows.get(offset + i);
                int y = top + 76 + i * 20;
                boolean loaded = client.level != null && client.level.getChunkSource()
                        .getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
                graphics.fill(memberX, y, memberX + column - 6, y + 18, StudioUi.control());
                graphics.text(font, StudioUi.fit(font, pos.toShortString()
                        + (loaded ? "  /  Loaded" : "  /  Unloaded"), column - 14),
                        memberX + 4, y + 5, loaded ? StudioUi.text() : StudioUi.muted());
            }
        } else {
            graphics.text(font, "Choose a nearby head to form a group", left + 14, top + 60, StudioUi.accent());
            if (nearbyHeads.isEmpty()) {
                graphics.text(font, "No nearby heads found.", left + 14, top + 86, StudioUi.muted());
            }
        }

        super.extractRenderState(graphics, mouseX, mouseY, deltaTracker);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    private record DiscoveredHead(BlockPos pos, String type, double distance) {}
}
