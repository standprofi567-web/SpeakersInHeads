package dev.klxsh.speakersinheads.music;

import dev.klxsh.speakersinheads.audio.AudioStreamFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

public final class AuthorizedImport {
    public record Result(Path file, boolean existing, String quality, long bytes) {}
    public record Preview(String format, long bytes) {}
    @FunctionalInterface
    interface Transfer { void copy(OutputStream output) throws IOException; }

    private AuthorizedImport() {}

    public static Preview preview(MusicLink link) throws IOException {
        if (link.service() != MusicLink.Service.DIRECT) throw new MusicCatalog.RequestException("A direct audio URL is required.");
        return DirectAudioHttp.preview(link.original());
    }

    public static Result direct(MusicLink link, String artist, String title, Path library, IntConsumer progress) throws IOException {
        if (link.service() != MusicLink.Service.DIRECT) throw new MusicCatalog.RequestException("This service link cannot download audio.");
        String path = link.original().getPath();
        String fallback = path.substring(path.lastIndexOf('/') + 1).replaceFirst("(?i)\\.(mp3|ogg|wav)$", "");
        return save(library, artist, title.isBlank() ? fallback : title, out -> DirectAudioHttp.copy(link.original(), out, progress));
    }

    static Result fromJamendo(MusicCatalog.Entry track, Path library, IntConsumer progress) throws IOException {
        return save(library, track.artist(), track.title(), out -> CatalogHttp.copy(URI.create(track.audio()),
                CatalogHttp.JAMENDO, DirectAudioHttp.LIMIT, out, progress));
    }

    public static Result local(Path source, String artist, String title, Path library, IntConsumer progress) throws IOException {
        long size = Files.size(source);
        if (size > DirectAudioHttp.LIMIT) throw new MusicCatalog.RequestException("Audio exceeds 32 MiB.");
        String fallback = source.getFileName().toString().replaceFirst("(?i)\\.(mp3|ogg|wav)$", "");
        return save(library, artist, title.isBlank() ? fallback : title, output -> {
            try (var input = Files.newInputStream(source)) {
                byte[] buffer = new byte[16384];
                int total = 0;
                for (int read; (read = input.read(buffer)) != -1;) {
                    checkCancelled();
                    if (read > DirectAudioHttp.LIMIT - total) throw new MusicCatalog.RequestException("Audio exceeds 32 MiB.");
                    output.write(buffer, 0, read);
                    total += read;
                    progress.accept(size > 0 ? (int) Math.min(99, total * 100L / size) : -total);
                }
            }
        });
    }

    static Result save(Path library, String artist, String title, Transfer transfer) throws IOException {
        Path folder = library.toAbsolutePath().normalize();
        Files.createDirectories(folder);
        Path staged = Files.createTempFile(folder.getParent(), "sih-import-", ".part");
        Path ready = null;
        try {
            try (var output = Files.newOutputStream(staged)) { transfer.copy(output); }
            checkCancelled();
            String extension = detect(staged);
            ready = Files.createTempFile(folder.getParent(), "sih-verify-", extension);
            Files.move(staged, ready, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            javax.sound.sampled.AudioFormat format;
            try { format = AudioStreamFactory.probeLocalFile(ready); }
            catch (IOException corrupt) { throw new MusicCatalog.RequestException("The audio is invalid or its codec is unsupported."); }
            checkCancelled();
            String quality = extension.substring(1).toUpperCase(Locale.ROOT) + " / "
                    + Math.round(format.getSampleRate()) + " Hz / " + (format.getChannels() == 1 ? "Mono" : "Stereo");
            return publish(folder, ready, safeName(artist, title), extension, quality);
        } finally {
            Files.deleteIfExists(staged);
            if (ready != null) Files.deleteIfExists(ready);
        }
    }

    private static synchronized Result publish(Path folder, Path ready, String stem, String extension, String quality) throws IOException {
        long length = Files.size(ready);
        try (var files = Files.list(folder)) {
            for (var candidate : (Iterable<Path>) files::iterator) {
                checkCancelled();
                if (Files.isRegularFile(candidate, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        && candidate.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension)
                        && Files.size(candidate) == length && Files.mismatch(ready, candidate) == -1) {
                    return new Result(candidate, true, quality, length);
                }
            }
        }
        for (int i = 1; i <= 1000; i++) {
            checkCancelled();
            Path destination = folder.resolve(stem + (i == 1 ? "" : " (" + i + ")") + extension);
            try {
                // A link on the same filesystem publishes only complete data and never replaces an existing name.
                try {
                    Files.createLink(destination, ready);
                } catch (FileAlreadyExistsException collision) {
                    throw collision;
                } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
                    try { Files.move(ready, destination); }
                    catch (IOException moveFailure) {
                        moveFailure.addSuppressed(unsupported);
                        throw moveFailure;
                    }
                }
                return new Result(destination, false, quality, length);
            } catch (FileAlreadyExistsException collision) {
                // Keep the existing track and try the next numbered filename.
            }
        }
        throw new MusicCatalog.RequestException("Too many files use this track name.");
    }

