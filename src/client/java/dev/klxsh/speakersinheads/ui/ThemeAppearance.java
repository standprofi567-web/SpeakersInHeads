package dev.klxsh.speakersinheads.ui;

import dev.klxsh.speakersinheads.ClientPreferences;

public final class ThemeAppearance {
    enum Theme {
        GRAPHITE(0xff202225, 0xff2a2e33, 0xff343941, 0xff475361, 0xff292c30, 0xfff4f6f8, 0xffb4c0cc, 0xff9ac5f0, 0xff536374, 0xffffa49d),
        AMETHYST(0xff21192c, 0xff30213d, 0xff412d50, 0xff593a71, 0xff2d2438, 0xfff6edff, 0xffc6afd6, 0xffe5b5ff, 0xff6e4d82, 0xffff9eac),
        AMBER(0xff211c17, 0xff322820, 0xff423327, 0xff59422d, 0xff302720, 0xfffff2e5, 0xffd4bcaa, 0xffffbd76, 0xff765439, 0xffffab91),
        FROST(0xffe8f0f7, 0xfff5faff, 0xffd8e7f3, 0xffc2d9ec, 0xffe4ebf2, 0xff142737, 0xff42586b, 0xff194d75, 0xff829cb2, 0xff942929);

        private final int[] colors;
        Theme(int... colors) { this.colors = colors; }
        int color(int index) { return colors[index]; }
    }

    enum Motion { FULL, REDUCED, OFF }

    private static Theme current = Theme.GRAPHITE;
    private static Theme previous = current;
    private static Motion motion = Motion.FULL;
    private static long changed;
    private static final int[] transitionStart = new int[Theme.GRAPHITE.colors.length];
    private static boolean transitioning;

    private ThemeAppearance() {}

    public static void initialize() {
        try { current = Theme.valueOf(ClientPreferences.get("theme", "GRAPHITE")); }
        catch (IllegalArgumentException ignored) { current = Theme.GRAPHITE; }
        previous = current;
        try { motion = Motion.valueOf(ClientPreferences.get("motion", "FULL")); }
        catch (IllegalArgumentException ignored) { motion = Motion.FULL; }
    }

    static Theme theme() { return current; }
    static Motion motion() { return motion; }

    static void setTheme(Theme next) {
        if (next == current) return;
        for (int i = 0; i < transitionStart.length; i++) {
            transitionStart[i] = color(i);
        }
        previous = current;
        current = next;
        changed = System.nanoTime();
        transitioning = motion != Motion.OFF;
        ClientPreferences.set("theme", next.name());
    }

    static void setMotion(Motion next) {
        motion = next;
        if (next == Motion.OFF) transitioning = false;
        ClientPreferences.set("motion", next.name());
    }

    static long durationNanos() {
        return switch (motion) {
            case FULL -> 180_000_000L;
            case REDUCED -> 75_000_000L;
            case OFF -> 0L;
        };
    }

    private static float progress() {
        long duration = durationNanos();
        if (!transitioning || duration == 0) return 1;
        return Math.clamp((System.nanoTime() - changed) / (float) duration, 0f, 1f);
    }

    static int color(int index) {
        float w = progress();
        // Switch foreground and surface together between light/dark to avoid a frame of gray-on-gray.
        if ((previous == Theme.FROST) != (current == Theme.FROST)) return current.color(index);
        return w >= 1 ? current.color(index) : mix(transitionStart[index], current.color(index), w);
    }

    static int mix(int a, int b, float w) {
        int r = Math.round((a >> 16 & 255) * (1 - w) + (b >> 16 & 255) * w);
        int g = Math.round((a >> 8 & 255) * (1 - w) + (b >> 8 & 255) * w);
        int blue = Math.round((a & 255) * (1 - w) + (b & 255) * w);
        return 0xff000000 | r << 16 | g << 8 | blue;
    }
}
