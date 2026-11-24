package dev.klxsh.speakersinheads;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

public final class ClientPreferences {
    private static Path file;
    private static final Properties VALUES = new Properties();

    private ClientPreferences() {}

    public static synchronized void initialize() {
        file = FabricLoader.getInstance().getConfigDir().resolve("speakersinheads/client.properties");
        if (!Files.isRegularFile(file)) return;
        try (var input = Files.newInputStream(file)) {
            VALUES.load(input);
        } catch (IOException | IllegalArgumentException failure) {
            SpeakersInHeadsClient.LOGGER.warn("Could not load client preferences");
        }
    }

    public static synchronized String get(String key, String fallback) {
        return VALUES.getProperty(key, fallback);
    }

    public static float getUnit(String key, float fallback) {
        try {
            float value = Float.parseFloat(get(key, Float.toString(fallback)));
            return Float.isFinite(value) ? Math.clamp(value, 0f, 1f) : fallback;
        } catch (NumberFormatException malformed) {
            return fallback;
        }
    }

    public static synchronized void set(String key, String value) {
        VALUES.setProperty(key, value);
        if (file == null) return;
        Path temp = file.resolveSibling("client.properties.tmp");
        try {
            Files.createDirectories(file.getParent());
            try (var output = Files.newOutputStream(temp)) {
                VALUES.store(output, "SpeakersInHeads client preferences");
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            SpeakersInHeadsClient.LOGGER.warn("Could not save client preferences");
        }
    }
}
