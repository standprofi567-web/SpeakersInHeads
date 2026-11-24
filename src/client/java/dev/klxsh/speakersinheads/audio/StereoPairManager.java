package dev.klxsh.speakersinheads.audio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static dev.klxsh.speakersinheads.SpeakersInHeadsClient.LOGGER;

/**
 * Manages physical stereo pairs between player heads, including configuration persistence.
 */
public final class StereoPairManager {
    private static final Map<UUID, PhysicalStereoPair> PAIRS = new ConcurrentHashMap<>();
    private static volatile Path configFile;

    private StereoPairManager() {}

    public static void initialize() {
        try {
            configFile = FabricLoader.getInstance().getConfigDir()
                    .resolve("speakersinheads").resolve("stereo_pairs.json");
            load();
        } catch (Throwable ignored) {
            // Environment without FabricLoader (e.g. headless unit tests)
        }
    }

    public static void initializeForTesting(Path path) {
        configFile = path;
        load();
    }

    public static void clearForTesting() {
        PAIRS.clear();
    }

    public static Optional<PhysicalStereoPair> findPairFor(Identifier dimension, BlockPos pos) {
        if (dimension == null || pos == null) return Optional.empty();
        return PAIRS.values().stream()
                .filter(pair -> pair.contains(dimension, pos))
                .findFirst();
    }

    public static List<PhysicalStereoPair> allPairs() {
        return List.copyOf(PAIRS.values());
    }

    public static PhysicalStereoPair createPair(String name, Identifier dimension, BlockPos leftPos, BlockPos rightPos) {
        // Disband any existing pairs that include either of these blocks
        unlink(dimension, leftPos);
        unlink(dimension, rightPos);

        PhysicalStereoPair pair = new PhysicalStereoPair(UUID.randomUUID(), name, dimension, leftPos, rightPos);
        PAIRS.put(pair.id(), pair);
        persist();
        LOGGER.info("Created physical stereo pair '{}' between {} and {} in {}",
                pair.name(), leftPos, rightPos, dimension);
        return pair;
    }

    public static boolean unlink(UUID id) {
        PhysicalStereoPair removed = PAIRS.remove(id);
        if (removed != null) {
            persist();
            LOGGER.info("Unlinked physical stereo pair '{}' ({})", removed.name(), id);
            return true;
        }
        return false;
    }

    public static boolean unlink(Identifier dimension, BlockPos pos) {
        Optional<PhysicalStereoPair> pair = findPairFor(dimension, pos);
        if (pair.isPresent()) {
            return unlink(pair.get().id());
        }
        return false;
    }

    public static void save() {
        persist();
    }

    private static synchronized void load() {
        PAIRS.clear();
        if (configFile == null || !Files.isRegularFile(configFile)) return;

        try {
            JsonElement root = JsonParser.parseString(Files.readString(configFile));
            if (!root.isJsonArray()) return;

            JsonArray array = root.getAsJsonArray();
            for (JsonElement item : array) {
                if (!item.isJsonObject()) continue;
                JsonObject obj = item.getAsJsonObject();

                UUID id = obj.has("id") ? UUID.fromString(obj.get("id").getAsString()) : UUID.randomUUID();
                String name = obj.has("name") ? obj.get("name").getAsString() : "Stereo Pair";
                Identifier dim = Identifier.parse(obj.get("dimension").getAsString());

                JsonObject leftObj = obj.getAsJsonObject("leftPos");
                BlockPos left = new BlockPos(leftObj.get("x").getAsInt(), leftObj.get("y").getAsInt(), leftObj.get("z").getAsInt());

                JsonObject rightObj = obj.getAsJsonObject("rightPos");
                BlockPos right = new BlockPos(rightObj.get("x").getAsInt(), rightObj.get("y").getAsInt(), rightObj.get("z").getAsInt());

                boolean swapped = obj.has("swapped") && obj.get("swapped").getAsBoolean();
                float leftVol = obj.has("leftVolume") ? obj.get("leftVolume").getAsFloat() : 1.0f;
                float rightVol = obj.has("rightVolume") ? obj.get("rightVolume").getAsFloat() : 1.0f;
                float pairMaster = obj.has("pairMasterVolume") ? obj.get("pairMasterVolume").getAsFloat() : 1.0f;

                PhysicalStereoPair pair = new PhysicalStereoPair(id, name, dim, left, right, swapped, leftVol, rightVol, pairMaster);
                PAIRS.put(id, pair);
            }
            LOGGER.info("Loaded {} physical stereo pairs from {}", PAIRS.size(), configFile);
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("Could not load stereo pairs configuration from {}", configFile, exception);
        }
    }

    private static synchronized void persist() {
        if (configFile == null) return;

        try {
            Files.createDirectories(configFile.getParent());
            JsonArray array = new JsonArray();

            for (PhysicalStereoPair pair : PAIRS.values()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("id", pair.id().toString());
                obj.addProperty("name", pair.name());
                obj.addProperty("dimension", pair.dimension().toString());

                JsonObject left = new JsonObject();
                left.addProperty("x", pair.leftPos().getX());
                left.addProperty("y", pair.leftPos().getY());
                left.addProperty("z", pair.leftPos().getZ());
                obj.add("leftPos", left);

                JsonObject right = new JsonObject();
                right.addProperty("x", pair.rightPos().getX());
                right.addProperty("y", pair.rightPos().getY());
                right.addProperty("z", pair.rightPos().getZ());
                obj.add("rightPos", right);

                obj.addProperty("swapped", pair.isSwapped());
                obj.addProperty("leftVolume", pair.leftVolume());
                obj.addProperty("rightVolume", pair.rightVolume());
                obj.addProperty("pairMasterVolume", pair.pairMasterVolume());

                array.add(obj);
            }

            Path temporary = configFile.resolveSibling(configFile.getFileName() + ".tmp");
            Files.writeString(temporary, array.toString());
            try {
                Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("Could not persist stereo pairs configuration to {}", configFile, exception);
        }
    }
}
