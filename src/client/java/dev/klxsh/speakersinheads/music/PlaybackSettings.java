package dev.klxsh.speakersinheads.music;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.klxsh.speakersinheads.SpeakersInHeadsClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Global and per-playback settings for track repetition and shuffle ordering.
 * Ensures non-repeating shuffle cycles without immediate repeats.
 */
public final class PlaybackSettings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile Path configPath;

    public enum RepeatMode {
        OFF("Off"),
        REPEAT_ONE("Repeat One"),
        REPEAT_ALL("Repeat All");

        private final String displayName;

        RepeatMode(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        public RepeatMode next() {
            return switch (this) {
                case OFF -> REPEAT_ALL;
                case REPEAT_ALL -> REPEAT_ONE;
                case REPEAT_ONE -> OFF;
            };
        }
    }

    private static volatile RepeatMode repeatMode = RepeatMode.REPEAT_ALL;
    private static volatile boolean shuffle = false;

    // Non-repeating shuffle state
    private static final List<MusicTrack> shuffleQueue = new ArrayList<>();
    private static final List<MusicTrack> playedHistory = new ArrayList<>();
    private static final Random RANDOM = new Random();

    static {
        try {
            configPath = FabricLoader.getInstance().getConfigDir()
                    .resolve("speakersinheads").resolve("playback.json");
            load();
        } catch (Throwable ignored) {
            // Headless unit test environment without FabricLoader
        }
    }

    private PlaybackSettings() {}

    public static synchronized void initializeForTesting(Path path) {
        configPath = path;
    }

    public static synchronized RepeatMode repeatMode() {
        return repeatMode;
    }

    public static synchronized void setRepeatMode(RepeatMode mode) {
        if (mode != null) {
            repeatMode = mode;
            save();
        }
    }

    public static synchronized void toggleRepeatMode() {
        repeatMode = repeatMode.next();
        save();
    }

    public static synchronized boolean isShuffle() {
        return shuffle;
    }

    public static synchronized void setShuffle(boolean value) {
        shuffle = value;
        shuffleQueue.clear();
        playedHistory.clear();
        save();
    }

    public static synchronized void toggleShuffle() {
        setShuffle(!shuffle);
    }

    /**
     * Determines the next track to play after {@code currentTrack}.
     * Respects {@link RepeatMode} and non-repeating shuffle queue.
     *
     * @param currentTrack the track that just finished or is currently playing
     * @param availableTracks snapshot of available tracks in library
     * @param userTriggered true if user explicitly pressed 'Next', false if triggered naturally on track completion
     * @return next {@link MusicTrack} to play, or {@code null} if playback should stop
     */
    public static synchronized MusicTrack getNextTrack(MusicTrack currentTrack, List<MusicTrack> availableTracks, boolean userTriggered) {
        if (availableTracks == null || availableTracks.isEmpty()) return null;

        // If not user-triggered and repeating one track: repeat it
        if (!userTriggered && repeatMode == RepeatMode.REPEAT_ONE && currentTrack != null) {
            return currentTrack;
        }

        if (shuffle) {
            return getNextShuffleTrack(currentTrack, availableTracks, userTriggered);
        } else {
            return getNextSequentialTrack(currentTrack, availableTracks, userTriggered);
        }
    }

    /**
     * Determines the previous track to play.
     */
    public static synchronized MusicTrack getPreviousTrack(MusicTrack currentTrack, List<MusicTrack> availableTracks) {
        if (availableTracks == null || availableTracks.isEmpty()) return null;

        if (shuffle && !playedHistory.isEmpty()) {
            // Pop the last played track
            MusicTrack prev = playedHistory.remove(playedHistory.size() - 1);
            if (currentTrack != null && currentTrack.equals(prev) && !playedHistory.isEmpty()) {
                prev = playedHistory.remove(playedHistory.size() - 1);
            }
            return prev;
        }

        int currentIndex = currentTrack != null ? availableTracks.indexOf(currentTrack) : -1;
        int prevIndex = currentIndex <= 0 ? availableTracks.size() - 1 : currentIndex - 1;
        return availableTracks.get(prevIndex);
    }

    private static synchronized MusicTrack getNextSequentialTrack(MusicTrack currentTrack, List<MusicTrack> availableTracks, boolean userTriggered) {
        int currentIndex = currentTrack != null ? availableTracks.indexOf(currentTrack) : -1;
        int nextIndex = currentIndex + 1;

        if (nextIndex >= availableTracks.size()) {
            if (repeatMode == RepeatMode.REPEAT_ALL || userTriggered) {
                nextIndex = 0;
            } else {
                // RepeatMode.OFF and natural finish -> stop
                return null;
            }
        }

        MusicTrack next = availableTracks.get(nextIndex);
        if (currentTrack != null) {
            playedHistory.add(currentTrack);
        }
        return next;
    }

    private static synchronized MusicTrack getNextShuffleTrack(MusicTrack currentTrack, List<MusicTrack> availableTracks, boolean userTriggered) {
        if (shuffleQueue.isEmpty()) {
            if (!userTriggered && repeatMode == RepeatMode.OFF && currentTrack != null && !playedHistory.isEmpty()) {
                // In shuffle mode with RepeatMode.OFF, when all tracks have been played once -> stop
                return null;
            }
            resetShuffleQueue(currentTrack, availableTracks);
        }

        if (shuffleQueue.isEmpty()) {
            return currentTrack != null ? currentTrack : availableTracks.get(0);
        }

        MusicTrack next = shuffleQueue.remove(0);
        if (currentTrack != null) {
            playedHistory.add(currentTrack);
        }
        return next;
    }

    public static synchronized void resetShuffleQueue(MusicTrack currentTrack, List<MusicTrack> availableTracks) {
        shuffleQueue.clear();
        List<MusicTrack> all = new ArrayList<>(availableTracks != null ? availableTracks : MusicLibrary.tracks());
        if (all.isEmpty()) return;

        if (currentTrack != null) {
            all.remove(currentTrack);
        }
        Collections.shuffle(all, RANDOM);
        shuffleQueue.addAll(all);
    }

    public static synchronized void resetShuffleQueue(MusicTrack currentTrack) {
        resetShuffleQueue(currentTrack, null);
    }

    public static synchronized void save() {
        if (configPath == null) return;
        try {
            Files.createDirectories(configPath.getParent());
            JsonObject json = new JsonObject();
            json.addProperty("repeatMode", repeatMode.name());
            json.addProperty("shuffle", shuffle);

            try (Writer writer = Files.newBufferedWriter(configPath)) {
                GSON.toJson(json, writer);
            }
        } catch (IOException e) {
            SpeakersInHeadsClient.LOGGER.error("Failed to save playback settings", e);
        }
    }

    public static synchronized void load() {
        if (configPath == null || !Files.exists(configPath)) return;
        try (Reader reader = Files.newBufferedReader(configPath)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            if (json.has("repeatMode")) {
                try {
                    repeatMode = RepeatMode.valueOf(json.get("repeatMode").getAsString());
                } catch (IllegalArgumentException ignored) {}
            }
            if (json.has("shuffle")) {
                shuffle = json.get("shuffle").getAsBoolean();
            }
        } catch (Exception e) {
            SpeakersInHeadsClient.LOGGER.warn("Failed to load playback settings, using defaults", e);
        }
    }
}
