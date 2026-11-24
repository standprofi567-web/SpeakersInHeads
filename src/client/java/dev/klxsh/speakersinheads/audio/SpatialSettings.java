package dev.klxsh.speakersinheads.audio;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static dev.klxsh.speakersinheads.SpeakersInHeadsClient.LOGGER;

/**
 * Persistent user-configurable spatial audio and attenuation settings.
 *
 * <p>Distance-based gain is computed once per client tick in the sound instance
 * and multiplied with the user gain (master × head) before it reaches the PCM
 * stream.  The settings are persisted to {@code spatial.json} inside the mod
 * config directory.
 */
public final class SpatialSettings {
    public static final float MIN_MAX_DISTANCE = 4.0f;
    public static final float MAX_MAX_DISTANCE = 128.0f;
    public static final float MIN_FULL_VOLUME_DISTANCE = 0.0f;

    private static final float DEFAULT_MAX_DISTANCE = 48.0f;
    private static final float DEFAULT_FULL_VOLUME_DISTANCE = 2.0f;
    private static final AttenuationCurve DEFAULT_CURVE = AttenuationCurve.SMOOTH;
    private static final SpatialMode DEFAULT_MODE = SpatialMode.MONO_SPATIAL;

    private static final SpatialSettings INSTANCE = new SpatialSettings();

    private volatile boolean enabled = true;
    private volatile boolean animationsEnabled = true;
    private volatile float maxDistance = DEFAULT_MAX_DISTANCE;
    private volatile float fullVolumeDistance = DEFAULT_FULL_VOLUME_DISTANCE;
    private volatile AttenuationCurve curve = DEFAULT_CURVE;
    private volatile SpatialMode mode = DEFAULT_MODE;

    private volatile Path configFile;

    private SpatialSettings() {}

    public static SpatialSettings get() {
        return INSTANCE;
    }

    /** Load persisted values or create the config file with defaults. */
    public static void initialize() {
        INSTANCE.configFile = FabricLoader.getInstance().getConfigDir()
                .resolve("speakersinheads").resolve("spatial.json");
        INSTANCE.load();
    }

    // ── Accessors ──────────────────────────────────────────────────────

    public boolean isEnabled() { return enabled; }
    public boolean isAnimationsEnabled() { return animationsEnabled; }
    public float maxDistance() { return maxDistance; }
    public float fullVolumeDistance() { return fullVolumeDistance; }
    public AttenuationCurve curve() { return curve; }
    public SpatialMode mode() { return mode; }

    // ── Mutators (validate, persist) ───────────────────────────────────

    public void setEnabled(boolean value) {
        enabled = value;
        persist();
    }

    public void setAnimationsEnabled(boolean value) {
        animationsEnabled = value;
        persist();
    }

    public void setMaxDistance(float value) {
        maxDistance = clampMax(value);
        if (fullVolumeDistance >= maxDistance) {
            fullVolumeDistance = Math.max(MIN_FULL_VOLUME_DISTANCE, maxDistance - 1.0f);
        }
        persist();
    }

    public void setFullVolumeDistance(float value) {
        fullVolumeDistance = clampFull(value, maxDistance);
        persist();
    }

    public void setCurve(AttenuationCurve value) {
        curve = value == null ? DEFAULT_CURVE : value;
        persist();
    }

    public void setMode(SpatialMode value) {
        mode = value == null ? DEFAULT_MODE : value;
        persist();
    }

    // ── Gain calculation ──────────────────────────────────────────────

    public static int nextRangePreset(float distance) {
        for (int preset = 16; preset <= 96; preset += 16) {
            if (distance < preset) return preset;
        }
        return 16;
    }

    /**
     * Returns the spatial gain multiplier for a given distance, in [0, 1].
     *
     * <ul>
     *   <li>distance ≤ fullVolumeDistance → 1.0</li>
     *   <li>distance ≥ maxDistance → 0.0</li>
     *   <li>Otherwise → curve-dependent interpolation</li>
     * </ul>
     *
     * @param distance world-space distance from head center to listener
     */
    public float gainForDistance(float distance) {
        return gainForDistance(distance, this.maxDistance);
    }

