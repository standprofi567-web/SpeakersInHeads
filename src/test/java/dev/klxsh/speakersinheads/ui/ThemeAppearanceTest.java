package dev.klxsh.speakersinheads.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemeAppearanceTest {
    @Test
    void textAndPrimaryActionsHaveReadableContrastAcrossPalettes() {
        for (var theme : ThemeAppearance.Theme.values()) {
            for (int surface : new int[]{0, 1, 2, 3}) {
                assertTrue(contrast(theme.color(5), theme.color(surface)) >= 4.5, theme.name() + "/" + surface);
            }
            assertTrue(contrast(theme.color(6), theme.color(0)) >= 4.5, theme.name());
            assertTrue(contrast(theme.color(7), theme.color(0)) >= 4.5, theme.name());
            assertTrue(contrast(theme.color(9), theme.color(0)) >= 4.5, theme.name());
        }
    }

    private static double contrast(int a, int b) {
        double one = luminance(a), two = luminance(b);
        return (Math.max(one, two) + 0.05) / (Math.min(one, two) + 0.05);
    }

    private static double luminance(int color) {
        return 0.2126 * channel(color >> 16 & 255) + 0.7152 * channel(color >> 8 & 255) + 0.0722 * channel(color & 255);
    }

    private static double channel(int encoded) {
        double value = encoded / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
