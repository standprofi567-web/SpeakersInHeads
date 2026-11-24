package dev.klxsh.speakersinheads.music;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SmartMusicImportTest {
    @TempDir Path temp;

    @Test
    void canonicalLinksStripSharingParametersAndTrackPagesCannotBecomeDirectAudio() {
        var spotify = MusicLink.parse("https://open.spotify.com/intl-de/track/4cOdK2wGLETKBW3PvgPWqT?si=share");
        assertEquals(MusicLink.Service.SPOTIFY, spotify.service());
        assertFalse(spotify.original().toString().contains("si="));
        var apple = MusicLink.parse("https://music.apple.com/us/album/album-slug/145111?i=145222");
        assertEquals("145222", apple.id());
        assertEquals("us", apple.market());
        assertEquals("123", MusicLink.parse("https://music.yandex.ru/album/456/track/123").id());
        assertEquals("dQw4w9WgXcQ", MusicLink.parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ&si=123").id());
        assertEquals("191125", MusicLink.parse("https://prod-1.storage.jamendo.com/download/track/191125/mp32/").id());
        assertThrows(IllegalArgumentException.class, () -> MusicLink.parse("https://stream.googlevideo.com/audio.mp3"));
        assertEquals(MusicLink.Service.DIRECT, MusicLink.parse("https://example.org/Музыка/Файл.wav").service());
    }

    @Test
    void publicMetadataOnlyReportsFieldsReturnedByEachProvider() {
        var apple = MetadataProviders.parseApple(JsonParser.parseString("""
                {"artistName":"Artist","trackName":"Title","trackTimeMillis":123003,
                 "collectionName":"Album","releaseDate":"2018-08-24T12:00:00Z"}
                """).getAsJsonObject());
        assertEquals("Artist", apple.artist());
        assertEquals("Album", apple.album());
        assertEquals(123, apple.seconds());
        assertEquals(2018, apple.year());
        var spotify = MetadataProviders.parseSpotify(JsonParser.parseString("""
                {"title":"Track","thumbnail_url":"https://i.scdn.co/image/test"}
                """).getAsJsonObject());
        assertEquals("Track", spotify.title());
        assertEquals("", spotify.artist());
        assertEquals(0, spotify.seconds());
        assertNotNull(spotify.artwork());
    }

    @Test
    void validatesAddressBeforeOpeningAnyConnection() throws Exception {
        for (String host : List.of("127.0.0.1", "10.0.0.2", "169.254.169.254", "100.64.0.1", "192.168.0.1",
                "198.51.100.1", "::1", "fc00::1", "fe80::1", "2001:db8::1")) {
            assertFalse(CatalogHttp.publicAddress(InetAddress.getByName(host)), host);
        }
        for (String url : List.of("ftp://example.org/file", "https://u@example.org/file",
                "https://example.org:8443/file", "http://internal.local/file", "http://example.org/file#part")) {
            assertThrows(IllegalArgumentException.class, () -> MusicLink.parse(url));
        }
        assertThrows(MusicCatalog.RequestException.class, () -> DirectAudioHttp.copy(
                URI.create("http://127.0.0.1/audio"), new ByteArrayOutputStream(), ignored -> {}));
    }

    @Test
    void directPreviewChecksFileBytesAndRejectsHtmlOrUnsupportedOpus() throws Exception {
        byte[] wav = wave((byte) 42);
        assertEquals(new AuthorizedImport.Preview("WAV", wav.length), DirectAudioHttp.previewAudio(wav, wav.length));
        assertThrows(IOException.class, () -> DirectAudioHttp.previewAudio("<!doctype html>".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 15));
        byte[] ogg = new byte[60];
        System.arraycopy("OggS".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, ogg, 0, 4);
        ogg[26] = 1;
        System.arraycopy("OpusHead".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, ogg, 28, 8);
        assertThrows(IOException.class, () -> DirectAudioHttp.previewAudio(ogg, -1));
        ogg[28] = 1;
        System.arraycopy("vorbis".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, ogg, 29, 6);
        assertEquals("OGG", DirectAudioHttp.previewAudio(ogg, -1).format());
    }

    @Test
    void spotifyDoesNotUseTheProviderNameAsTheArtistAndUsesOfficialAlbumArtwork() {
        var embed = MetadataProviders.parseSpotify(JsonParser.parseString("""
                {"title":"Sextape","author_name":"Spotify"}
                """).getAsJsonObject());
        assertEquals("Sextape", embed.title());
        assertEquals("", embed.artist());
        var track = MetadataProviders.parseSpotifyTrack(JsonParser.parseString("""
                {"name":"Sextape","artists":[{"name":"Deftones"}],"album":{"images":[{"width":300,"url":"https://i.scdn.co/image/cover"}]}}
                """).getAsJsonObject());
        assertEquals("Deftones", track.artist());
        assertEquals("https://i.scdn.co/image/cover", track.artwork().toString());
    }

    @Test
    void importUsesContentExtensionPreservesBytesAndDoesNotOverwriteOrDuplicate() throws Exception {
        Path library = temp.resolve("music");
        byte[] first = wave((byte) 12), newRecording = wave((byte) 15);
        var one = AuthorizedImport.save(library, "Кино", "Песня/тест", output -> output.write(first));
        assertEquals("Кино - Песня_тест.wav", one.file().getFileName().toString());
        assertEquals("WAV / 8000 Hz / Mono", one.quality());
        assertArrayEquals(first, Files.readAllBytes(one.file()));
        var same = AuthorizedImport.save(library, "", "other name", output -> output.write(first));
        assertTrue(same.existing());
        assertEquals(one.file(), same.file());
        var next = AuthorizedImport.save(library, "Кино", "Песня/тест", output -> output.write(newRecording));
        assertEquals("Кино - Песня_тест (2).wav", next.file().getFileName().toString());
        assertArrayEquals(first, Files.readAllBytes(one.file()));
        assertEquals("_CON", AuthorizedImport.safeName("", "CON"));
    }

    @Test
    void cancelledOrInvalidImportsLeaveNoFiles() throws Exception {
        Path library = temp.resolve("music");
        assertThrows(IOException.class, () -> AuthorizedImport.save(library, "", "Cancelled", output -> {
            output.write(new byte[100]);
            throw new IOException("Cancelled");
        }));
        assertThrows(IOException.class, () -> AuthorizedImport.save(library, "", "Invalid", output -> output.write("HTML".getBytes())));
        try (var files = Files.list(temp)) { assertEquals(List.of(library), files.toList()); }
        try (var files = Files.list(library)) { assertFalse(files.findAny().isPresent()); }
    }

    private static byte[] wave(byte value) throws IOException {
        var bytes = new byte[400];
        java.util.Arrays.fill(bytes, value);
        var encoded = new ByteArrayOutputStream();
        try (var audio = new AudioInputStream(new ByteArrayInputStream(bytes),
                new AudioFormat(8000, 16, 1, true, false), bytes.length / 2)) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, encoded);
        }
        return encoded.toByteArray();
    }
}