    /**
     * Returns the spatial gain multiplier for a given distance, in [0, 1], using a specific max distance cutoff.
     */
    public float gainForDistance(float distance, float effectiveMaxDistance) {
        if (!enabled) return 1.0f;
        float effectiveMax = Math.max(MIN_MAX_DISTANCE, effectiveMaxDistance);
        float effectiveFull = Math.min(fullVolumeDistance, Math.max(MIN_FULL_VOLUME_DISTANCE, effectiveMax - 1.0f));

        if (distance <= effectiveFull) return 1.0f;
        if (distance >= effectiveMax) return 0.0f;

        float t = (distance - effectiveFull) / (effectiveMax - effectiveFull);
        return switch (curve) {
            case LINEAR -> 1.0f - t;
            case SMOOTH -> {
                // smoothstep: f(t) = t² (3 − 2t), inverted so gain decreases
                float s = t * t * (3.0f - 2.0f * t);
                yield 1.0f - s;
            }
        };
    }

    // ── Persistence ───────────────────────────────────────────────────

    private void load() {
        if (configFile == null || !Files.isRegularFile(configFile)) {
            LOGGER.info("No spatial settings file; using defaults (maxDistance={}, fullVolume={}, curve={}, mode={})",
                    maxDistance, fullVolumeDistance, curve, mode);
            return;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(configFile));
            if (!root.isJsonObject()) {
                LOGGER.warn("Spatial settings file is not a JSON object; using defaults");
                return;
            }
            JsonObject obj = root.getAsJsonObject();
            if (obj.has("enabled")) enabled = obj.get("enabled").getAsBoolean();
            if (obj.has("animationsEnabled")) animationsEnabled = obj.get("animationsEnabled").getAsBoolean();
            if (obj.has("maxDistance")) maxDistance = clampMax(obj.get("maxDistance").getAsFloat());
            if (obj.has("fullVolumeDistance")) fullVolumeDistance = clampFull(obj.get("fullVolumeDistance").getAsFloat(), maxDistance);
            if (obj.has("curve")) {
                try {
                    curve = AttenuationCurve.valueOf(obj.get("curve").getAsString());
                } catch (IllegalArgumentException ignored) {
                    LOGGER.warn("Unknown attenuation curve '{}'; using {}", obj.get("curve").getAsString(), DEFAULT_CURVE);
                    curve = DEFAULT_CURVE;
                }
            }
            if (obj.has("mode")) {
                try {
                    mode = SpatialMode.valueOf(obj.get("mode").getAsString());
                } catch (IllegalArgumentException ignored) {
                    LOGGER.warn("Unknown spatial mode '{}'; using {}", obj.get("mode").getAsString(), DEFAULT_MODE);
                    mode = DEFAULT_MODE;
                }
            }
            LOGGER.info("Loaded spatial settings: enabled={}, maxDistance={}, fullVolume={}, curve={}, mode={}",
                    enabled, maxDistance, fullVolumeDistance, curve, mode);
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("Could not read spatial settings from {}; using defaults", configFile, exception);
        }
    }

    private void persist() {
        if (configFile == null) return;
        try {
            Files.createDirectories(configFile.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("animationsEnabled", animationsEnabled);
            obj.addProperty("maxDistance", maxDistance);
            obj.addProperty("fullVolumeDistance", fullVolumeDistance);
            obj.addProperty("curve", curve.name());
            obj.addProperty("mode", mode.name());
            Path temporary = configFile.resolveSibling(configFile.getFileName() + ".tmp");
            Files.writeString(temporary, obj.toString());
            try {
                Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("Could not persist spatial settings to {}", configFile, exception);
        }
    }

    // ── Validation ────────────────────────────────────────────────────

    private static float clampMax(float value) {
        return Math.max(MIN_MAX_DISTANCE, Math.min(MAX_MAX_DISTANCE, value));
    }

    private static float clampFull(float value, float currentMax) {
        return Math.max(MIN_FULL_VOLUME_DISTANCE, Math.min(currentMax - 1.0f, value));
    }

    // ── Enums ─────────────────────────────────────────────────────────

    public enum AttenuationCurve {
        LINEAR("Linear"),
        SMOOTH("Smooth");

        private final String displayName;

        AttenuationCurve(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() { return displayName; }
    }

    public enum SpatialMode {
        MONO_SPATIAL("Mono 3D", "True 3D point source with full directional panning and HRTF"),
        STEREO("Stereo", "Direct stereo playback with distance volume attenuation"),
        VIRTUAL_STEREO("Virtual Stereo", "Splits stereo into twin left/right 3D speakers");

        private final String displayName;
        private final String description;

        SpatialMode(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String displayName() { return displayName; }
        public String description() { return description; }
    }
}
