package dev.klxsh.speakersinheads.music;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicLibraryTest {
    @TempDir
    Path tempDirectory;

    @Test
    void acceptsSupportedExtensionsWithoutCaseSensitivity() {
        assertTrue(MusicLibrary.isSupported(Path.of("song.mp3")));
        assertTrue(MusicLibrary.isSupported(Path.of("song.OGG")));
        assertTrue(MusicLibrary.isSupported(Path.of("song.Wav")));
    }

    @Test
    void rejectsUnsupportedAndExtensionlessFiles() {
        assertFalse(MusicLibrary.isSupported(Path.of("cover.png")));
        assertFalse(MusicLibrary.isSupported(Path.of("recording")));
    }

    @Test
    void scansMediaFilesAndSkipsUnsupportedFiles() throws IOException {
        Files.createFile(tempDirectory.resolve("capsize.mp3"));
        Files.createFile(tempDirectory.resolve("session.OGG"));
        Files.createFile(tempDirectory.resolve("voice.Wav"));
        Files.createFile(tempDirectory.resolve("cover.png"));

        MusicLibrary.FolderScan scan = MusicLibrary.scanFolder(tempDirectory);

        assertEquals(null, scan.error());
        assertEquals(3, scan.found());
        assertEquals(3, scan.tracks().size());
        assertEquals(3, new HashSet<>(scan.tracks().stream().map(track -> PathIdentity.key(track.path())).toList()).size());
    }

    @Test
    void reportsMissingFolderInsteadOfSilentlyReturningEmpty() {
        MusicLibrary.FolderScan scan = MusicLibrary.scanFolder(tempDirectory.resolve("missing"));

        assertFalse(scan.error() == null);
        assertTrue(scan.tracks().isEmpty());
    }

    @Test
    void freshScanReflectsAddedDeletedAndRenamedFolderFiles() throws IOException {
        assertEquals(0, MusicLibrary.scanFolder(tempDirectory).found());
        Path added = Files.createFile(tempDirectory.resolve("capsize.mp3"));
        assertEquals(1, MusicLibrary.scanFolder(tempDirectory).found());
        Path renamed = Files.move(added, tempDirectory.resolve("renamed.mp3"));
        MusicLibrary.FolderScan afterRename = MusicLibrary.scanFolder(tempDirectory);
        assertEquals(1, afterRename.found());
        assertEquals(renamed.toAbsolutePath().normalize(), afterRename.tracks().getFirst().path());
        Files.delete(renamed);
        assertEquals(0, MusicLibrary.scanFolder(tempDirectory).found());
    }

    @Test
    void preservesMissingExternalRecordAsUnavailable() throws IOException {
        Path removed = Files.createFile(tempDirectory.resolve("removed.wav"));
        MusicTrack previous = new MusicTrack(removed, "removed", true, MusicTrack.Source.EXTERNAL);
        Files.delete(removed);
        MusicTrack missing = new MusicTrack(previous.path(), previous.title(),
                Files.isRegularFile(previous.path()) && Files.isReadable(previous.path()), MusicTrack.Source.EXTERNAL);

        assertFalse(missing.available());
        assertEquals(previous.path(), missing.path());
    }

    @Test
    void identifiesDifferentDirectoriesWithTheSameFileNameSeparately() throws IOException {
        Path firstDir = Files.createDirectory(tempDirectory.resolve("first"));
        Path secondDir = Files.createDirectory(tempDirectory.resolve("second"));
        Path first = Files.createFile(firstDir.resolve("song.mp3"));
        Path second = Files.createFile(secondDir.resolve("song.mp3"));

        assertNotEquals(PathIdentity.key(first), PathIdentity.key(second));
    }

    @Test
    void resolvesRelativePathsFromTheActiveGameDirectory() {
        Path relative = Path.of("music", "song.mp3");
        assertEquals(tempDirectory.resolve(relative).toAbsolutePath().normalize(),
                PathIdentity.normalize(relative, tempDirectory));
    }

    @Test
    void mergePrefersManagedFolderAndDeduplicatesExternalAliases() throws IOException {
        Path managed = Files.createDirectory(tempDirectory.resolve("music"));
        Path song = Files.createFile(managed.resolve("same.wav"));
        Path alias = managed.resolve(".").resolve("same.wav");
        MusicLibrary.FolderScan scan = MusicLibrary.scanFolder(managed);
        List<MusicTrack> merged = MusicLibrary.mergeTracks(scan, List.of(
                        new MusicTrack(alias, "legacy row", true, MusicTrack.Source.EXTERNAL)),
                managed, tempDirectory, Set.of());

        assertEquals(1, merged.size());
        assertEquals(MusicTrack.Source.FOLDER, merged.getFirst().source());
        assertEquals(song.toAbsolutePath().normalize(), merged.getFirst().path());
    }

    @Test
    void missingExternalPathBecomesAvailableWhenFileReturns() throws IOException {
        Path externalDirectory = Files.createDirectory(tempDirectory.resolve("external"));
        Path missing = externalDirectory.resolve("capsize.mp3");
        MusicTrack saved = new MusicTrack(missing, "capsize", false, MusicTrack.Source.EXTERNAL);
        MusicLibrary.FolderScan emptyFolder = MusicLibrary.scanFolder(tempDirectory.resolve("managed"));

        assertFalse(MusicLibrary.mergeTracks(emptyFolder, List.of(saved), tempDirectory.resolve("managed"),
                tempDirectory, Set.of()).getFirst().available());
        Files.createFile(missing);
        assertTrue(MusicLibrary.mergeTracks(emptyFolder, List.of(saved), tempDirectory.resolve("managed"),
                tempDirectory, Set.of()).getFirst().available());
    }

    @Test
    void excludedManagedFilesStayHiddenUntilExclusionIsCleared() throws IOException {
        Path managed = Files.createDirectory(tempDirectory.resolve("managed"));
        Path song = Files.createFile(managed.resolve("song.ogg"));
        MusicLibrary.FolderScan scan = MusicLibrary.scanFolder(managed);

        assertTrue(MusicLibrary.mergeTracks(scan, List.of(), managed, tempDirectory,
                Set.of(song)).isEmpty());
        assertEquals(1, MusicLibrary.mergeTracks(scan, List.of(), managed, tempDirectory,
                Set.of()).size());
    }

    @Test
    void readsLegacyTracksConfigAndPreservesUnavailablePaths() throws IOException {
        Path config = tempDirectory.resolve("config/library.json");
        Path missing = tempDirectory.resolve("gone.mp3");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "{\"tracks\":[{\"path\":\"" + missing.toString().replace("\\", "\\\\")
                + "\",\"title\":\"Saved title\"}]}");

        MusicLibrary.LibraryConfig loaded = MusicLibrary.readConfig(config, tempDirectory,
                tempDirectory.resolve("music"), new HashSet<>());

        assertTrue(loaded.valid());
        assertEquals(1, loaded.externalTracks().size());
        assertFalse(loaded.externalTracks().getFirst().available());
        assertEquals("Saved title", loaded.externalTracks().getFirst().title());
    }

    @Test
    void savesAndReloadsExternalPathsAndManagedFolderExclusions() throws IOException {
        Path managed = Files.createDirectory(tempDirectory.resolve("music"));
        Path external = Files.createFile(tempDirectory.resolve("external.wav"));
        Path excluded = managed.resolve("excluded.wav");
        Path config = tempDirectory.resolve("config/library.json");
        MusicTrack track = new MusicTrack(external, "My track", true, MusicTrack.Source.EXTERNAL);
        assertTrue(MusicLibrary.writeConfig(List.of(track), config, tempDirectory, managed, Set.of(excluded)));

        Set<Path> loadedExclusions = new HashSet<>();
        MusicLibrary.LibraryConfig loaded = MusicLibrary.readConfig(config, tempDirectory, managed, loadedExclusions);

        assertTrue(loaded.valid());
        assertEquals(1, loaded.externalTracks().size());
        assertEquals(external.toAbsolutePath().normalize(), loaded.externalTracks().getFirst().path());
        assertEquals(Set.of(excluded.toAbsolutePath().normalize()), loadedExclusions);
    }

    @Test
    void removingExternalEntryDoesNotDeleteItsSourceFile() throws IOException {
        Path source = Files.createFile(tempDirectory.resolve("keep-this.mp3"));
        MusicTrack track = new MusicTrack(source, "keep-this", true, MusicTrack.Source.EXTERNAL);

        MusicLibrary.LibraryConfig remaining = MusicLibrary.withoutTrack(track, List.of(track), new HashSet<>());

        assertTrue(remaining.externalTracks().isEmpty());
        assertTrue(Files.isRegularFile(source));
    }
}
