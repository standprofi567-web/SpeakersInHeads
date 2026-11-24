package dev.klxsh.speakersinheads.music;

import dev.klxsh.speakersinheads.ClientPreferences;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.awt.EventQueue;
import java.awt.FileDialog;
import java.awt.Frame;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static dev.klxsh.speakersinheads.SpeakersInHeadsClient.LOGGER;

/** Keeps folder-owned tracks derived from disk and external tracks persisted in the user config. */
public final class MusicLibrary {
    private static volatile List<MusicTrack> tracks = List.of();
    private static volatile Path gameDirectory;
    private static volatile Path musicDirectory;
    private static volatile Path libraryFile;
    private static volatile ScanReport lastScan = new ScanReport(null, 0, 0, 0, 0, "Not scanned yet");
    private static volatile WatchService watchService;
    private static volatile boolean autoRefresh = true;
    private static boolean watcherClosing;
    private static final Set<Path> FOLDER_EXCLUSIONS = new LinkedHashSet<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "SpeakersInHeads-MusicLibrary");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean READY = new AtomicBoolean();
    private static final AtomicInteger SCAN_COUNT = new AtomicInteger();
    private static final ManagedFileReadiness FOLDER_READINESS = new ManagedFileReadiness();
    private static volatile java.time.Instant lastScannedAt;

    private MusicLibrary() {}

    public static void initialize() {
        autoRefresh = Boolean.parseBoolean(ClientPreferences.get("libraryAutoRefresh", "true"));
        gameDirectory = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        musicDirectory = gameDirectory.resolve("music").resolve("speakersinheads");
        libraryFile = FabricLoader.getInstance().getConfigDir().resolve("speakersinheads").resolve("library.json");
        LOGGER.info("Music library game directory: {}; managed music folder: {}; config: {}",
                gameDirectory, musicDirectory, libraryFile);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            CompletableFuture.runAsync(MusicLibrary::persistCurrentExternalTracks, IO)
                    .whenComplete((ignored, error) -> IO.shutdown());
            closeWatcher();
        });
        refresh().thenRun(MusicLibrary::startWatcher);
    }

    /** Does a full disk/config scan on the library worker; the UI reads the latest immutable snapshot. */
    public static CompletableFuture<ScanReport> refresh() {
        SCAN_COUNT.incrementAndGet();
        return CompletableFuture.supplyAsync(MusicLibrary::scanAndMerge, IO)
                .whenComplete((report, error) -> SCAN_COUNT.decrementAndGet());
    }

    public static CompletableFuture<ScanReport> refreshImported(Path file) {
        return CompletableFuture.runAsync(() -> {
            try {
                Path path = file.toAbsolutePath().normalize();
                if (path.getParent().equals(musicDirectory)) FOLDER_READINESS.recordImported(path);
            } catch (IOException ignored) {
                // The ordinary scan will report a file that changed after publication.
            }
        }, IO).thenCompose(ignored -> refresh());
    }

    public static Path getMusicDirectory() {
        return musicDirectory;
    }

    public static java.time.Instant lastScannedAt() { return lastScannedAt; }

    @FunctionalInterface
    interface FolderOpener { void open(Path path) throws IOException; }

    static void openFolder(Path path, FolderOpener opener) throws IOException {
        if (path == null) throw new IOException("The music library is still loading");
        Files.createDirectories(path);
        opener.open(path);
    }

    public static CompletableFuture<Void> openMusicFolder() {
        Path selected = musicDirectory;
        return CompletableFuture.runAsync(() -> {
            try {
                openFolder(selected, SystemFolderOpener::open);
            } catch (IOException | RuntimeException failure) {
                LOGGER.warn("Could not open managed music folder {}", selected, failure);
                throw new java.util.concurrent.CompletionException(failure);
            }
        }, IO);
    }

    public static boolean autoRefresh() { return autoRefresh; }

    public static void setAutoRefresh(boolean enabled) {
        autoRefresh = enabled;
        ClientPreferences.set("libraryAutoRefresh", Boolean.toString(enabled));
        if (enabled) refresh();
    }

    public static List<MusicTrack> tracks() {
        return tracks;
    }

    public static ScanReport lastScan() {
        return lastScan;
    }

    public static boolean isReady() {
        return READY.get();
    }

    public static boolean isScanning() {
        return SCAN_COUNT.get() > 0;
    }

    public static void addExternalFiles(List<Path> paths) {
        CompletableFuture.runAsync(() -> {
            LibraryConfig config = readConfig();
            if (!config.valid() && !backupInvalidConfig()) return;
            List<MusicTrack> external = new ArrayList<>(config.externalTracks());
            for (Path supplied : paths) {
                try {
                    Path path = PathIdentity.normalize(supplied, gameDirectory);
                    if (!isSupported(path) || !Files.isRegularFile(path)) continue;
                    String key = PathIdentity.key(path);
                    if (isManagedFolderPath(path)) {
                        FOLDER_EXCLUSIONS.remove(path);
                        FOLDER_EXCLUSIONS.removeIf(excluded -> PathIdentity.key(excluded).equals(key));
                        continue;
                    }
                    if (external.stream().noneMatch(track -> PathIdentity.key(track.path()).equals(key))) {
                        external.add(new MusicTrack(path, titleFrom(path), true, MusicTrack.Source.EXTERNAL));
                    }
                } catch (RuntimeException exception) {
                    LOGGER.warn("Ignoring invalid imported music path {}", supplied, exception);
                }
            }
            persist(external);
        }, IO).thenCompose(ignored -> refresh());
    }

    /** Remove from the catalog only. Source audio files are never deleted by this operation. */
    public static void removeFromLibrary(MusicTrack track) {
        CompletableFuture.runAsync(() -> {
            LibraryConfig config = readConfig();
            if (!config.valid() && !backupInvalidConfig()) return;
            LibraryConfig updated = withoutTrack(track, config.externalTracks(), FOLDER_EXCLUSIONS);
            persist(updated.externalTracks());
        }, IO).thenCompose(ignored -> refresh());
    }

    /** Physically deletes a selected source file; callers must obtain explicit confirmation first. */
    public static CompletableFuture<Void> deleteFileAfterConfirmation(MusicTrack track) {
        return CompletableFuture.runAsync(() -> {
            try {
                Path path = track.path().toAbsolutePath().normalize();
                if (!Files.isRegularFile(path)) throw new IOException("File no longer exists: " + path);
                Files.delete(path);
                String key = PathIdentity.key(path);
                LibraryConfig config = readConfig();
                List<MusicTrack> external = config.externalTracks().stream()
                        .filter(existing -> !PathIdentity.key(existing.path()).equals(key)).toList();
                if (config.valid() || backupInvalidConfig()) persist(external);
            } catch (IOException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }, IO).thenCompose(ignored -> refresh()).thenApply(ignored -> null);
    }

    public static void chooseFiles() {
        EventQueue.invokeLater(() -> {
            try {
                FileDialog dialog = new FileDialog((Frame) null, "Add music", FileDialog.LOAD);
                dialog.setMultipleMode(true);
                dialog.setFilenameFilter((dir, name) -> isSupported(Path.of(name)));
                dialog.setVisible(true);
                java.io.File[] files = dialog.getFiles();
                dialog.dispose();
                if (files != null) addExternalFiles(java.util.Arrays.stream(files).map(java.io.File::toPath).toList());
            } catch (RuntimeException exception) {
                LOGGER.warn("Could not open the native music file chooser", exception);
            }
        });
    }

    private static ScanReport scanAndMerge() {
        int folderFiles = 0;
        String error = null;
        try {
            Files.createDirectories(musicDirectory);
            LibraryConfig config = readConfig();
            FOLDER_READINESS.forgetMissing();
            FolderScan folderScan = scanFolder(musicDirectory, FOLDER_READINESS);
            folderFiles = folderScan.found();
            error = folderScan.error();

            tracks = mergeTracks(folderScan, config.externalTracks(), musicDirectory, gameDirectory,
                    FOLDER_EXCLUSIONS);
            READY.set(true);
            if (config.valid()) persist(config.externalTracks());
        } catch (IOException | RuntimeException exception) {
            error = exception.getClass().getSimpleName() + ": " + exception.getMessage();
            LOGGER.error("Music library scan failed for {}", musicDirectory, exception);
            READY.set(true);
        }
        int externalTracks = (int) tracks.stream().filter(track -> track.source() == MusicTrack.Source.EXTERNAL).count();
        int available = (int) tracks.stream().filter(MusicTrack::available).count();
        int unavailable = tracks.size() - available;
        ScanReport report = new ScanReport(musicDirectory, folderFiles, externalTracks, available, unavailable, error);
        lastScan = report;
        lastScannedAt = java.time.Instant.now();
        LOGGER.info("Music scan complete: folder={}, folderFiles={}, externalTracks={}, available={}, unavailable={}, error={}",
                report.directory(), report.folderFiles(), report.externalTracks(), report.available(), report.unavailable(), report.error());
        return report;
    }

    static FolderScan scanFolder(Path directory) {
        return scanFolder(directory, null);
    }

    static FolderScan scanFolder(Path directory, ManagedFileReadiness readiness) {
        List<MusicTrack> found = new ArrayList<>();
        String error = null;
        long now = System.currentTimeMillis();
        try (Stream<Path> paths = Files.list(directory)) {
            List<Path> candidates = paths.filter(MusicLibrary::isSupported)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
            for (Path candidate : candidates) {
                try {
                    if (!Files.isRegularFile(candidate)) continue;
                    Path path = candidate.toAbsolutePath().normalize();
                    if (readiness != null && readiness.check(path, now) != ManagedFileReadiness.State.READY) continue;
                    found.add(new MusicTrack(path, titleFrom(path), Files.isReadable(path), MusicTrack.Source.FOLDER));
                } catch (RuntimeException exception) {
                    LOGGER.warn("Could not inspect music file {}", candidate, exception);
                }
            }
        } catch (IOException | RuntimeException exception) {
            error = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        }
        return new FolderScan(List.copyOf(found), error);
    }

    static List<MusicTrack> mergeTracks(FolderScan folderScan, List<MusicTrack> externalTracks,
                                        Path managedDirectory, Path activeGameDirectory, Set<Path> exclusions) {
        Map<String, MusicTrack> merged = new LinkedHashMap<>();
        Set<String> excludedKeys = exclusions.stream().map(PathIdentity::key)
                .collect(java.util.stream.Collectors.toSet());
        for (MusicTrack folderTrack : folderScan.tracks()) {
            Path path = PathIdentity.normalize(folderTrack.path(), activeGameDirectory);
            String key = PathIdentity.key(path);
            if (!excludedKeys.contains(key)) {
                merged.putIfAbsent(key, new MusicTrack(path, folderTrack.title(),
                        Files.isRegularFile(path) && Files.isReadable(path), MusicTrack.Source.FOLDER));
            }
        }
        for (MusicTrack external : externalTracks) {
            Path path = PathIdentity.normalize(external.path(), activeGameDirectory);
            if (PathIdentity.isWithin(path, managedDirectory)) continue;
            String key = PathIdentity.key(path);
            if (merged.containsKey(key)) continue;
            merged.put(key, new MusicTrack(path, external.title(),
                    Files.isRegularFile(path) && Files.isReadable(path), MusicTrack.Source.EXTERNAL));
        }
        return List.copyOf(merged.values());
    }

    static LibraryConfig withoutTrack(MusicTrack track, List<MusicTrack> externalTracks, Set<Path> exclusions) {
        String key = PathIdentity.key(track.path());
        if (track.source() == MusicTrack.Source.FOLDER) {
            exclusions.add(track.path().toAbsolutePath().normalize());
        }
        List<MusicTrack> remaining = externalTracks.stream()
                .filter(existing -> !PathIdentity.key(existing.path()).equals(key)).toList();
        return new LibraryConfig(remaining, true);
    }

    private static LibraryConfig readConfig() {
        return readConfig(libraryFile, gameDirectory, musicDirectory, FOLDER_EXCLUSIONS);
    }

    static LibraryConfig readConfig(Path configFile, Path activeGameDirectory, Path managedDirectory,
                                    Set<Path> exclusionsOut) {
        List<MusicTrack> external = new ArrayList<>();
        boolean valid = true;
        if (!Files.isRegularFile(configFile)) return new LibraryConfig(external, valid);
        try {
            JsonElement root = JsonParser.parseString(Files.readString(configFile));
            JsonArray entries;
            if (root.isJsonArray()) {
                entries = root.getAsJsonArray(); // legacy top-level array format
            } else if (root.isJsonObject()) {
                JsonObject object = root.getAsJsonObject();
                entries = object.has("externalTracks") && object.get("externalTracks").isJsonArray()
                        ? object.getAsJsonArray("externalTracks")
                        : object.has("tracks") && object.get("tracks").isJsonArray()
                            ? object.getAsJsonArray("tracks") : new JsonArray();
                JsonArray exclusions = object.has("excludedFolderTracks") && object.get("excludedFolderTracks").isJsonArray()
                        ? object.getAsJsonArray("excludedFolderTracks")
                        : object.has("excluded") && object.get("excluded").isJsonArray()
                            ? object.getAsJsonArray("excluded") : new JsonArray();
                if (!exclusions.isEmpty()) {
                    for (JsonElement item : exclusions) {
                        try {
                            exclusionsOut.add(PathIdentity.normalize(Path.of(item.getAsString()), activeGameDirectory));
                        } catch (RuntimeException exception) {
                            valid = false;
                            LOGGER.warn("Ignoring malformed managed-folder exclusion in {}", configFile, exception);
                        }
                    }
                }
            } else {
                return new LibraryConfig(external, false);
            }
            Map<String, MusicTrack> unique = new LinkedHashMap<>();
            for (JsonElement item : entries) {
                try {
                    if (!item.isJsonObject()) throw new IllegalArgumentException("entry is not an object");
                    JsonObject object = item.getAsJsonObject();
                    Path path = PathIdentity.normalize(Path.of(object.get("path").getAsString()), activeGameDirectory);
                    if (!isSupported(path) || PathIdentity.isWithin(path, managedDirectory)) continue;
                    String key = PathIdentity.key(path);
                    String title = object.has("title") ? object.get("title").getAsString() : titleFrom(path);
                    boolean available = Files.isRegularFile(path) && Files.isReadable(path);
                    unique.putIfAbsent(key, new MusicTrack(path, title, available, MusicTrack.Source.EXTERNAL));
                } catch (RuntimeException exception) {
                    valid = false;
                    LOGGER.warn("Ignoring malformed external music library record", exception);
                }
            }
            external.addAll(unique.values());
        } catch (IOException | RuntimeException exception) {
            valid = false;
            LOGGER.error("Could not read music library config {}; preserving it and scanning folder", configFile, exception);
        }
        return new LibraryConfig(List.copyOf(external), valid);
    }

    private static void persistCurrentExternalTracks() {
        persist(tracks.stream().filter(track -> track.source() == MusicTrack.Source.EXTERNAL).toList());
    }

    private static boolean backupInvalidConfig() {
        if (!Files.isRegularFile(libraryFile)) return true;
        Path backup = libraryFile.resolveSibling("library.json.corrupt-" + System.currentTimeMillis() + ".bak");
        try {
            Files.copy(libraryFile, backup);
            LOGGER.warn("Backed up unreadable music library config to {} before rewriting it", backup);
            return true;
        } catch (IOException exception) {
            LOGGER.error("Refusing to overwrite unreadable music library config {}; backup failed", libraryFile, exception);
            return false;
        }
    }

    private static synchronized void persist(List<MusicTrack> externalTracks) {
        writeConfig(externalTracks, libraryFile, gameDirectory, musicDirectory, FOLDER_EXCLUSIONS);
    }

    static boolean writeConfig(List<MusicTrack> externalTracks, Path configFile, Path activeGameDirectory,
                               Path managedDirectory, Set<Path> exclusions) {
        try {
            Files.createDirectories(configFile.getParent());
            JsonObject root = new JsonObject();
            JsonArray external = new JsonArray();
            Map<String, MusicTrack> unique = new LinkedHashMap<>();
            for (MusicTrack track : externalTracks) {
                Path path = PathIdentity.normalize(track.path(), activeGameDirectory);
                if (PathIdentity.isWithin(path, managedDirectory)) continue;
                unique.putIfAbsent(PathIdentity.key(path), new MusicTrack(path, track.title(),
                        Files.isRegularFile(path) && Files.isReadable(path), MusicTrack.Source.EXTERNAL));
            }
            unique.values().forEach(track -> {
                JsonObject item = new JsonObject();
                item.addProperty("path", track.path().toString());
                item.addProperty("title", track.title());
                external.add(item);
            });
            JsonArray excluded = new JsonArray();
            exclusions.forEach(path -> excluded.add(path.toString()));
            root.add("externalTracks", external);
            root.add("excludedFolderTracks", excluded);
            Path temporary = configFile.resolveSibling(configFile.getFileName() + ".tmp");
            Files.writeString(temporary, root.toString());
            try {
                Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnsupported) {
                Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("Could not persist music library config to {}", configFile, exception);
            return false;
        }
    }

    private static boolean isManagedFolderPath(Path path) {
        return PathIdentity.isWithin(path, musicDirectory);
    }

    public static boolean isSupported(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) return false;
        String name = fileName.toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".mp3") || name.endsWith(".ogg") || name.endsWith(".wav");
    }

    private static String titleFrom(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) return path.toString();
        String name = fileName.toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static synchronized void startWatcher() {
        if (watcherClosing || watchService != null || IO.isShutdown()) return;
        WatchService watcher = null;
        try {
            watcher = FileSystems.getDefault().newWatchService();
            musicDirectory.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
            watchService = watcher;
            WatchService registered = watcher;
            Thread thread = new Thread(() -> watchDirectory(registered), "SpeakersInHeads-MusicWatcher");
            thread.setDaemon(true);
            thread.start();
        } catch (IOException | RuntimeException exception) {
            watchService = null;
            if (watcher != null) {
                try { watcher.close(); } catch (IOException closeFailure) { exception.addSuppressed(closeFailure); }
            }
            LOGGER.warn("Could not watch music folder {}; use Refresh to rescan", musicDirectory, exception);
        }
    }

    private static void watchDirectory(WatchService watcher) {
        try {
            while (!IO.isShutdown()) {
                WatchKey key = watcher.poll(1, TimeUnit.SECONDS);
                if (key == null) continue;
                boolean changed = consumeEvents(key);
                if (!key.reset()) break;
                if (changed) {
                    WatchKey pending;
                    while ((pending = watcher.poll(1500, TimeUnit.MILLISECONDS)) != null) {
                        changed |= consumeEvents(pending);
                        if (!pending.reset()) break;
                    }
                    if (changed && autoRefresh) refresh();
                }
            }
        } catch (java.nio.file.ClosedWatchServiceException closed) {
            // Expected when the client shuts down.
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException exception) {
            if (!IO.isShutdown()) LOGGER.warn("Music folder watcher stopped; Refresh remains available", exception);
        }
    }

    private static boolean consumeEvents(WatchKey key) {
        boolean changed = false;
        for (var event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW
                    || event.context() instanceof Path path && isSupported(path)) changed = true;
        }
        return changed;
    }

    private static synchronized void closeWatcher() {
        watcherClosing = true;
        WatchService watcher = watchService;
        watchService = null;
        if (watcher != null) {
            try {
                watcher.close();
            } catch (IOException exception) {
                LOGGER.debug("Music watcher was already closed", exception);
            }
        }
    }

    record LibraryConfig(List<MusicTrack> externalTracks, boolean valid) {}
    record FolderScan(List<MusicTrack> tracks, String error) {
        int found() { return tracks.size(); }
    }

    public record ScanReport(Path directory, int folderFiles, int externalTracks, int available,
                             int unavailable, String error) {}
}
