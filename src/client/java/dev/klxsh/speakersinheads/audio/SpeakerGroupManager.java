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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static dev.klxsh.speakersinheads.SpeakersInHeadsClient.LOGGER;

/**
 * Manages arbitrary multi-head speaker groups, persistence to config/speakersinheads/speaker_groups.json.
 */
public final class SpeakerGroupManager {
    private static final Map<UUID, SpeakerGroup> GROUPS = new ConcurrentHashMap<>();
    private static volatile Path configFile;

    private SpeakerGroupManager() {}

    public static void initialize() {
        try {
            configFile = FabricLoader.getInstance().getConfigDir()
                    .resolve("speakersinheads").resolve("speaker_groups.json");
            load();
        } catch (Throwable ignored) {
            // Test or headless environment without FabricLoader
        }
    }

    public static void initializeForTesting(Path path) {
        configFile = path;
        load();
    }

    public static void clearForTesting() {
        GROUPS.clear();
    }

    public static Optional<SpeakerGroup> findGroupFor(Identifier dimension, BlockPos pos) {
        if (dimension == null || pos == null) return Optional.empty();
        return GROUPS.values().stream()
                .filter(group -> group.contains(dimension, pos))
                .findFirst();
    }

    public static Optional<SpeakerGroup> getGroup(UUID id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(GROUPS.get(id));
    }

    public static List<SpeakerGroup> allGroups() {
        return List.copyOf(GROUPS.values());
    }

    public static SpeakerGroup createGroup(String name, Identifier dimension, Set<BlockPos> members) {
        // Disband any existing groups containing these heads
        if (members != null) {
            for (BlockPos pos : members) {
                removeFromAnyGroup(dimension, pos);
            }
        }

        SpeakerGroup group = new SpeakerGroup(UUID.randomUUID(), name, dimension, members);
        GROUPS.put(group.id(), group);
        persist();
        LOGGER.info("Created speaker group '{}' with {} members in {}", group.name(), group.size(), dimension);
        return group;
    }

    public static boolean deleteGroup(UUID id) {
        SpeakerGroup removed = GROUPS.remove(id);
        if (removed != null) {
            persist();
            LOGGER.info("Deleted speaker group '{}' ({})", removed.name(), id);
            return true;
        }
        return false;
    }

    public static boolean removeFromAnyGroup(Identifier dimension, BlockPos pos) {
        Optional<SpeakerGroup> group = findGroupFor(dimension, pos);
        if (group.isPresent()) {
            SpeakerGroup g = group.get();
            boolean removed = g.removeMember(pos);
            if (g.size() == 0) {
                deleteGroup(g.id());
            } else {
                persist();
            }
            return removed;
        }
        return false;
    }

    public static void save() {
        persist();
    }

    private static synchronized void load() {
        GROUPS.clear();
        if (configFile == null || !Files.isRegularFile(configFile)) return;

        try {
            String json = Files.readString(configFile);
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) return;

            for (JsonElement elem : root.getAsJsonArray()) {
                if (!elem.isJsonObject()) continue;
                JsonObject obj = elem.getAsJsonObject();

                UUID id = UUID.fromString(obj.get("id").getAsString());
                String name = obj.get("name").getAsString();
                Identifier dim = Identifier.parse(obj.get("dimension").getAsString());
                float volume = obj.has("volume") ? obj.get("volume").getAsFloat() : 1.0f;

                Set<BlockPos> members = new HashSet<>();
                if (obj.has("members") && obj.get("members").isJsonArray()) {
                    for (JsonElement mElem : obj.getAsJsonArray("members")) {
                        JsonObject mObj = mElem.getAsJsonObject();
                        members.add(new BlockPos(
                                mObj.get("x").getAsInt(),
                                mObj.get("y").getAsInt(),
                                mObj.get("z").getAsInt()
                        ));
                    }
                }

                GROUPS.put(id, new SpeakerGroup(id, name, dim, members, volume));
            }
            LOGGER.info("Loaded {} speaker groups from {}", GROUPS.size(), configFile);
        } catch (Exception exception) {
            LOGGER.warn("Failed to load speaker groups from {}: {}", configFile, exception.getMessage());
        }
    }

    private static synchronized void persist() {
        if (configFile == null) return;

        try {
            Files.createDirectories(configFile.getParent());
            JsonArray array = new JsonArray();

            for (SpeakerGroup group : GROUPS.values()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("id", group.id().toString());
                obj.addProperty("name", group.name());
                obj.addProperty("dimension", group.dimension().toString());
                obj.addProperty("volume", group.volume());

                JsonArray membersArray = new JsonArray();
                for (BlockPos pos : group.members()) {
                    JsonObject posObj = new JsonObject();
                    posObj.addProperty("x", pos.getX());
                    posObj.addProperty("y", pos.getY());
                    posObj.addProperty("z", pos.getZ());
                    membersArray.add(posObj);
                }
                obj.add("members", membersArray);
                array.add(obj);
            }

            Path tempFile = configFile.resolveSibling(configFile.getFileName() + ".tmp");
            Files.writeString(tempFile, array.toString());
            Files.move(tempFile, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            LOGGER.error("Failed to save speaker groups to {}: {}", configFile, exception.getMessage());
        }
    }
}
