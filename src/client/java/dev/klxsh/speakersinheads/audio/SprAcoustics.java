package dev.klxsh.speakersinheads.audio;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.sounds.SoundSource;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Associates environment callbacks with the exact streaming channel that received them. */
public final class SprAcoustics {
    private static final String ID = "sound_physics_remastered";
    private static final String VERIFIED_BINARY_VERSION = "1.5.4+26.3";
    private static final boolean SUPPORTED = FabricLoader.getInstance().getModContainer(ID)
            .map(mod -> VERIFIED_BINARY_VERSION.equals(mod.getMetadata().getVersion().getFriendlyString()))
            .orElse(false);
    private static final Set<Integer> SOURCES = ConcurrentHashMap.newKeySet();

    private SprAcoustics() {}

    public static void attach(int source) {
        if (SUPPORTED) SOURCES.add(source);
    }

    public static void detach(int source) {
        if (SUPPORTED) SOURCES.remove(source);
    }

    public static boolean ownsDirectSignal(int source) {
        return SUPPORTED && SOURCES.contains(source);
    }

    public static SoundSource environmentCategory(SoundSource category, boolean managed) {
        return managed && category == SoundSource.RECORDS ? SoundSource.BLOCKS : category;
    }

    public static String status() {
        if (SUPPORTED) return "Walls and water: SpeakersInHeads. Reflections: SPR when available.";
        return FabricLoader.getInstance().isModLoaded(ID)
                ? "SPR version unverified. Local filtering is enabled."
                : "Local filtering is enabled.";
    }
}
