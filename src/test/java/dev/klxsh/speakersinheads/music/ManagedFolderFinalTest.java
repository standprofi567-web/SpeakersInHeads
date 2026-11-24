package dev.klxsh.speakersinheads.music;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ManagedFolderFinalTest {
    @TempDir Path temp;

    @Test
    void openerUsesTheSuppliedProfileLibraryAndCreatesItBeforeOpening() throws Exception {
        Path library = temp.resolve("Melon profile").resolve("music").resolve("speakersinheads");
        var opened = new AtomicReference<Path>();
        MusicLibrary.openFolder(library, actual -> {
            assertTrue(Files.isDirectory(actual));
            opened.set(actual);
        });
        assertEquals(library, opened.get());
        assertThrows(java.io.IOException.class, () -> MusicLibrary.openFolder(null, opened::set));
    }

    @Test
    void refreshDefersFreshOrOpenWindowsCopiesAndIgnoresIncompleteMedia() throws Exception {
        var file = temp.resolve("Кино - Трек.wav");
        byte[] audio = new byte[3200];
        try (var stream = new AudioInputStream(new ByteArrayInputStream(audio),
                new AudioFormat(8000, 16, 1, true, false), audio.length / 2)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, file.toFile());
        }
        Files.writeString(temp.resolve("interrupted.wav"), "not music");
        Files.writeString(temp.resolve("copying.mp3.part"), "temporary file");
        var readiness = new ManagedFileReadiness();
        assertEquals(ManagedFileReadiness.State.SETTLING, readiness.check(file, Files.getLastModifiedTime(file).toMillis()));
        if (System.getProperty("os.name").startsWith("Windows")) {
            try (var writer = Files.newByteChannel(file, StandardOpenOption.WRITE)) {
                assertTrue(writer.isOpen());
                assertEquals(ManagedFileReadiness.State.SETTLING, readiness.check(file, System.currentTimeMillis() + 2000));
            }
        }
        readiness.recordImported(file);
        var found = MusicLibrary.scanFolder(temp, readiness);
        assertEquals(List.of("Кино - Трек"), found.tracks().stream().map(MusicTrack::title).toList());
    }

    @Test
    void spotifyReadsTheArtistSeparatelyAndMatchesBothFilenameOrders() {
        var row = JsonParser.parseString("""
                {"name":"Sextape","artists":[{"name":"Deftones"}],"duration_ms":243000,
                 "album":{"name":"Diamond Eyes","release_date":"2010-05-04"}}
                """).getAsJsonObject();
        var metadata = MetadataProviders.parseSpotifyTrack(row);
        assertEquals("Sextape", metadata.title());
        assertEquals("Deftones", metadata.artist());
        assertEquals("Diamond Eyes", metadata.album());
        assertEquals(243, metadata.seconds());
        var track = new MusicTrack(temp.resolve("track.mp3"), "Sextape - Deftones", true, MusicTrack.Source.FOLDER);
        var cover = new MusicTrack(temp.resolve("cover.mp3"), "Other Artist - Sextape", true, MusicTrack.Source.FOLDER);
        assertEquals(List.of(track), AuthorizedImport.localMatches(List.of(track, cover), metadata.artist(), metadata.title()));
        assertEquals(List.of(track, cover), AuthorizedImport.localMatches(List.of(track, cover), "", metadata.title()));
    }
}
