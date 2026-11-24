package dev.klxsh.speakersinheads.ui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

final class UnifiedMusicPlayer {
    enum Action {
        PLAY("Play"), PAUSE("Pause"), RESUME("Resume");
        final String label;
        Action(String label) { this.label = label; }
    }

    static Action action(String state) {
        if (state.startsWith("Paused")) return Action.RESUME;
        if (state.startsWith("Playing") || state.equals("Loading")) return Action.PAUSE;
        return Action.PLAY;
    }

    static Button button(String label, String help, int x, int y, int w, Runnable callback) {
        var button = StudioUi.button(Component.literal(label), pressed -> callback.run()).bounds(x, y, w, 18).build();
        if (!help.isBlank()) button.setTooltip(Tooltip.create(Component.literal(help)));
        return button;
    }

    static void update(Button toggle, String state, boolean canPlay) {
        Action action = action(state);
        if (!toggle.getMessage().getString().equals(action.label)) toggle.setMessage(Component.literal(action.label));
        toggle.active = action != Action.PLAY || canPlay;
    }
}