    static String detect(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) { return detect(in.readNBytes(12)); }
    }

    static String detect(byte[] bytes) throws IOException {
        if (bytes.length >= 12 && tag(bytes, 0, "RIFF") && tag(bytes, 8, "WAVE")) return ".wav";
        if (bytes.length >= 4 && tag(bytes, 0, "OggS")) return ".ogg";
        if (bytes.length >= 4 && (tag(bytes, 0, "ID3") || ((bytes[0] & 255) == 255
                && (bytes[1] & 0xe0) == 0xe0 && (bytes[1] & 0x06) != 0
                && (bytes[2] & 0xf0) != 0xf0 && (bytes[2] & 0x0c) != 0x0c))) return ".mp3";
        throw new MusicCatalog.RequestException("Audio format is not supported. Use MP3, Ogg Vorbis or WAV.");
    }

    private static boolean tag(byte[] bytes, int at, String tag) {
        for (int i = 0; i < tag.length(); i++) if (bytes[at + i] != tag.charAt(i)) return false;
        return true;
    }

    static String safeName(String artist, String title) {
        String name = Normalizer.normalize(artist.isBlank() ? title : artist + " - " + title, Normalizer.Form.NFC)
                .replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_").strip().replaceAll("[. ]+$", "");
        if (name.isBlank()) name = "Imported Track";
        if (name.matches("(?i)^(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?:\\..*)?$")) name = "_" + name;
        if (name.codePointCount(0, name.length()) > 100) name = name.substring(0, name.offsetByCodePoints(0, 100));
        return name.replaceAll("[. ]+$", "");
    }

    public static List<MusicTrack> localMatches(String artist, String title) {
        return localMatches(MusicLibrary.tracks(), artist, title);
    }

    static List<MusicTrack> localMatches(List<MusicTrack> tracks, String artist, String title) {
        String name = normalized(title);
        String performer = normalized(artist);
        if (name.isBlank()) return List.of();
        return tracks.stream().filter(MusicTrack::available).filter(track -> {
            String libraryName = normalized(track.title());
            if (!performer.isBlank()) {
                return libraryName.equals(performer + " " + name) || libraryName.equals(name + " " + performer);
            }
            if (libraryName.equals(name)) return true;
            return java.util.Arrays.stream(track.title().split("\\s+[-–—]\\s+"))
                    .map(AuthorizedImport::normalized).anyMatch(name::equals);
        }).limit(5).toList();
    }

    public static List<MusicCatalog.Entry> licensedMatches(String artist, String title, long seconds) throws IOException {
        if (artist.isBlank() || title.isBlank()) return List.of();
        return MusicCatalog.licensedSearch(artist + " " + title).stream()
                .filter(MusicCatalog.Entry::canImport)
                .filter(entry -> normalized(entry.artist()).equals(normalized(artist))
                        && normalized(entry.title()).equals(normalized(title))
                        && (seconds <= 0 || entry.seconds() <= 0 || Math.abs(seconds - entry.seconds()) <= 3))
                .limit(5).toList();
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private static void checkCancelled() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Import cancelled.");
    }
}
