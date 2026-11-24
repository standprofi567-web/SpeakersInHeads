package dev.klxsh.speakersinheads;

import dev.klxsh.speakersinheads.audio.SpatialSettings;
import dev.klxsh.speakersinheads.audio.StereoPairManager;
import dev.klxsh.speakersinheads.interaction.PlayerHeadInteraction;
import dev.klxsh.speakersinheads.audio.SpeakerAudioManager;
import dev.klxsh.speakersinheads.music.MusicLibrary;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SpeakersInHeadsClient implements ClientModInitializer {
    public static final String MOD_ID = "speakersinheads";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        ClientPreferences.initialize();
        dev.klxsh.speakersinheads.ui.ThemeAppearance.initialize();
        dev.klxsh.speakersinheads.audio.HybridAcoustics.initialize();
        SpatialSettings.initialize();
        StereoPairManager.initialize();
        dev.klxsh.speakersinheads.audio.SpeakerGroupManager.initialize();
        MusicLibrary.initialize();
        SpeakerAudioManager.initialize();
        dev.klxsh.speakersinheads.audio.SpeakerAnimationManager.initialize();
        PlayerHeadInteraction.register();
        LOGGER.info("SpeakersInHeads client initialized; local music library is at {}",
                MusicLibrary.getMusicDirectory());
    }
}
