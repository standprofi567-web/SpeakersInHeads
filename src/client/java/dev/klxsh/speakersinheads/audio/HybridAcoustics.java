package dev.klxsh.speakersinheads.audio;

import dev.klxsh.speakersinheads.ClientPreferences;

/** Local ownership of direct acoustic effects, independent of an SPR reflection send. */
public final class HybridAcoustics {
    private static volatile float wall = 1f;
    private static volatile float water = 1f;

    private HybridAcoustics() {}

    public static void initialize() {
        wall = load("wallStrength");
        water = load("underwaterStrength");
    }

    private static float load(String key) {
        try {
            return bounded(Float.parseFloat(ClientPreferences.get(key, "1.0")));
        } catch (NumberFormatException ignored) {
            return 1f;
        }
    }

    private static float bounded(float value) {
        return Float.isFinite(value) ? Math.clamp(value, 0f, 1f) : 1f;
    }

    public static float wall() { return wall; }
    public static float water() { return water; }

    public static void setWall(float value) {
        wall = bounded(value);
        ClientPreferences.set("wallStrength", Float.toString(wall));
    }

    public static void setWater(float value) {
        water = bounded(value);
        ClientPreferences.set("underwaterStrength", Float.toString(water));
    }
}
